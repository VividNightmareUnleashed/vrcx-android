package com.vrcx.android.data.websocket

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the real socket against MockWebServer. The pipeline URL is a constant,
 * so the base client carries an interceptor that redirects it at the local
 * server — the socket itself is unmodified.
 */
@RunWith(RobolectricTestRunner::class)
class VRChatWebSocketConnectionTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer
    private var client: OkHttpClient? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
        server.shutdown()
    }

    private fun localClient(): PipelineOkHttpClient = PipelineOkHttpClient(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val local = original.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(original.newBuilder().url(local).build())
            }
            .build()
            .also { client = it }
    )

    /** Completes the closing handshake so MockWebServer can shut down cleanly. */
    private abstract class ClosingServerListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Test
    fun `frames that pile up behind a lagging collector are all delivered`() = runBlocking {
        // The login burst re-sends friend-online and friend-location for every
        // online friend while the collector is still working through the first
        // few. A bounded queue sheds that backlog silently, and the pipeline has
        // no replay: each discarded frame is a friend transition the app never
        // learns about.
        val frameCount = 1_000
        val allSent = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    repeat(frameCount) { index ->
                        webSocket.send("""{"type":"friend-online","content":{"userId":"usr_$index"}}""")
                    }
                    allSent.countDown()
                }
            })
        )

        val socket = VRChatWebSocket(json, localClient())
        val gate = CompletableDeferred<Unit>()
        val subscribed = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val collector = launch(Dispatchers.IO) {
            socket.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { event ->
                    // Stall on the first frame so the rest have to queue up.
                    if (received.isEmpty()) gate.await()
                    received += event.content!!.jsonObject["userId"]!!.jsonPrimitive.content
                }
        }

        // Subscribe before connecting: a SharedFlow with no subscriber drops.
        withTimeout(SETUP_TIMEOUT_MS) { subscribed.await() }
        socket.connect("test-token")

        assertEquals(true, allSent.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        // Let the reader thread finish handing the burst to the queue.
        delay(SETTLE_MS)
        gate.complete(Unit)

        withTimeout(DELIVERY_TIMEOUT_MS) {
            while (received.size < frameCount) delay(20)
        }
        assertEquals(frameCount, received.size)
        assertEquals("usr_0", received.first())
        assertEquals("usr_${frameCount - 1}", received.last())

        collector.cancel()
        socket.disconnect()
    }

    @Test
    fun `disconnect stops delivery and reports a disconnected socket`() = runBlocking {
        val opened = CompletableDeferred<WebSocket>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(webSocket)
                }
            })
        )

        val socket = VRChatWebSocket(json, localClient())
        val received = mutableListOf<PipelineEvent>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.IO) {
            socket.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { received += it }
        }
        withTimeout(SETUP_TIMEOUT_MS) { subscribed.await() }
        socket.connect("test-token")

        val serverSocket = withTimeout(SETUP_TIMEOUT_MS) { opened.await() }
        withTimeout(SETUP_TIMEOUT_MS) {
            while (socket.state.value != WebSocketState.CONNECTED) delay(5)
        }

        socket.disconnect()
        assertEquals(WebSocketState.DISCONNECTED, socket.state.value)

        serverSocket.send("""{"type":"friend-online","content":{"userId":"usr_late"}}""")
        delay(SETTLE_MS)
        assertEquals(emptyList<PipelineEvent>(), received)

        serverSocket.close(1000, null)
        collector.cancel()
    }

    @Test
    fun `a superseded socket can neither emit nor report the live one disconnected`() = runBlocking {
        // The socket left over from before a reconnect (or from the previous
        // account) is still on the wire while the replacement comes up. Its
        // frames belong to a session the app has already left, and its close
        // belongs to a connection nothing is waiting on — the generation gate is
        // the only thing keeping either out of the live connection's state.
        val staleClosed = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.send("""{"type":"friend-online","content":{"userId":"usr_stale"}}""")
                    webSocket.close(1000, null)
                    staleClosed.complete(Unit)
                }
            })
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {}))

        val socket = VRChatWebSocket(json, localClient())
        val received = mutableListOf<PipelineEvent>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.IO) {
            socket.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { received += it }
        }
        withTimeout(SETUP_TIMEOUT_MS) { subscribed.await() }

        socket.connect("token-a")
        withTimeout(SETUP_TIMEOUT_MS) {
            while (socket.state.value != WebSocketState.CONNECTED) delay(5)
        }

        socket.reconnectNow("token-b")
        withTimeout(SETUP_TIMEOUT_MS) { staleClosed.await() }
        withTimeout(SETUP_TIMEOUT_MS) {
            while (socket.state.value != WebSocketState.CONNECTED) delay(5)
        }
        delay(SETTLE_MS)

        assertEquals(emptyList<PipelineEvent>(), received)
        assertEquals(WebSocketState.CONNECTED, socket.state.value)

        collector.cancel()
        socket.disconnect()
    }

    @Test
    fun `a refused handshake asks for a new token instead of spinning the backoff`() = runBlocking {
        // The token lives in the connection URL and is captured once per service
        // start, so retrying it can never succeed — the socket would sit in
        // RECONNECTING against a dead credential with every realtime event gone.
        server.enqueue(MockResponse().setResponseCode(401))
        val rejected = CompletableDeferred<Unit>()
        val socket = VRChatWebSocket(json, localClient()) { rejected.complete(Unit) }

        socket.connect("stale-token")

        withTimeout(SETUP_TIMEOUT_MS) { rejected.await() }
        delay(SETTLE_MS)
        assertEquals(WebSocketState.DISCONNECTED, socket.state.value)

        socket.disconnect()
    }

    private companion object {
        const val SETUP_TIMEOUT_MS = 10_000L
        const val DELIVERY_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 500L
    }
}
