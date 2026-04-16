package com.vrcx.android.di

import android.content.Context
import com.vrcx.android.data.api.AuthApi
import com.vrcx.android.data.api.AuthEventBus
import com.vrcx.android.data.api.AuthInterceptor
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.AvatarModerationApi
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
import com.vrcx.android.data.api.UserAgentInterceptor
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.WorldApi
import com.vrcx.android.data.security.SecureSecretsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val BASE_URL = "https://api.vrchat.cloud/api/1/"

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
    fun provideCookieJar(
        @ApplicationContext context: Context,
        secureSecretsStore: SecureSecretsStore,
    ): CookieJarImpl {
        return CookieJarImpl(context, secureSecretsStore)
    }

    @Provides
    @Singleton
    fun provideAuthInterceptor(): AuthInterceptor = AuthInterceptor()

    @Provides
    @Singleton
    fun provideRequestDeduplicator(): RequestDeduplicator = RequestDeduplicator()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        cookieJar: CookieJarImpl,
        authInterceptor: AuthInterceptor,
        authEventBus: AuthEventBus,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(UserAgentInterceptor())
            .addInterceptor(authInterceptor)
            .addInterceptor(ErrorInterceptor(authEventBus))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        json: Json,
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json; charset=utf-8".toMediaType()))
            .build()
    }

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
    fun providePlayerModerationApi(retrofit: Retrofit): PlayerModerationApi = retrofit.create(PlayerModerationApi::class.java)

    @Provides
    @Singleton
    fun provideGalleryApi(retrofit: Retrofit): GalleryApi = retrofit.create(GalleryApi::class.java)

    @Provides
    @Singleton
    fun provideInventoryApi(retrofit: Retrofit): InventoryApi = retrofit.create(InventoryApi::class.java)

    @Provides
    @Singleton
    fun provideInviteMessageApi(retrofit: Retrofit): InviteMessageApi = retrofit.create(InviteMessageApi::class.java)

    @Provides
    @Singleton
    fun provideAvatarModerationApi(retrofit: Retrofit): AvatarModerationApi = retrofit.create(AvatarModerationApi::class.java)
}
