package io.github.vrcxandroid.bridge.webapi

import io.github.vrcxandroid.bridge.DotNetException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MultipartReader
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Tags its output so tests can see which helper ran with which flag. */
class FakeImages : UploadImageProcessor {
    override fun resizeToFitLimits(image: ByteArray, matchingDimensions: Boolean): ByteArray =
        "R$matchingDimensions:".toByteArray() + image

    override fun preparePrint(image: ByteArray, cropWhiteBorder: Boolean): ByteArray =
        "P$cropWhiteBorder:".toByteArray() + image
}

class WebApiEngineTest {
    private lateinit var server: MockWebServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MemoryBlobStore()
    private val jar = PersistentCookieJar(store, scope, 1000)
    private val client = HttpClients.create(jar, "VRCX 2026.09.16", null)
    private val engine = WebApiEngine({ client }, FakeImages(), jar::ensureLoaded)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
        scope.cancel()
    }

    private fun url(path: String = "/api/1/config") = server.url(path).toString()

    private fun exec(options: JsonObject): WebApiResult = runBlocking { engine.execute(options) }

    private fun take(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    @Test
    fun getWithDefaultUserAgentAndCompression() {
        server.enqueue(MockResponse().setBody("{\"ok\":true}").setHeader("Content-Type", "application/json"))
        val json = runBlocking { engine.executeJson("""{"url":"${url()}","method":"GET"}""") }
        assertEquals("""{"status":200,"message":"{\"ok\":true}"}""", json)
        val r = take()
        assertEquals("GET", r.method)
        assertEquals("VRCX 2026.09.16", r.getHeader("User-Agent"))
        assertEquals("br,gzip", r.getHeader("Accept-Encoding"))
        assertEquals(0L, r.bodySize)
    }

    @Test
    fun executeJsonEnvelopeEscapesAndParses() {
        server.enqueue(MockResponse().setBody("line1\n\"é\"\u0001"))
        val json = runBlocking { engine.executeJson("""{"url":"${url()}"}""") }
        val parsed = Json.parseToJsonElement(json) as JsonObject
        assertEquals(200, parsed["status"]!!.jsonPrimitive.int)
        assertEquals("line1\n\"é\"\u0001", parsed["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun postJsonBodyUsesCallerContentTypeNormalized() {
        server.enqueue(MockResponse().setBody("{}"))
        val result = exec(
            buildJsonObject {
                put("url", url("/api/1/auth/user/friends"))
                put("method", "POST")
                putJsonObject("headers") { put("Content-Type", "application/json;charset=utf-8") }
                put("body", "{\"a\":\"ü\"}")
                putJsonObject("params") { put("a", "ignored") }
                put("customMsg", "ignored")
            },
        )
        assertEquals(200, result.status)
        val r = take()
        assertEquals("POST", r.method)
        assertEquals("application/json; charset=utf-8", r.getHeader("Content-Type"))
        assertArrayEquals("{\"a\":\"ü\"}".toByteArray(Charsets.UTF_8), r.body.readByteArray())
    }

    @Test
    fun bodyWithoutContentTypeIsTextPlainUtf8AndLowercaseKeyIsIgnored() {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        exec(buildJsonObject { put("url", url()); put("method", "put"); put("body", "x") })
        val first = take()
        assertEquals("PUT", first.method)
        assertEquals("text/plain; charset=utf-8", first.getHeader("Content-Type"))
        exec(
            buildJsonObject {
                put("url", url()); put("method", "DELETE"); put("body", "{}")
                putJsonObject("headers") { put("content-type", "application/json") }
            },
        )
        val second = take()
        assertEquals("DELETE", second.method)
        assertEquals("text/plain; charset=utf-8", second.getHeader("Content-Type"))
        assertEquals("{}", second.body.readUtf8())
    }

    @Test
    fun getIgnoresBodyAndPostWithoutBodyIsEmpty() {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        exec(buildJsonObject { put("url", url()); put("method", "GET"); put("body", "nope") })
        assertEquals(0L, take().bodySize)
        exec(buildJsonObject { put("url", url()); put("method", "POST") })
        val r = take()
        assertEquals("POST", r.method)
        assertEquals("0", r.getHeader("Content-Length"))
        assertNull(r.getHeader("Content-Type"))
    }

    @Test
    fun headersRefererAndCallerUserAgent() {
        server.enqueue(MockResponse())
        exec(
            buildJsonObject {
                put("url", url())
                putJsonObject("headers") {
                    put("Referer", "https://vrcx.app")
                    put("VRCX-ID", "1234")
                    put("User-Agent", "custom")
                    put("X-Flag", true)
                    put("X-Num", 12)
                    put("Content-Type", "ignored/for-get")
                    put("bad header", "skipped")
                }
            },
        )
        val r = take()
        assertEquals("https://vrcx.app/", r.getHeader("Referer"))
        assertEquals("1234", r.getHeader("VRCX-ID"))
        assertEquals("custom", r.getHeader("User-Agent"))
        assertEquals("True", r.getHeader("X-Flag"))
        assertEquals("12", r.getHeader("X-Num"))
        assertNull(r.getHeader("Content-Type"))
    }

    @Test
    fun invalidRefererIsMinusOne() {
        val result = exec(buildJsonObject { put("url", url()); putJsonObject("headers") { put("Referer", "not a uri") } })
        assertEquals(WebApiResult(-1, "Invalid URI: The format of the URI could not be determined."), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun headerValuesWithNewLinesAreRejected() {
        val result = exec(buildJsonObject { put("url", url()); putJsonObject("headers") { put("X-Evil", "a\r\nInjected: 1") } })
        assertEquals(-1, result.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun nonSuccessStatusesAreResults() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"\\\"Missing Credentials\\\"\"}}"))
        val result = exec(buildJsonObject { put("url", url()) })
        assertEquals(401, result.status)
        assertTrue(result.message.contains("Missing Credentials"))
    }

    @Test
    fun imageAndOctetStreamBecomePngDataUrls() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "image/jpeg"))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setHeader("Content-Type", "application/octet-stream; x=y"))
        server.enqueue(MockResponse().setBody("text").setHeader("Content-Type", "Image/PNG")) // .NET compares case-sensitively
        val expected = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        assertEquals(WebApiResult(200, expected), exec(buildJsonObject { put("url", url("/a.jpg")) }))
        assertEquals(WebApiResult(200, expected), exec(buildJsonObject { put("url", url("/b")) }))
        assertEquals(WebApiResult(200, "text"), exec(buildJsonObject { put("url", url("/c")) }))
    }

    @Test
    fun transportFailureIsMinusOne() {
        val dead = url()
        server.shutdown()
        val result = exec(buildJsonObject { put("url", dead) })
        assertEquals(-1, result.status)
        assertTrue(result.message.isNotEmpty())
        val json = runBlocking { engine.executeJson("""{"url":"$dead"}""") }
        assertEquals(-1, (Json.parseToJsonElement(json) as JsonObject)["status"]!!.jsonPrimitive.int)
    }

    @Test
    fun badOptionsAreMinusOneOrRejections() {
        assertEquals(WebApiResult(-1, "The given key 'url' was not present in the dictionary."), exec(buildJsonObject { put("method", "GET") }))
        assertEquals(-1, exec(buildJsonObject { put("url", "relative/path") }).status)
        assertEquals(WebApiResult(-1, "The 'ftp' scheme is not supported."), exec(buildJsonObject { put("url", "ftp://x/y") }))
        assertEquals(
            """{"status":-1,"message":"Object reference not set to an instance of an object."}""",
            runBlocking { engine.executeJson("null") },
        )
        try {
            runBlocking { engine.executeJson("[1]") }
            throw AssertionError("expected a rejection")
        } catch (e: DotNetException) {
            assertEquals("JsonSerializationException", e.type)
        }
        try {
            runBlocking { engine.executeJson("{not json") }
            throw AssertionError("expected a rejection")
        } catch (e: Exception) {
            assertTrue(e !is AssertionError)
        }
    }

    @Test
    fun uploadFilePutIsByteExactForS3() {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        val data = ByteArray(300) { it.toByte() }
        val md5 = java.security.MessageDigest.getInstance("MD5").digest(data)
        val result = exec(
            buildJsonObject {
                put("url", url("/bucket/file?X-Amz-Signature=abc"))
                put("method", "GET") // request.js default; ignored for uploads
                put("uploadFilePUT", true)
                put("fileData", Base64.getEncoder().encodeToString(data))
                put("fileMIME", "image/png")
                // Non-canonical but valid base64 (whitespace): upstream decodes and re-encodes.
                put("fileMD5", Base64.getMimeEncoder(4, "\n".toByteArray()).encodeToString(md5))
            },
        )
        assertEquals(200, result.status)
        val r = take()
        assertEquals("PUT", r.method)
        assertEquals("/bucket/file?X-Amz-Signature=abc", r.path)
        assertEquals("image/png", r.getHeader("Content-Type"))
        assertEquals(Base64.getEncoder().encodeToString(md5), r.getHeader("Content-MD5"))
        assertArrayEquals(data, r.body.readByteArray())

        exec(
            buildJsonObject {
                put("url", url("/sig"))
                put("uploadFilePUT", false) // presence, not truthiness, selects the variant
                put("fileData", "AAEC")
                put("fileMIME", "application/x-rsync-signature")
            },
        )
        val sig = take()
        assertEquals("PUT", sig.method)
        assertEquals("application/x-rsync-signature", sig.getHeader("Content-Type"))
        assertNull(sig.getHeader("Content-MD5"))
        assertArrayEquals(byteArrayOf(0, 1, 2), sig.body.readByteArray())
    }

    @Test
    fun uploadFilePutRejectsBadInput() {
        assertEquals(
            WebApiResult(-1, "The format of value 'image/png; charset=utf-8' is invalid."),
            exec(buildJsonObject { put("url", url()); put("uploadFilePUT", true); put("fileData", "AA=="); put("fileMIME", "image/png; charset=utf-8") }),
        )
        assertEquals(-1, exec(buildJsonObject { put("url", url()); put("uploadFilePUT", true); put("fileData", "!!"); put("fileMIME", "image/png") }).status)
        assertEquals(
            WebApiResult(-1, "The given key 'fileMIME' was not present in the dictionary."),
            exec(buildJsonObject { put("url", url()); put("uploadFilePUT", true); put("fileData", "AA==") }),
        )
        assertEquals(0, server.requestCount)
    }

    private class Part(val headers: okhttp3.Headers, val body: ByteArray) {
        val disposition get() = headers["Content-Disposition"]
        val type get() = headers["Content-Type"]
        val text get() = String(body, Charsets.UTF_8)
    }

    private fun parts(r: RecordedRequest): List<Part> {
        val contentType = r.getHeader("Content-Type")!!
        assertTrue(contentType, contentType.startsWith("multipart/form-data; boundary=---------------------------"))
        val boundary = contentType.substringAfter("boundary=")
        val reader = MultipartReader(r.body, boundary)
        val out = ArrayList<Part>()
        while (true) {
            val part = reader.nextPart() ?: break
            out.add(Part(part.headers, part.body.readByteArray()))
        }
        return out
    }

    @Test
    fun uploadImageLegacyLayout() {
        server.enqueue(MockResponse())
        val image = byteArrayOf(9, 8, 7)
        exec(
            buildJsonObject {
                put("url", url("/api/1/invite/usr_x/photo"))
                put("uploadImageLegacy", true)
                put("postData", "{\"instanceId\":\"wrld_1:1\",\"messageSlot\":0}")
                put("imageData", Base64.getEncoder().encodeToString(image))
                putJsonObject("headers") { put("X-Test", "1") }
            },
        )
        val r = take()
        assertEquals("POST", r.method)
        assertEquals("1", r.getHeader("X-Test"))
        val p = parts(r)
        assertEquals(2, p.size)
        assertEquals("form-data; name=\"data\"", p[0].disposition)
        assertEquals("text/plain; charset=utf-8", p[0].type)
        assertEquals("{\"instanceId\":\"wrld_1:1\",\"messageSlot\":0}", p[0].text)
        assertEquals("form-data; name=\"image\"; filename=\"image.png\"", p[1].disposition)
        assertEquals("image/png", p[1].type)
        assertArrayEquals("Rfalse:".toByteArray() + image, p[1].body)
    }

    @Test
    fun uploadImageLayout() {
        server.enqueue(MockResponse())
        exec(
            buildJsonObject {
                put("url", url("/api/1/file/image"))
                put("uploadImage", false)
                put("matchingDimensions", true)
                put("postData", "{\"tag\":\"sticker\",\"maskTag\":\"square\",\"frames\":12,\"flag\":true,\"ratio\":12.5,\"none\":null}")
                put("imageData", "AQI=")
            },
        )
        val p = parts(take())
        assertEquals(listOf("tag", "maskTag", "frames", "flag", "ratio", "none", "file"), p.map { it.disposition!!.substringAfter("name=\"").substringBefore('"') })
        assertEquals(listOf("sticker", "square", "12", "True", "12.5", ""), p.dropLast(1).map { it.text })
        assertTrue(p.dropLast(1).all { it.type == "text/plain; charset=utf-8" })
        assertEquals("form-data; name=\"file\"; filename=\"blob\"", p.last().disposition)
        assertEquals("image/png", p.last().type)
        assertArrayEquals("Rtrue:".toByteArray() + byteArrayOf(1, 2), p.last().body)
    }

    @Test
    fun uploadImageWithoutMatchingDimensionsDefaultsToFalse() {
        server.enqueue(MockResponse())
        exec(buildJsonObject { put("url", url()); put("uploadImage", true); put("imageData", "AQI=") })
        val p = parts(take())
        assertEquals(1, p.size)
        assertArrayEquals("Rfalse:".toByteArray() + byteArrayOf(1, 2), p[0].body)
    }

    @Test
    fun uploadPrintPutsImageFirst() {
        server.enqueue(MockResponse())
        exec(
            buildJsonObject {
                put("url", url("/api/1/prints"))
                put("method", "GET")
                put("uploadImagePrint", true)
                put("cropWhiteBorder", true)
                put("postData", "{\"note\":\"hello\",\"timestamp\":\"2026-09-25T10:00:00.000Z\"}")
                put("imageData", "AQI=")
            },
        )
        val r = take()
        assertEquals("POST", r.method)
        val p = parts(r)
        assertEquals("form-data; name=\"image\"; filename=\"image\"", p[0].disposition)
        assertEquals("image/png", p[0].type)
        assertEquals((p[0].body.size).toString(), p[0].headers["Content-Length"])
        assertArrayEquals("Ptrue:".toByteArray() + byteArrayOf(1, 2), p[0].body)
        assertEquals("form-data; name=\"note\"", p[1].disposition)
        assertEquals("hello", p[1].text)
        assertEquals("2026-09-25T10:00:00.000Z", p[2].text)
        assertEquals("text/plain; charset=utf-8", p[2].type)
    }

    @Test
    fun uploadDispatchOrderPrefersLegacy() {
        server.enqueue(MockResponse())
        exec(buildJsonObject { put("url", url()); put("uploadImagePrint", true); put("uploadImageLegacy", true); put("imageData", "AQI=") })
        val p = parts(take())
        assertEquals("form-data; name=\"image\"; filename=\"image.png\"", p.single().disposition)
    }

    @Test
    fun uploadWithoutImageDataIsMinusOne() {
        assertEquals(
            WebApiResult(-1, "The given key 'imageData' was not present in the dictionary."),
            exec(buildJsonObject { put("url", url()); put("uploadImage", true) }),
        )
    }

    @Test
    fun cookiesFromResponsesAreSentAndPersisted() {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "auth=authcookie_x; Path=/; HttpOnly"))
        server.enqueue(MockResponse())
        exec(buildJsonObject { put("url", url("/api/1/auth/user")) })
        take()
        exec(buildJsonObject { put("url", url("/api/1/auth/user")) })
        assertEquals("auth=authcookie_x", take().getHeader("Cookie"))
        runBlocking { jar.flush() }
        assertTrue(String(Base64.getDecoder().decode(store.blob!!)).contains("\"Value\":\"authcookie_x\""))
    }

    @Test
    fun redirectsFollowDotNetRules() {
        // 302 after POST: GET without body; Authorization dropped.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/after"))
        server.enqueue(MockResponse().setBody("done"))
        val result = exec(
            buildJsonObject {
                put("url", url("/start")); put("method", "POST"); put("body", "x")
                putJsonObject("headers") { put("Authorization", "Bearer t") }
            },
        )
        assertEquals(WebApiResult(200, "done"), result)
        take()
        val second = take()
        assertEquals("GET", second.method)
        assertEquals("/after", second.path)
        assertNull(second.getHeader("Authorization"))
        assertEquals(0L, second.bodySize)

        // 307 keeps method and body.
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/again"))
        server.enqueue(MockResponse())
        exec(buildJsonObject { put("url", url("/p")); put("method", "PUT"); put("body", "payload") })
        take()
        val kept = take()
        assertEquals("PUT", kept.method)
        assertEquals("payload", kept.body.readUtf8())
    }

    @Test
    fun redirectLimitReturnsTheLastRedirect() {
        repeat(51) { server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/loop")) }
        val result = exec(buildJsonObject { put("url", url("/loop")) })
        assertEquals(301, result.status)
        assertEquals(51, server.requestCount)
    }

    @Test
    fun executeVariantReturnsTupleObject() {
        server.enqueue(MockResponse().setResponseCode(204))
        val bridge = WebApiBridge(engine, jar) {}
        val out = runBlocking {
            bridge.invoke("Execute", JsonArray(listOf(buildJsonObject { put("url", url()); put("method", "HEAD") })))
        } as JsonObject
        assertEquals(JsonPrimitive(204), out["Item1"])
        assertEquals(JsonPrimitive(""), out["Item2"])
        assertEquals("HEAD", take().method)
    }

    @Test
    fun bridgeCookieMethods() = runBlocking {
        var webViewCleared = 0
        val bridge = WebApiBridge(engine, jar) { webViewCleared++ }
        val blob = NetCookieCodec.encodeBase64(
            listOf(StoredCookie(okhttp3.Cookie.Builder().name("auth").value("v").hostOnlyDomain(server.hostName).build(), System.currentTimeMillis())),
        )
        bridge.invoke("SetCookies", JsonArray(listOf(JsonPrimitive(blob))))
        val exported = (bridge.invoke("GetCookies", JsonArray(emptyList())) as JsonPrimitive).content
        assertEquals("auth", NetCookieCodec.decodeBase64(exported, System.currentTimeMillis()).single().cookie.name)
        bridge.invoke("ClearCookies", JsonArray(emptyList()))
        assertEquals(1, webViewCleared)
        assertEquals("W10=", store.blob)
        assertEquals("W10=", (bridge.invoke("GetCookies", JsonArray(emptyList())) as JsonPrimitive).content)
    }
}
