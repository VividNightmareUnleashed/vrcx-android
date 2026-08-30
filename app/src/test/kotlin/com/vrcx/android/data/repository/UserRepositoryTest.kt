package com.vrcx.android.data.repository

import com.vrcx.android.data.api.RequestDeduplicator
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.VrcUser
import com.vrcx.android.directTestDispatcher
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class UserRepositoryTest {
    @Test
    fun `an account change prevents a late user result from entering the new cache`() = runBlocking {
        val userApi = mock<UserApi>()
        val accountScope = AccountScope()
        val repository = UserRepository(userApi, RequestDeduplicator(directTestDispatcher), accountScope)
        val calls = AtomicInteger()
        val oldRequestStarted = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        whenever(userApi.getUser("usr_shared")).doSuspendableAnswer {
            if (calls.incrementAndGet() == 1) {
                oldRequestStarted.complete(Unit)
                releaseOldRequest.await()
                VrcUser(id = "usr_shared", displayName = "Old account value")
            } else {
                VrcUser(id = "usr_shared", displayName = "New account value")
            }
        }

        val oldLoad = async(start = CoroutineStart.UNDISPATCHED) {
            repository.getUser("usr_shared")
        }
        oldRequestStarted.await()
        accountScope.invalidate()
        releaseOldRequest.complete(Unit)
        assertEquals("Old account value", oldLoad.await().displayName)

        val current = repository.getUser("usr_shared")

        assertEquals("New account value", current.displayName)
        verify(userApi, times(2)).getUser("usr_shared")
        Unit
    }
}
