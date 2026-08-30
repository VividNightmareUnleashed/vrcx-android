package com.vrcx.android.data.repository

/** Couples load-once caching to the account token that is allowed to publish the result. */
internal class FavoriteLoadCache {
    private val lock = Any()
    private val loadedKeys = mutableSetOf<String>()

    suspend fun <T> loadOnce(
        account: AccountScope,
        key: String,
        forceRefresh: Boolean,
        fetch: suspend () -> T,
        publish: (T) -> Unit,
    ) {
        val token = account.current()
        val shouldLoad = synchronized(lock) {
            if (forceRefresh) loadedKeys.remove(key)
            key !in loadedKeys
        }
        if (!shouldLoad) return

        val result = fetch()
        account.publishIfCurrent(token) {
            publish(result)
            synchronized(lock) { loadedKeys.add(key) }
        }
    }

    fun invalidate(key: String) {
        synchronized(lock) { loadedKeys.remove(key) }
    }

    fun clear() {
        synchronized(lock) { loadedKeys.clear() }
    }
}
