package io.github.vrcxandroid.bridge.webapi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

    override suspend fun save(snapshot: () -> String?) {
        val value = snapshot() ?: return
        saves++
        blob = value
    }

    fun json(): String? = blob?.let { String(Base64.getDecoder().decode(it)) }
}

/**
 * Stands in for the SQLite lane: a save only queues its block, and the test decides when (and in which order) the
 * blocks run. [replaceDatabase] is what an import does on the lane: fence the jar, then swap the stored blob.
 */
class LaneBlobStore(var blob: String? = null) : CookieBlobStore {
    val queued = ArrayDeque<() -> Unit>()
    var loadGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun load(): String? {
        val value = blob
        loadGate?.await()
        return value
    }

    override suspend fun save(snapshot: () -> String?) {
        queued.add { snapshot()?.let { blob = it } }
    }

    fun runQueued(reversed: Boolean = false) {
        val blocks = queued.toList().let { if (reversed) it.reversed() else it }
        queued.clear()
        blocks.forEach { it() }
    }

    fun replaceDatabase(jar: PersistentCookieJar, newBlob: String?) {
        jar.invalidate()
        blob = newBlob
    }
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

    private fun blobOf(vararg cookies: Pair<String, String>) = NetCookieCodec.encodeBase64(
        cookies.map { (name, value) ->
            StoredCookie(Cookie.Builder().name(name).value(value).hostOnlyDomain("api.vrchat.cloud").path("/").build(), clock)
        },
    )

    private fun storedValues(blob: String?) = NetCookieCodec.decodeBase64(blob!!, clock).map { "${it.cookie.name}=${it.cookie.value}" }

    @Test
    fun saveQueuedBehindAnImportDoesNotWriteTheOldSession() = runTest(dispatcher) {
        val lane = LaneBlobStore(blobOf("auth" to "android"))
        val jar = PersistentCookieJar(lane, backgroundScope, 1000) { clock }
        jar.saveFromResponse(api, listOf(setCookie("auth=android2; Path=/")))
        // The debounced save fires and its write is queued on the lane ...
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(1, lane.queued.size)
        // ... behind the import, which fences the jar and replaces the database file.
        lane.replaceDatabase(jar, blobOf("auth" to "pc"))
        lane.runQueued()
        assertEquals("the imported session survives", listOf("auth=pc"), storedValues(lane.blob))
        assertEquals(listOf("pc"), jar.loadForRequest(api).map { it.value })
        // Nothing is left that could write the Android session later.
        jar.flush()
        advanceTimeBy(5000)
        runCurrent()
        lane.runQueued()
        assertEquals(listOf("auth=pc"), storedValues(lane.blob))
    }

    @Test
    fun flushCalledAfterTheFenceWritesNothing() = runTest(dispatcher) {
        val lane = LaneBlobStore()
        val jar = PersistentCookieJar(lane, backgroundScope, 1000) { clock }
        jar.saveFromResponse(api, listOf(setCookie("auth=android; Path=/")))
        lane.replaceDatabase(jar, blobOf("auth" to "pc"))
        jar.flush() // e.g. the restart that follows the import
        advanceTimeBy(5000) // the debounced save was cancelled by the fence
        runCurrent()
        lane.runQueued()
        assertEquals(listOf("auth=pc"), storedValues(lane.blob))
    }

    @Test
    fun writesFollowTheJarStateNotTheOrderTheSavesWereRequested() = runTest(dispatcher) {
        val lane = LaneBlobStore()
        val jar = PersistentCookieJar(lane, backgroundScope, 1000) { clock }
        jar.saveFromResponse(api, listOf(setCookie("auth=1; Path=/")))
        jar.flush() // a pending save of the logged-in jar ...
        jar.clear() // ... and ClearCookies' immediate W10= save
        assertEquals(2, lane.queued.size)
        lane.runQueued(reversed = true) // even if the older request reaches the lane last
        assertEquals("W10=", lane.blob)
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(api))
    }

    @Test
    fun loadThatRacesAnImportReadsTheImportedDatabase() = runTest(dispatcher) {
        val lane = LaneBlobStore(blobOf("auth" to "android"))
        lane.loadGate = kotlinx.coroutines.CompletableDeferred()
        val jar = PersistentCookieJar(lane, backgroundScope, 1000) { clock }
        val loading = launch { jar.ensureLoaded() }
        runCurrent() // the load has read the old database and is waiting
        lane.replaceDatabase(jar, blobOf("auth" to "pc"))
        lane.loadGate!!.complete(Unit)
        loading.join()
        assertEquals(listOf("pc"), jar.cookies().map { it.cookie.value })
    }

    @Test
    fun failedSaveKeepsTheJarDirty() = runTest(dispatcher) {
        var fail = true
        val store = object : CookieBlobStore {
            var blob: String? = null
            override suspend fun load(): String? = null
            override suspend fun save(snapshot: () -> String?) {
                val value = snapshot() ?: return
                if (fail) throw IllegalStateException("database is locked")
                blob = value
            }
        }
        val jar = PersistentCookieJar(store, backgroundScope, 1000) { clock }
        jar.saveFromResponse(api, listOf(setCookie("auth=1; Path=/")))
        jar.flush()
        assertEquals(null, store.blob)
        fail = false
        jar.flush()
        assertEquals(listOf("auth=1"), storedValues(store.blob))
    }

    @Test
    fun corruptStoredBlobStartsEmpty() {
        val jar = jar(MemoryBlobStore("%%%"))
        assertEquals(emptyList<Cookie>(), jar.loadForRequest(api))
        assertEquals(emptyList<StoredCookie>(), jar.cookies())
    }
}
