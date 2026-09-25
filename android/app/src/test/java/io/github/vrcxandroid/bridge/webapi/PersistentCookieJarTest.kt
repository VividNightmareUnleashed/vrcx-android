package io.github.vrcxandroid.bridge.webapi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MemoryBlobStore(var blob: String? = null) : CookieBlobStore {
    var saves = 0
    var loads = 0
    override suspend fun load(): String? {
        loads++
        return blob
    }

    override suspend fun save(blob: String) {
        saves++
        this.blob = blob
    }

    fun json(): String? = blob?.let { String(Base64.getDecoder().decode(it)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentCookieJarTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private var clock = System.currentTimeMillis()
    private val api = "https://api.vrchat.cloud/api/1/auth/user".toHttpUrl()

    private fun jar(store: CookieBlobStore) = PersistentCookieJar(store, scope, 1000) { clock }

    private fun setCookie(header: String, url: okhttp3.HttpUrl = api) = Cookie.parse(url, header)!!

    @Test
    fun responseCookiesAreSavedWithinOneSecond() {
        val store = MemoryBlobStore()
        val jar = jar(store)
        jar.saveFromResponse(api, listOf(setCookie("auth=authcookie_1; Path=/; Secure; HttpOnly")))
        scope.runCurrent()
        assertEquals(0, store.saves)
        scope.advanceTimeBy(999)
        scope.runCurrent()
        assertEquals(0, store.saves)
        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(1, store.saves)
        val json = store.json()!!
        assertTrue(json, json.contains("\"Name\":\"auth\""))
        assertTrue(json, json.contains("\"Secure\":true")) // Secure cookies are persisted
        // No further saves while nothing changes.
        scope.advanceTimeBy(10_000)
        scope.runCurrent()
        assertEquals(1, store.saves)
    }

    @Test
    fun severalChangesInOneSecondAreOneSave() {
        val store = MemoryBlobStore()
        val jar = jar(store)
        jar.saveFromResponse(api, listOf(setCookie("a=1")))
        scope.advanceTimeBy(500)
        jar.saveFromResponse(api, listOf(setCookie("b=2")))
        scope.advanceTimeBy(501)
        scope.runCurrent()
        assertEquals(1, store.saves)
        assertTrue(store.json()!!.contains("\"Name\":\"b\""))
    }

    @Test
    fun loadsStoredCookiesLazilyForRequests() {
        val stored = NetCookieCodec.encodeBase64(
            listOf(StoredCookie(Cookie.Builder().name("auth").value("x").hostOnlyDomain("api.vrchat.cloud").secure().build(), clock)),
        )
        val store = MemoryBlobStore(stored)
        val jar = jar(store)
        assertEquals(0, store.loads)
        assertEquals(listOf("auth"), jar.loadForRequest(api).map { it.name })
        assertEquals(emptyList<Cookie>(), jar.loadForRequest("http://api.vrchat.cloud/".toHttpUrl()))
        assertEquals(emptyList<Cookie>(), jar.loadForRequest("https://example.com/".toHttpUrl()))
        assertEquals(1, store.loads)
    }

    @Test
    fun expiredResponseCookieDeletesAndExpiryIsHonoured() {
        val jar = jar(MemoryBlobStore())
        jar.saveFromResponse(api, listOf(setCookie("auth=1; Max-Age=60")))
        assertEquals(1, jar.loadForRequest(api).size)
        jar.saveFromResponse(api, listOf(setCookie("auth=; Max-Age=0")))
        assertEquals(0, jar.loadForRequest(api).size)
        jar.saveFromResponse(api, listOf(setCookie("auth=2; Max-Age=60")))
        clock += 61_000
        assertEquals(0, jar.loadForRequest(api).size)
    }

    @Test
    fun sameNameDomainPathReplaces() {
        val jar = jar(MemoryBlobStore())
        jar.saveFromResponse(api, listOf(setCookie("auth=1")))
        jar.saveFromResponse(api, listOf(setCookie("auth=2")))
        jar.saveFromResponse(api, listOf(setCookie("auth=3; Path=/api")))
        assertEquals(listOf("2", "3"), jar.loadForRequest(api).map { it.value }.sorted())
    }

    @Test
    fun getCookiesExportsAndMarksDirty() = runTest(dispatcher) {
        val store = MemoryBlobStore()
        val jar = PersistentCookieJar(store, backgroundScope, 1000) { clock }
        jar.saveFromResponse(api, listOf(setCookie("auth=abc")))
        jar.flush()
        assertEquals(1, store.saves)
        val exported = jar.exportBase64()
        val decoded = NetCookieCodec.decodeBase64(exported, clock)
        assertEquals("abc", decoded.single().cookie.value)
        jar.flush() // GetCookies marked the jar dirty (upstream forces a save for lastUserLoggedIn)
        assertEquals(2, store.saves)
    }

    @Test
    fun setCookiesMergesAndIgnoresGarbage() = runTest(dispatcher) {
        val store = MemoryBlobStore()
        val jar = PersistentCookieJar(store, backgroundScope, 1000) { clock }
        jar.saveFromResponse(api, listOf(setCookie("keep=1")))
        val blob = NetCookieCodec.encodeBase64(
            listOf(StoredCookie(Cookie.Builder().name("auth").value("pc").hostOnlyDomain("api.vrchat.cloud").build(), clock)),
        )
        jar.importBase64(blob)
        jar.importBase64("not base64 !!")
        jar.importBase64(null)
        assertEquals(listOf("auth", "keep"), jar.loadForRequest(api).map { it.name }.sorted())
    }

    @Test
    fun clearPersistsEmptyListImmediately() = runTest(dispatcher) {
        val store = MemoryBlobStore(
            NetCookieCodec.encodeBase64(listOf(StoredCookie(Cookie.Builder().name("a").value("1").hostOnlyDomain("api.vrchat.cloud").build(), clock))),
        )
        val jar = PersistentCookieJar(store, backgroundScope, 1000) { clock }
        jar.clear()
        assertEquals("W10=", store.blob)
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(api))
    }

    @Test
    fun flushOfAnUntouchedJarDoesNothing() = runTest(dispatcher) {
        val store = MemoryBlobStore("W10=")
        val jar = PersistentCookieJar(store, backgroundScope, 1000) { clock }
        jar.flush()
        assertEquals(0, store.saves)
        assertEquals(0, store.loads)
        jar.loadForRequest(api)
        jar.flush()
        assertEquals(0, store.saves)
        assertEquals(1, store.loads)
    }

    @Test
    fun cookiesSetBeforeLoadAreNotLostAndStoredOnesAreMerged() = runTest(dispatcher) {
        val store = MemoryBlobStore(
            NetCookieCodec.encodeBase64(listOf(StoredCookie(Cookie.Builder().name("old").value("1").hostOnlyDomain("api.vrchat.cloud").build(), clock))),
        )
        val jar = PersistentCookieJar(store, backgroundScope, 1000) { clock }
        jar.saveFromResponse(api, listOf(setCookie("new=2")))
        jar.flush()
        val names = NetCookieCodec.decodeBase64(store.blob!!, clock).map { it.cookie.name }.sorted()
        assertEquals(listOf("new", "old"), names)
    }

    @Test
    fun reloadReadsTheReplacedDatabase() = runTest(dispatcher) {
        val store = MemoryBlobStore()
        val jar = PersistentCookieJar(store, backgroundScope, 1000) { clock }
        jar.saveFromResponse(api, listOf(setCookie("mine=1")))
        store.blob = NetCookieCodec.encodeBase64(
            listOf(StoredCookie(Cookie.Builder().name("imported").value("2").hostOnlyDomain("api.vrchat.cloud").build(), clock)),
        )
        jar.reload()
        jar.flush() // nothing dirty after a reload: the imported blob stays
        assertEquals(listOf("imported"), jar.loadForRequest(api).map { it.name })
        assertEquals(listOf("imported"), NetCookieCodec.decodeBase64(store.blob!!, clock).map { it.cookie.name })
    }

    @Test
    fun corruptStoredBlobStartsEmpty() {
        val jar = jar(MemoryBlobStore("%%%"))
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(api))
        assertEquals(emptyList<StoredCookie>(), jar.cookies())
    }
}
