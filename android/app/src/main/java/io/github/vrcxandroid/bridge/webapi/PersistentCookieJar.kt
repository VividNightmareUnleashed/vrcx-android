package io.github.vrcxandroid.bridge.webapi

import android.util.Log
import io.github.vrcxandroid.bridge.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Where the jar's base64 blob lives (the `cookies` table, key `default`). */
interface CookieBlobStore {
    /** The stored blob, or null when there is none. */
    suspend fun load(): String?

    /**
     * Calls [snapshot] at the point where the write is ordered with every other database access (the SQLite lane) and
     * stores its result; a null result means there is nothing to write. Taking the snapshot there, and not when the
     * save was requested, keeps the stored blob in the order of the jar's changes.
     */
    suspend fun save(snapshot: () -> String?)
}

/**
 * The persistent WebApi cookie jar.
 *
 * - Cookies are keyed by (name, domain, path) and matched with OkHttp's domain/path/secure rules. Secure cookies are
 *   kept (upstream loses them through its `http://` enumeration; that quirk is intentionally not replicated).
 * - The stored blob is read on first use, not at construction, because the database lives on the SQLite lane.
 * - A change schedules a save at most [saveDelayMs] later (upstream checks a dirty flag every second); nothing runs
 *   while the jar is unchanged. [flush] saves immediately (host `onStop`, restart).
 * - Database import: [invalidate] runs on the SQLite lane before the file is replaced. It drops the in-memory session,
 *   and every save that is still queued finds nothing to write, so the old session never reaches the imported
 *   database. The next use reads the imported cookies.
 */
class PersistentCookieJar(
    private val store: CookieBlobStore,
    private val scope: CoroutineScope,
    private val saveDelayMs: Long = 1000,
    private val now: () -> Long = System::currentTimeMillis,
) : CookieJar {
    private data class Key(val name: String, val domain: String, val path: String)

    private val lock = Any()
    private val entries = LinkedHashMap<Key, StoredCookie>()
    private val loadMutex = Mutex()

    /** Written under [lock]; read without it on the fast paths. */
    @Volatile
    private var loaded = false
    private var dirty = false
    /** Incremented by [invalidate]; a load that started in an older epoch is discarded. */
    private var epoch = 0L
    private var saveJob: Job? = null

    /** Loads the stored cookies once. Cookies added before the load finished win over stored ones. */
    suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            while (!loaded) {
                val startEpoch = synchronized(lock) { epoch }
                val stored = try {
                    store.load()?.let { NetCookieCodec.decodeBase64(it, now()) }.orEmpty()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load cookies ${SafeLog.kind(e)}")
                    emptyList()
                }
                synchronized(lock) {
                    // The database was replaced while this load ran: read the new one instead.
                    if (epoch != startEpoch) return@synchronized
                    val added = LinkedHashMap(entries)
                    entries.clear()
                    stored.forEach { putLocked(it) }
                    entries.putAll(added)
                    loaded = true
                }
            }
        }
    }

    private fun ensureLoadedBlocking() {
        if (!loaded) runBlocking { ensureLoaded() }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoadedBlocking()
        val time = now()
        synchronized(lock) {
            val result = ArrayList<Cookie>()
            val it = entries.values.iterator()
            while (it.hasNext()) {
                val cookie = it.next().cookie
                if (cookie.expiresAt <= time) {
                    it.remove()
                    markDirtyLocked()
                } else if (cookie.matches(url)) {
                    result.add(cookie)
                }
            }
            return result
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        ensureLoadedBlocking()
        val time = now()
        synchronized(lock) {
            for (cookie in cookies) putLocked(StoredCookie(cookie, time))
            markDirtyLocked()
        }
    }

    /** `WebApi.GetCookies()`: base64 of the .NET cookie JSON. Marks the jar dirty, as upstream does. */
    suspend fun exportBase64(): String {
        ensureLoaded()
        val time = now()
        return synchronized(lock) {
            markDirtyLocked()
            NetCookieCodec.encodeBase64(entries.values.filter { it.cookie.expiresAt > time })
        }
    }

    /** `WebApi.SetCookies(b64)`: merges cookies; unreadable data is logged and ignored. */
    suspend fun importBase64(base64: String?) {
        ensureLoaded()
        synchronized(lock) {
            try {
                NetCookieCodec.decodeBase64(base64.orEmpty(), now()).forEach { putLocked(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set cookies ${SafeLog.kind(e)}")
            }
            markDirtyLocked()
        }
    }

    /** `WebApi.ClearCookies()`: empties the jar and persists the empty list (`W10=`) now. */
    suspend fun clear() {
        loadMutex.withLock {
            synchronized(lock) {
                entries.clear()
                dirty = true
                loaded = true
            }
        }
        flush()
    }

    /** Saves now if anything changed since the last save. */
    suspend fun flush() {
        // Every change loads the jar first, so an unloaded jar has nothing to write (and restart stays cheap). The
        // snapshot on the lane checks again: this is only the fast path that skips the lane hop.
        if (synchronized(lock) { !loaded || !dirty }) return
        var snapshotEpoch = -1L
        try {
            store.save {
                synchronized(lock) {
                    if (!loaded || !dirty) return@save null
                    dirty = false
                    snapshotEpoch = epoch
                    val time = now()
                    NetCookieCodec.encodeBase64(entries.values.filter { it.cookie.expiresAt > time })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cookies ${SafeLog.kind(e)}")
            synchronized(lock) { if (snapshotEpoch == epoch && loaded) dirty = true }
        }
    }

    /**
     * Forgets the in-memory session because the database file is being replaced. Called on the SQLite lane before the
     * replacement, so saves queued behind it write nothing; the next use loads the new file's cookies. Never suspends.
     */
    fun invalidate() {
        synchronized(lock) {
            epoch++
            entries.clear()
            dirty = false
            loaded = false
            saveJob?.cancel()
            saveJob = null
        }
    }

    /** Drops the in-memory cookies and reads them again from the store. */
    suspend fun reload() {
        invalidate()
        ensureLoaded()
    }

    /** Snapshot of the live cookies (tests, diagnostics). */
    fun cookies(): List<StoredCookie> {
        val time = now()
        return synchronized(lock) { entries.values.filter { it.cookie.expiresAt > time } }
    }

    private fun putLocked(stored: StoredCookie) {
        val c = stored.cookie
        val key = Key(c.name, c.domain, c.path)
        entries.remove(key)
        if (c.expiresAt > now()) entries[key] = stored
    }

    private fun markDirtyLocked() {
        dirty = true
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(saveDelayMs)
            // Changes made while this save runs schedule the next one.
            synchronized(lock) { if (saveJob === coroutineContext[Job]) saveJob = null }
            flush()
        }
    }

    private companion object {
        const val TAG = "WebApiCookies"
    }
}
