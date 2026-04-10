package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AvatarApi
import com.vrcx.android.data.api.GroupApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.WorldApi
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SearchRepositoryTest {
    private val userApi = mock<UserApi>()
    private val worldApi = mock<WorldApi>()
    private val avatarApi = mock<AvatarApi>()
    private val groupApi = mock<GroupApi>()
    private val okHttpClient = mock<OkHttpClient>()
    private val okHttpClientBuilder = mock<OkHttpClient.Builder>()
    private val remoteAvatarClient = mock<OkHttpClient>()

    private val repository = SearchRepository(
        userApi = userApi,
        worldApi = worldApi,
        avatarApi = avatarApi,
        groupApi = groupApi,
        okHttpClient = okHttpClient,
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `remote avatar provider failures surface as errors`() {
        runBlocking {
            val call = mock<Call>()
            whenever(okHttpClient.newBuilder()).thenReturn(okHttpClientBuilder)
            whenever(okHttpClientBuilder.cookieJar(any())).thenReturn(okHttpClientBuilder)
            whenever(okHttpClientBuilder.interceptors()).thenReturn(mutableListOf<Interceptor>())
            whenever(okHttpClientBuilder.networkInterceptors()).thenReturn(mutableListOf<Interceptor>())
            whenever(okHttpClientBuilder.build()).thenReturn(remoteAvatarClient)
            whenever(remoteAvatarClient.newCall(any())).thenReturn(call)
            whenever(call.execute()).thenReturn(
                Response.Builder()
                    .request(Request.Builder().url("https://example.com/provider").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Server Error")
                    .body("failure".toResponseBody())
                    .build()
            )

            val error = runCatching {
                repository.searchRemoteAvatars("test", "https://example.com/provider")
            }.exceptionOrNull()

            assertTrue(error is IOException)
            assertTrue(error?.message.orEmpty().contains("HTTP 500"))
        }
    }

    @Test
    fun `invalid remote avatar provider URLs fail fast`() {
        runBlocking {
            val error = runCatching {
                repository.searchRemoteAvatars("test", "not-a-url")
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertEquals("Enter a valid remote avatar provider URL.", error?.message)
        }
    }
}
