package com.vrcx.android.data.cache

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

class ProfilePicCacheInterceptor(
    private val cacheManager: ProfilePicCacheManager,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val url = when (data) {
            is String -> data
            else -> data.toString()
        }

        if (!isVrchatUrl(url)) {
            return chain.proceed()
        }

        val cachedFile: File? = cacheManager.getCachedFile(url)
        if (cachedFile != null) {
            val newRequest = chain.request.newBuilder()
                .data(cachedFile)
                .build()
            return chain.withRequest(newRequest).proceed()
        }

        return chain.proceed()
    }

    internal companion object {
        private const val VRCHAT_HOST = "vrchat.cloud"

        // Match the host, not the whole URL: a proxied image URL can carry the
        // VRChat host in its query string without being a VRChat asset.
        internal fun isVrchatUrl(url: String): Boolean {
            val host = url.toHttpUrlOrNull()?.host ?: return false
            return host == VRCHAT_HOST || host.endsWith(".$VRCHAT_HOST")
        }
    }
}
