package com.vrcx.android.di

import com.vrcx.android.BuildConfig
import com.vrcx.android.data.api.AccountBoundCookieInterceptor
import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.AuthEventBus
import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.DedupInterceptor
import com.vrcx.android.data.api.ErrorInterceptor
import com.vrcx.android.data.api.FavoriteApi
import com.vrcx.android.data.api.FriendApi
import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.InstanceApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.InviteMessageApi
import com.vrcx.android.data.api.NotificationApi
import com.vrcx.android.data.api.PlayerModerationApi
import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.SessionCookieRequestInterceptor
import com.vrcx.android.data.api.SessionCookieResponseInterceptor
import com.vrcx.android.data.api.UserAgentInterceptor
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.websocket.PipelineOkHttpClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private const val NETWORK_TIMEOUT_SECONDS = 30L

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://api.vrchat.cloud/api/1/"

    /**
     * Shared across every client below: VRChat serves user images from the API
     * host, so image loads can reuse the API client's warm TLS connections
     * instead of handshaking again, and one pool replaces three. The clients are
     * still built separately rather than derived with `newBuilder()`, which
     * would copy interceptors and cookie jars across the boundaries that keep
     * the session isolated.
     */
    private val sharedConnectionPool = ConnectionPool()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        cookieJar: CookieJarImpl,
        authEventBus: AuthEventBus,
        deduplicator: RequestDeduplicator,
        accountBoundCookieInterceptor: AccountBoundCookieInterceptor,
    ): OkHttpClient {
        val sessionCookieRequestInterceptor = SessionCookieRequestInterceptor(cookieJar)
        val sessionCookieResponseInterceptor = SessionCookieResponseInterceptor(cookieJar)
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectionPool(sharedConnectionPool)
            .addInterceptor(sessionCookieRequestInterceptor)
            .addInterceptor(UserAgentInterceptor())
            .addInterceptor(ErrorInterceptor(authEventBus))
            // Remember missing resources globally so every repository benefits
            // from the failure cache without having to opt in.
            .addInterceptor(DedupInterceptor(deduplicator))
            .addNetworkInterceptor(sessionCookieResponseInterceptor)
            .addNetworkInterceptor(accountBoundCookieInterceptor)
            .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                },
            )
        }

        return builder.build()
    }

    @Provides
    @Singleton
    @Named("imageOkHttpClient")
    fun provideImageOkHttpClient(cookieJar: CookieJarImpl): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectionPool(sharedConnectionPool)
        .addInterceptor(SessionCookieRequestInterceptor(cookieJar))
        .addInterceptor(UserAgentInterceptor())
        .addNetworkInterceptor(SessionCookieResponseInterceptor(cookieJar))
        .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWebSocketOkHttpClient(): PipelineOkHttpClient = PipelineOkHttpClient(
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .addInterceptor(UserAgentInterceptor())
            .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
    )

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(
            json.asConverterFactory("application/json; charset=utf-8".toMediaType()),
        )
        .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)

    @Provides
    @Singleton
    fun provideFriendApi(retrofit: Retrofit): FriendApi = retrofit.create(FriendApi::class.java)

    @Provides
    @Singleton
    fun provideWorldApi(retrofit: Retrofit): WorldApi = retrofit.create(WorldApi::class.java)

    @Provides
    @Singleton
    fun provideAvatarApi(retrofit: Retrofit): AvatarApi = retrofit.create(AvatarApi::class.java)

    @Provides
    @Singleton
    fun provideInstanceApi(retrofit: Retrofit): InstanceApi = retrofit.create(InstanceApi::class.java)

    @Provides
    @Singleton
    fun provideNotificationApi(retrofit: Retrofit): NotificationApi = retrofit.create(NotificationApi::class.java)

    @Provides
    @Singleton
    fun provideFavoriteApi(retrofit: Retrofit): FavoriteApi = retrofit.create(FavoriteApi::class.java)

    @Provides
    @Singleton
    fun provideGroupApi(retrofit: Retrofit): GroupApi = retrofit.create(GroupApi::class.java)

    @Provides
    @Singleton
    fun providePlayerModerationApi(retrofit: Retrofit): PlayerModerationApi =
        retrofit.create(PlayerModerationApi::class.java)

    @Provides
    @Singleton
    fun provideGalleryApi(retrofit: Retrofit): GalleryApi = retrofit.create(GalleryApi::class.java)

    @Provides
    @Singleton
    fun provideInventoryApi(retrofit: Retrofit): InventoryApi = retrofit.create(InventoryApi::class.java)

    @Provides
    @Singleton
    fun provideInviteMessageApi(retrofit: Retrofit): InviteMessageApi = retrofit.create(InviteMessageApi::class.java)
}
