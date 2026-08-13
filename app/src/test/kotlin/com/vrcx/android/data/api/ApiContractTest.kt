package com.vrcx.android.data.api

import com.vrcx.android.data.api.model.NotificationV2
import com.vrcx.android.data.api.model.TwoFactorAuthRequest
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
    fun `each two factor method posts to its own verification endpoint`() = runTest {
        repeat(3) { server.enqueue(jsonResponse("{\"verified\":true}")) }
        val api = retrofit().create(AuthApi::class.java)

        api.verifyTotp(TwoFactorAuthRequest("123456"))
        api.verifyOtp(TwoFactorAuthRequest("1234-5678"))
        api.verifyEmailOtp(TwoFactorAuthRequest("123456"))

        val totp = server.takeRequest()
        assertEquals("POST", totp.method)
        assertEquals("/auth/twofactorauth/totp/verify", totp.path)
        assertEquals("{\"code\":\"123456\"}", totp.body.readUtf8())

        // Recovery codes carry the hyphen at position 4 and use the OTP endpoint.
        val otp = server.takeRequest()
        assertEquals("POST", otp.method)
        assertEquals("/auth/twofactorauth/otp/verify", otp.path)
        assertEquals("{\"code\":\"1234-5678\"}", otp.body.readUtf8())

        val emailOtp = server.takeRequest()
        assertEquals("POST", emailOtp.method)
        assertEquals("/auth/twofactorauth/emailotp/verify", emailOtp.path)
        assertEquals("{\"code\":\"123456\"}", emailOtp.body.readUtf8())
    }

    @Test
    fun `a request invite reports the platform it was sent from`() = runTest {
        server.enqueue(jsonResponse("{}"))
        val api = retrofit().create(NotificationApi::class.java)

        api.sendRequestInvite("usr_target")

        val request = server.takeRequest()
        assertEquals("/requestInvite/usr_target", request.path)
        assertEquals("{\"platform\":\"android\"}", request.body.readUtf8())
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
