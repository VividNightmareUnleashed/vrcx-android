package com.vrcx.android.data.websocket

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real socket against MockWebServer. The pipeline URL is a constant,
 * so the base client carries an interceptor that redirects it at the local
 * server — the socket itself is unmodified.
 */
@RunWith(RobolectricTestRunner::class)
class VRChatWebSocketConnectionTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var server: MockWebServer
    private lateinit var socketDispatcher: ExecutorCoroutineDispatcher
    private var client: OkHttpClient? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        socketDispatcher = Executors.newFixedThreadPool(SOCKET_TEST_THREADS).asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        client?.dispatcher?.executorService?.shutdown()
        client?.connectionPool?.evictAll()
        socketDispatcher.close()
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
            .also { client = it },
    )

    /** Completes the closing handshake so MockWebServer can shut down cleanly. */
    private open class ClosingServerListener : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Test
    fun `a normal burst is delivered in order behind a lagging collector`() = runBlocking {
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
            }),
        )

        val socket = VRChatWebSocket(json, localClient(), socketDispatcher)
        val gate = CompletableDeferred<Unit>()
        val subscribed = CompletableDeferred<Unit>()
        val received = Collections.synchronizedList(mutableListOf<String>())
        val collector = launch(socketDispatcher) {
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
        assertEquals((0 until frameCount).map { "usr_$it" }, received)

        collector.cancel()
        socket.disconnect()
    }

    @Test
    fun `malformed frame shapes do not terminate the frame pump`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":{},"content":{}}""")
                    webSocket.send("""{"type":"friend-online","content":[]}""")
                    webSocket.send("""{"type":"friend-online","content":{"userId":"usr_valid"}}""")
                }
            }),
        )

        val socket = VRChatWebSocket(json, localClient(), socketDispatcher)
        val subscribed = CompletableDeferred<Unit>()
        val unexpectedShapeDelivered = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<String>()
        val collector = launch(socketDispatcher) {
            socket.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { event ->
                    val content = event.content as? JsonObject
                    if (content == null) {
                        unexpectedShapeDelivered.complete(Unit)
                        return@collect
                    }
                    val userId = content["userId"]
                        ?.jsonPrimitive
                        ?.content
                    if (userId != null) delivered.complete(userId)
                }
        }
        withTimeout(SETUP_TIMEOUT_MS) { subscribed.await() }

        socket.connect("test-token")

        withTimeout(SETUP_TIMEOUT_MS) { unexpectedShapeDelivered.await() }
        assertEquals("usr_valid", withTimeout(SETUP_TIMEOUT_MS) { delivered.await() })

        collector.cancel()
        socket.disconnect()
    }

    @Test
    fun `an oversized frame requests ordered recovery before reconnecting`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Character count is below the limit; UTF-8 byte count is not.
                    webSocket.send("€".repeat((MAX_PIPELINE_FRAME_BYTES / 3).toInt() + 1))
                    webSocket.send(
                        """{"type":"friend-online","content":{"userId":"usr_rejected"}}""",
                    )
                }
            }),
        )
        val recoveredConnection = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    recoveredConnection.complete(Unit)
                    webSocket.send(
                        """{"type":"friend-online","content":{"userId":"usr_resynced"}}""",
                    )
                }
            }),
        )

        val socket = VRChatWebSocket(json, localClient(), socketDispatcher)
        val subscribed = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<String>()
        val recoveryRequested = CompletableDeferred<Unit>()
        val received = Collections.synchronizedList(mutableListOf<String>())
        val collector = launch(socketDispatcher) {
            socket.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { event ->
                    if (event === PipelineEvent.StreamGap) {
                        recoveryRequested.complete(Unit)
                        return@collect
                    }
                    event.content?.jsonObject
                        ?.get("userId")
                        ?.jsonPrimitive
                        ?.content
                        ?.let {
                            received += it
                            if (it == "usr_resynced") delivered.complete(it)
                        }
                }
        }
        withTimeout(SETUP_TIMEOUT_MS) { subscribed.await() }

        socket.connect("test-token")

        withTimeout(SETUP_TIMEOUT_MS) { recoveryRequested.await() }
        assertEquals(emptyList<String>(), received)
        assertEquals(1, server.requestCount)

        socket.reconnectNow("test-token")
        delay(SETTLE_MS)
        assertEquals("ordinary reconnects must wait for state reconciliation", 1, server.requestCount)

        socket.reconnectAfterRecovery("test-token")
        withTimeout(SETUP_TIMEOUT_MS) { recoveredConnection.await() }
        assertEquals("usr_resynced", withTimeout(SETUP_TIMEOUT_MS) { delivered.await() })
        assertEquals(listOf("usr_resynced"), received)
        assertEquals(2, server.requestCount)

        collector.cancel()
        socket.disconnect()
    }

    @Test
    fun `queue overflow emits one recovery marker after its accepted prefix`() = runBlocking {
        val frameCount = PIPELINE_FRAME_QUEUE_CAPACITY + 32
        val allSent = CountDownLatch(1)
        val closeCount = AtomicInteger()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    repeat(frameCount) { index ->
                        webSocket.send(
                            """{"type":"friend-online","content":{"userId":"usr_$index"}}""",
                        )
                    }
                    allSent.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeCount.incrementAndGet()
                    webSocket.close(1000, null)
                }
            }),
        )

        val socket = VRChatWebSocket(json, localClient(), socketDispatcher)
        val gate = CompletableDeferred<Unit>()
        val subscribed = CompletableDeferred<Unit>()
        val firstDelivered = CompletableDeferred<Unit>()
        val recoveryRequested = CompletableDeferred<Unit>()
        val recoveryCount = AtomicInteger()
        val received = Collections.synchronizedList(mutableListOf<String>())
        val collector = launch(socketDispatcher) {
            socket.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { event ->
                    if (event === PipelineEvent.StreamGap) {
                        recoveryCount.incrementAndGet()
                        recoveryRequested.complete(Unit)
                        return@collect
                    }
                    val userId = requireNotNull(event.content)
                        .jsonObject
                        .getValue("userId")
                        .jsonPrimitive
                        .content
                    received += userId
                    if (userId == "usr_0") {
                        firstDelivered.complete(Unit)
                        gate.await()
                    }
                }
        }
        withTimeout(SETUP_TIMEOUT_MS) { subscribed.await() }

        socket.connect("test-token")

        withTimeout(SETUP_TIMEOUT_MS) { firstDelivered.await() }
        assertEquals(true, allSent.await(SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        delay(SETTLE_MS)
        gate.complete(Unit)
        withTimeout(DELIVERY_TIMEOUT_MS) { recoveryRequested.await() }
        delay(SETTLE_MS)

        assertTrue(received.size in PIPELINE_FRAME_QUEUE_CAPACITY..PIPELINE_FRAME_QUEUE_CAPACITY + 1)
        assertEquals((0 until received.size).map { "usr_$it" }, received)
        assertEquals(1, recoveryCount.get())
        assertEquals(0, closeCount.get())
        assertEquals(1, server.requestCount)

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
            }),
        )

        val socket = VRChatWebSocket(json, localClient(), socketDispatcher)
        val received = mutableListOf<PipelineEvent>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch(socketDispatcher) {
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
    fun `a terminally disconnected socket cannot be revived by a late callback`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {}))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {}))

        val socket = VRChatWebSocket(json, localClient(), socketDispatcher)
        socket.connect("test-token")
        withTimeout(SETUP_TIMEOUT_MS) {
            while (socket.state.value != WebSocketState.CONNECTED) delay(5)
        }

        socket.disconnect()
        socket.reconnectNow("test-token")
        delay(SETTLE_MS)

        assertEquals(WebSocketState.DISCONNECTED, socket.state.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a direct connect replaces an older delayed reconnect job`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val recovered = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    recovered.complete(Unit)
                }
            }),
        )
        val socket = VRChatWebSocket(json, localClient(), socketDispatcher)

        socket.connect("token-a")
        withTimeout(SETUP_TIMEOUT_MS) {
            while (socket.state.value != WebSocketState.RECONNECTING) delay(5)
        }
        socket.connect("token-b")

        withTimeout(RECOVERY_TIMEOUT_MS) { recovered.await() }
        assertEquals(3, server.requestCount)

        socket.disconnect()
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
            }),
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(object : ClosingServerListener() {}))

        val socket = VRChatWebSocket(json, localClient(), socketDispatcher)
        val received = mutableListOf<PipelineEvent>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = launch(socketDispatcher) {
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
        val socket = VRChatWebSocket(json, localClient(), socketDispatcher) { rejected.complete(Unit) }

        socket.connect("stale-token")

        withTimeout(SETUP_TIMEOUT_MS) { rejected.await() }
        delay(SETTLE_MS)
        assertEquals(WebSocketState.DISCONNECTED, socket.state.value)

        socket.disconnect()
    }

    private companion object {
        const val SOCKET_TEST_THREADS = 4
        const val SETUP_TIMEOUT_MS = 10_000L
        const val RECOVERY_TIMEOUT_MS = 15_000L
        const val DELIVERY_TIMEOUT_MS = 20_000L
        const val SETTLE_MS = 500L
    }
}
