package com.vrcx.android.data.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePicCacheInterceptorTest {
    @Test
    fun `VRChat hosts take the cache branch`() {
        assertTrue(ProfilePicCacheInterceptor.isVrchatUrl("https://api.vrchat.cloud/api/1/file/file_a/1/file"))
        assertTrue(ProfilePicCacheInterceptor.isVrchatUrl("https://vrchat.cloud/avatar.png"))
    }

    @Test
    fun `a foreign host carrying the VRChat host elsewhere in the URL does not`() {
        assertFalse(
            ProfilePicCacheInterceptor.isVrchatUrl(
                "https://images.example.com/?src=https://api.vrchat.cloud/api/1/file/file_a/1/file",
            ),
        )
        assertFalse(ProfilePicCacheInterceptor.isVrchatUrl("https://notvrchat.cloud/avatar.png"))
    }

    @Test
    fun `a non-URL data value does not`() {
        assertFalse(ProfilePicCacheInterceptor.isVrchatUrl("content://media/external/images/1"))
    }
}
