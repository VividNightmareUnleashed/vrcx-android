package com.vrcx.android.data.repository

import com.vrcx.android.data.api.AccountBoundCookies
import com.vrcx.android.data.api.CookieJarImpl
import com.vrcx.android.data.api.GalleryApi
import com.vrcx.android.data.api.InventoryApi
import com.vrcx.android.data.api.UserApi
import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryResponse
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryRepositoryTest {

    @Test
    fun `late gallery result cannot repopulate state after account clear`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val accountScope = AccountScope()
        val repository = GalleryRepository(
            galleryApi,
            mock(),
            mock(),
            mock(),
            accountScope,
            AuthenticatedSessionGate(accountScope),
        )
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        whenever(galleryApi.getFileList(tag = "gallery")).doSuspendableAnswer {
            requestStarted.complete(Unit)
            releaseRequest.await()
            listOf(GalleryImage(id = "file_old_account"))
        }

        val load = async(start = CoroutineStart.UNDISPATCHED) { repository.loadGallery() }
        requestStarted.await()
        accountScope.invalidate()
        releaseRequest.complete(Unit)
        load.await()

        assertTrue(repository.galleryImages.value.isEmpty())
    }

    @Test
    fun `image lists page past the first hundred results`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val accountScope = AccountScope()
        val repository = GalleryRepository(
            galleryApi,
            mock(),
            mock(),
            mock(),
            accountScope,
            AuthenticatedSessionGate(accountScope),
        )
        whenever(galleryApi.getFileList(n = 100, offset = 0, tag = "gallery"))
            .thenReturn(List(100) { GalleryImage(id = "file_$it") })
        whenever(galleryApi.getFileList(n = 100, offset = 100, tag = "gallery"))
            .thenReturn(listOf(GalleryImage(id = "file_100")))

        repository.loadGallery()

        val images = repository.galleryImages.value
        assertEquals(101, images.size)
        // The list is reversed as a whole, so the newest page leads.
        assertEquals("file_100", images.first().id)
        verify(galleryApi).getFileList(n = 100, offset = 100, tag = "gallery")
    }

    @Test
    fun `prints page past the first hundred results`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val accountScope = AccountScope()
        val repository = GalleryRepository(
            galleryApi,
            mock(),
            mock(),
            mock(),
            accountScope,
            AuthenticatedSessionGate(accountScope),
        )
        whenever(galleryApi.getPrints("usr_1", n = 100, offset = 0))
            .thenReturn(List(100) { VrcPrint(id = "prnt_$it") })
        whenever(galleryApi.getPrints("usr_1", n = 100, offset = 100))
            .thenReturn(listOf(VrcPrint(id = "prnt_100")))

        repository.loadPrints("usr_1")

        assertEquals(101, repository.prints.value.size)
        verify(galleryApi).getPrints("usr_1", n = 100, offset = 100)
    }

    @Test
    fun `content refresh accepts both print aliases and keeps loader failures to itself`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val accountScope = AccountScope()
        accountScope.bind("usr_1")
        val repository = GalleryRepository(
            galleryApi,
            mock(),
            mock(),
            mock(),
            accountScope,
            AuthenticatedSessionGate(accountScope),
        )
        whenever(galleryApi.getPrints("usr_1", n = 100, offset = 0))
            .thenReturn(listOf(VrcPrint(id = "prnt_1")))
        whenever(galleryApi.getFileList(n = 100, offset = 0, tag = "emoji"))
            .thenThrow(RuntimeException("emoji unavailable"))

        repository.handleContentRefresh("print", "usr_1")
        assertEquals(listOf("prnt_1"), repository.prints.value.map { it.id })

        accountScope.invalidate()
        accountScope.bind("usr_1")
        repository.handleContentRefresh("prints", "usr_1")
        assertEquals(listOf("prnt_1"), repository.prints.value.map { it.id })

        // A pipeline-driven refresh must not surface an error to a screen that
        // did not ask for one, while the screen's own loader still does.
        repository.handleContentRefresh("emoji", "usr_1")
        assertTrue(runCatching { repository.loadEmojis() }.isFailure)
    }

    @Test
    fun `recovery refreshes only requested datasets and supersedes an older load`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val inventoryApi = mock<InventoryApi>()
        val accountScope = AccountScope().apply { bind("usr_1") }
        val repository = GalleryRepository(
            galleryApi,
            inventoryApi,
            mock(),
            mock(),
            accountScope,
            AuthenticatedSessionGate(accountScope),
        )
        val oldRequestStarted = CompletableDeferred<Unit>()
        val releaseOldRequest = CompletableDeferred<Unit>()
        var requestCount = 0
        whenever(galleryApi.getFileList(n = 100, offset = 0, tag = "gallery"))
            .doSuspendableAnswer {
                requestCount++
                if (requestCount == 1) {
                    oldRequestStarted.complete(Unit)
                    releaseOldRequest.await()
                    listOf(GalleryImage(id = "file_old"))
                } else {
                    listOf(GalleryImage(id = "file_recovered"))
                }
            }

        val oldLoad = async(start = CoroutineStart.UNDISPATCHED) { repository.loadGallery() }
        oldRequestStarted.await()
        repository.resynchronize(accountScope.current())
        releaseOldRequest.complete(Unit)
        oldLoad.await()

        assertEquals(listOf("file_recovered"), repository.galleryImages.value.map { it.id })
        verify(galleryApi, times(2)).getFileList(n = 100, offset = 0, tag = "gallery")
        verify(galleryApi, never()).getFileList(n = 100, offset = 0, tag = "icon")
        verify(galleryApi, never()).getPrints(any(), any(), any())
        verify(inventoryApi, never()).getInventoryItems(any(), any(), any())
    }

    @Test
    fun `live content refresh cannot be overwritten by an older recovery load`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val accountScope = AccountScope().apply { bind("usr_1") }
        val repository = GalleryRepository(
            galleryApi,
            mock(),
            mock(),
            mock(),
            accountScope,
            AuthenticatedSessionGate(accountScope),
        )
        val recoveryStarted = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        var requestCount = 0
        whenever(galleryApi.getFileList(n = 100, offset = 0, tag = "gallery"))
            .doSuspendableAnswer {
                when (++requestCount) {
                    1 -> listOf(GalleryImage(id = "file_initial"))

                    2 -> {
                        recoveryStarted.complete(Unit)
                        releaseRecovery.await()
                        listOf(GalleryImage(id = "file_recovery"))
                    }

                    else -> listOf(GalleryImage(id = "file_live"))
                }
            }

        repository.loadGallery()
        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            repository.resynchronize(accountScope.current())
        }
        recoveryStarted.await()

        repository.handleContentRefresh("gallery", "usr_1", accountScope.current())
        assertEquals(listOf("file_live"), repository.galleryImages.value.map { it.id })

        releaseRecovery.complete(Unit)
        recovery.await()

        assertEquals(listOf("file_live"), repository.galleryImages.value.map { it.id })
    }

    @Test
    fun `an account change mid-load aborts with an account change signal`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val inventoryApi = mock<InventoryApi>()
        val accountScope = AccountScope()
        val repository = GalleryRepository(
            galleryApi,
            inventoryApi,
            mock(),
            mock(),
            accountScope,
            AuthenticatedSessionGate(accountScope),
        )
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        whenever(inventoryApi.getInventoryItems(n = 100, offset = 0, order = "newest"))
            .doSuspendableAnswer {
                requestStarted.complete(Unit)
                releaseRequest.await()
                InventoryResponse(data = listOf(InventoryItem(id = "inv_first")), totalCount = 2)
            }

        val load = async(start = CoroutineStart.UNDISPATCHED) { repository.loadInventory() }
        requestStarted.await()
        accountScope.invalidate()
        releaseRequest.complete(Unit)
        val error = runCatching { load.await() }.exceptionOrNull()

        assertTrue(error is AccountChangedException)
    }

    @Test
    fun `loadInventory paginates wrapped results and resolves each template once`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val inventoryApi = mock<InventoryApi>()
        val userApi = mock<UserApi>()
        val accountScope = AccountScope()
        val repository = GalleryRepository(
            galleryApi,
            inventoryApi,
            userApi,
            mock(),
            accountScope,
            AuthenticatedSessionGate(accountScope),
        )
        val first = InventoryItem(id = "inv_first", templateId = "invt_shared")
        val second = InventoryItem(id = "inv_second", templateId = "invt_shared")
        val template = InventoryTemplate(id = "invt_shared", name = "Shared template")

        whenever(inventoryApi.getInventoryItems(n = 100, offset = 0, order = "newest"))
            .thenReturn(InventoryResponse(data = listOf(first), totalCount = 2))
        whenever(inventoryApi.getInventoryItems(n = 100, offset = 1, order = "newest"))
            .thenReturn(InventoryResponse(data = listOf(second), totalCount = 2))
        whenever(inventoryApi.getInventoryTemplate("invt_shared")).thenReturn(template)

        repository.loadInventory()

        assertEquals(listOf(first, second), repository.inventoryItems.value)
        assertEquals(listOf(template), repository.inventoryTemplates.value)
        verify(inventoryApi).getInventoryItems(n = 100, offset = 1, order = "newest")
        verify(inventoryApi).getInventoryTemplate("invt_shared")
    }

    @Test
    fun `auth transition that wins the snapshot gate aborts the upload`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val cookieJar = mock<CookieJarImpl>()
        val accountScope = AccountScope().apply { bind("usr_old") }
        val token = accountScope.current()
        val gate = AuthenticatedSessionGate(accountScope)
        val repository = GalleryRepository(
            galleryApi,
            mock(),
            mock(),
            cookieJar,
            accountScope,
            gate,
        )
        val transitionStarted = CompletableDeferred<Unit>()
        val releaseTransition = CompletableDeferred<Unit>()
        val transition = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.transition {
                transitionStarted.complete(Unit)
                releaseTransition.await()
                accountScope.invalidate()
                accountScope.bind("usr_new")
            }
        }
        transitionStarted.await()

        val upload = async {
            runCatching {
                repository.uploadFile("gallery", byteArrayOf(), "image/png", "image.png", token)
            }
        }
        runCurrent()

        assertFalse(upload.isCompleted)
        releaseTransition.complete(Unit)
        transition.join()
        assertTrue(upload.await().exceptionOrNull() is AccountChangedException)
        verify(cookieJar, never()).snapshotForAccountBoundRequest()
        verify(galleryApi, never()).uploadFile(any(), any(), any())
    }

    @Test
    fun `auth transition can continue after upload cookies are captured`() = runTest {
        val galleryApi = mock<GalleryApi>()
        val cookieJar = mock<CookieJarImpl>()
        val accountCookies = mock<AccountBoundCookies>()
        val accountScope = AccountScope().apply { bind("usr_old") }
        val gate = AuthenticatedSessionGate(accountScope)
        val repository = GalleryRepository(
            galleryApi,
            mock(),
            mock(),
            cookieJar,
            accountScope,
            gate,
        )
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        whenever(cookieJar.snapshotForAccountBoundRequest()).thenReturn(accountCookies)
        whenever(galleryApi.uploadFile(any(), any(), any())).doSuspendableAnswer {
            uploadStarted.complete(Unit)
            releaseUpload.await()
            GalleryImage()
        }

        val upload = async {
            repository.uploadFile("gallery", byteArrayOf(), "image/png", "image.png")
        }
        uploadStarted.await()
        val transition = launch {
            gate.transition {
                accountScope.invalidate()
                accountScope.bind("usr_new")
            }
        }
        runCurrent()

        assertTrue(transition.isCompleted)
        releaseUpload.complete(Unit)
        upload.await()
        verify(galleryApi).uploadFile(eq(accountCookies), any(), any())
    }

    @Test
    fun `defaultFileNameFor maps supported and fallback MIME types`() {
        listOf(
            "image/jpeg" to "image.jpg",
            "image/jpg" to "image.jpg",
            "Image/JPEG" to "image.jpg",
            "image/webp" to "image.webp",
            "image/gif" to "image.gif",
            "image/png" to "image.png",
            "application/octet-stream" to "image.png",
            "" to "image.png",
        ).forEach { (mimeType, expected) ->
            assertEquals(expected, GalleryUploadFileNames.defaultFor(mimeType))
        }
    }
}
