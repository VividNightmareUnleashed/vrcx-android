package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.InventoryResponse
import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.UnPlayerModerationRequest
import com.vrcx.android.data.api.model.UpdateCurrentUserRequest
import com.vrcx.android.data.api.model.VrcPrint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class ApiContractTest {
    private lateinit var server: MockWebServer

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `current user patch has a constructible converter and omits unrelated defaults`() = runTest {
        server.enqueue(jsonResponse("{\"id\":\"usr_test\"}"))
        val api = retrofit().create(UserApi::class.java)

        api.saveCurrentUser("usr_test", UpdateCurrentUserRequest(bio = "Hello"))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/users/usr_test", request.path)
        assertEquals("{\"bio\":\"Hello\"}", request.body.readUtf8())
    }

    @Test
    fun `inventory uses wrapped response single template endpoint and PUT consumption`() = runTest {
        server.enqueue(jsonResponse("""{"data":[{"id":"inv_one","templateId":"invt_one"}],"totalCount":1}"""))
        server.enqueue(jsonResponse("""{"id":"invt_one","name":"Template"}"""))
        server.enqueue(jsonResponse("{}"))
        val api = retrofit().create(InventoryApi::class.java)

        val inventory = api.getInventoryItems(n = 100, offset = 0)
        val template = api.getInventoryTemplate("invt_one")
        api.consumeInventoryBundle("inv_one")

        assertEquals(1, inventory.totalCount)
        assertEquals("invt_one", inventory.data.single().templateId)
        assertEquals("Template", template.name)
        assertEquals("/inventory?n=100&offset=0&order=newest", server.takeRequest().path)
        assertEquals("/inventory/template/invt_one", server.takeRequest().path)
        assertEquals("PUT", server.takeRequest().method)
    }

    @Test
    fun `unmoderate uses target and type in PUT body`() = runTest {
        server.enqueue(jsonResponse("{}"))
        val api = retrofit().create(PlayerModerationApi::class.java)

        api.unmoderatePlayer(UnPlayerModerationRequest("usr_target", "block"))

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/auth/user/unplayermoderate", request.path)
        assertEquals("{\"moderated\":\"usr_target\",\"type\":\"block\"}", request.body.readUtf8())
    }

    @Test
    fun `camel case notification and print timestamps decode`() {
        val notification = json.decodeFromString<NotificationV2>(
            """{"id":"notif","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z"}"""
        )
        val print = json.decodeFromString<VrcPrint>(
            """{"id":"prnt","createdAt":"2026-01-03T00:00:00Z"}"""
        )

        assertEquals("2026-01-01T00:00:00Z", notification.createdAt)
        assertEquals("2026-01-02T00:00:00Z", notification.updatedAt)
        assertEquals("2026-01-03T00:00:00Z", print.createdAt)
        assertEquals("{\"bio\":\"Hello\"}", json.encodeToString(UpdateCurrentUserRequest(bio = "Hello")))
    }

    private fun retrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
