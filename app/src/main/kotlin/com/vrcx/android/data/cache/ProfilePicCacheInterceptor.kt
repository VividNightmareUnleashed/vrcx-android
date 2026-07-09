package com.vrcx.android.data.cache

import coil3.intercept.Interceptor
import coil3.request.ImageResult
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

        if (!url.contains("vrchat.cloud")) {
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
}
