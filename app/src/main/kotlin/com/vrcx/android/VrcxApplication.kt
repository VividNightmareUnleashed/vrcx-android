package com.vrcx.android

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.vrcx.android.data.cache.ProfilePicCacheInterceptor
import com.vrcx.android.data.cache.ProfilePicCacheManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named

@HiltAndroidApp
class VrcxApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: Context): ImageLoader {
        val entryPoint = EntryPointAccessors.fromApplication(
            context, ImageLoaderEntryPoint::class.java
        )
        return ImageLoader.Builder(context)
            .components {
                add(ProfilePicCacheInterceptor(entryPoint.profilePicCacheManager()))
                add(AnimatedImageDecoder.Factory())
                add(OkHttpNetworkFetcherFactory(callFactory = { entryPoint.imageOkHttpClient() }))
            }
            .build()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImageLoaderEntryPoint {
    @Named("imageOkHttpClient")
    fun imageOkHttpClient(): OkHttpClient
    fun profilePicCacheManager(): ProfilePicCacheManager
}
