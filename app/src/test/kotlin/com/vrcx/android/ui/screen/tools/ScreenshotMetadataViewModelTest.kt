package com.vrcx.android.ui.screen.tools

import android.net.Uri
import com.vrcx.android.data.api.model.CurrentUser
import com.vrcx.android.data.content.ContentFileDetails
import com.vrcx.android.data.content.ContentImageService
import com.vrcx.android.data.content.InspectedScreenshot
import com.vrcx.android.data.gallery.GalleryImageCategory
import com.vrcx.android.data.gallery.GalleryUploadCoordinator
import com.vrcx.android.data.gallery.GalleryUploadResult
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.screenshot.ScreenshotReadResult
import com.vrcx.android.ui.common.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotMetadataViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val testDispatcher = mainDispatcherRule.dispatcher

    @Test
    fun `load publishes content details without a Context parameter`() = runTest(testDispatcher) {
        val uri = mock<Uri>()
        val inspected = InspectedScreenshot(
            details = ContentFileDetails("VRChat.png", 123L),
            result = ScreenshotReadResult.NoMetadata("1920x1080"),
        )
        val content = mock<ContentImageService>()
        whenever(content.inspectScreenshot(uri)).thenReturn(inspected)
        val viewModel = buildViewModel(content = content)

        viewModel.loadScreenshot(uri)
        advanceUntilIdle()

        assertEquals(uri, viewModel.uiState.value.selectedUri)
        assertEquals("VRChat.png", viewModel.uiState.value.fileName)
        assertEquals(123L, viewModel.uiState.value.fileSizeBytes)
        assertEquals(inspected.result, viewModel.uiState.value.result)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `late read from an old selection cannot replace the current screenshot`() = runTest(testDispatcher) {
        val oldUri = mock<Uri>()
        val currentUri = mock<Uri>()
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val content = mock<ContentImageService>()
        whenever(content.inspectScreenshot(oldUri)).doSuspendableAnswer {
            oldStarted.complete(Unit)
            withContext(NonCancellable) { releaseOld.await() }
            InspectedScreenshot(
                ContentFileDetails("old.png", 1L),
                ScreenshotReadResult.NoMetadata("1x1"),
            )
        }
        whenever(content.inspectScreenshot(currentUri)).thenReturn(
            InspectedScreenshot(
                ContentFileDetails("current.png", 2L),
                ScreenshotReadResult.NoMetadata("2x2"),
            ),
        )
        val viewModel = buildViewModel(content = content)

        viewModel.loadScreenshot(oldUri)
        runCurrent()
        oldStarted.await()
        viewModel.loadScreenshot(currentUri)
        advanceUntilIdle()
        releaseOld.complete(Unit)
        advanceUntilIdle()

        assertEquals(currentUri, viewModel.uiState.value.selectedUri)
        assertEquals("current.png", viewModel.uiState.value.fileName)
        assertEquals(ScreenshotReadResult.NoMetadata("2x2"), viewModel.uiState.value.result)
    }

    @Test
    fun `supporter upload uses the shared coordinator and keeps its success message`() = runTest(testDispatcher) {
        val uri = mock<Uri>()
        val content = mock<ContentImageService>()
        whenever(content.inspectScreenshot(uri)).thenReturn(
            InspectedScreenshot(
                ContentFileDetails("VRChat.png", 123L),
                ScreenshotReadResult.NoMetadata("1920x1080"),
            ),
        )
        val coordinator = mock<GalleryUploadCoordinator>()
        whenever(coordinator.uploadImage(eq(uri), eq(GalleryImageCategory.GALLERY), any()))
            .thenReturn(GalleryUploadResult.Uploaded)
        val authState = MutableStateFlow<AuthState>(
            AuthState.LoggedIn(CurrentUser(tags = listOf("system_supporter"))),
        )
        val viewModel = buildViewModel(
            content = content,
            coordinator = coordinator,
            authState = authState,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.isVrcPlusSupporter.collect()
        }
        runCurrent()
        viewModel.loadScreenshot(uri)
        advanceUntilIdle()

        viewModel.uploadSelectedScreenshotToGallery()
        advanceUntilIdle()

        verify(coordinator).uploadImage(eq(uri), eq(GalleryImageCategory.GALLERY), any())
        assertEquals("Image uploaded to gallery.", viewModel.uiState.value.uploadMessage)
        assertFalse(viewModel.uiState.value.isUploading)
    }

    @Test
    fun `post-upload refresh failures do not report the upload as failed`() = runTest(testDispatcher) {
        val outcomes = listOf(
            GalleryUploadResult.RefreshRequiresAuthentication to
                "Image uploaded to gallery, but refresh requires signing in again.",
            GalleryUploadResult.RefreshFailed(RuntimeException("timeout")) to
                "Image uploaded to gallery, but refresh failed: timeout",
        )

        outcomes.forEach { (result, expectedMessage) ->
            val uri = mock<Uri>()
            val content = mock<ContentImageService>()
            whenever(content.inspectScreenshot(uri)).thenReturn(
                InspectedScreenshot(
                    ContentFileDetails("VRChat.png", 123L),
                    ScreenshotReadResult.NoMetadata("1920x1080"),
                ),
            )
            val coordinator = mock<GalleryUploadCoordinator>()
            whenever(coordinator.uploadImage(eq(uri), eq(GalleryImageCategory.GALLERY), any()))
                .thenReturn(result)
            val authState = MutableStateFlow<AuthState>(
                AuthState.LoggedIn(CurrentUser(tags = listOf("system_supporter"))),
            )
            val viewModel = buildViewModel(content, coordinator, authState)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.isVrcPlusSupporter.collect()
            }
            runCurrent()
            viewModel.loadScreenshot(uri)
            advanceUntilIdle()

            viewModel.uploadSelectedScreenshotToGallery()
            advanceUntilIdle()

            assertEquals(expectedMessage, viewModel.uiState.value.uploadMessage)
            assertFalse(viewModel.uiState.value.isUploading)
        }
    }

    @Test
    fun `non supporter is rejected before the coordinator`() = runTest(testDispatcher) {
        val uri = mock<Uri>()
        val content = mock<ContentImageService>()
        whenever(content.inspectScreenshot(uri)).thenReturn(
            InspectedScreenshot(
                ContentFileDetails("VRChat.png", 123L),
                ScreenshotReadResult.NoMetadata("1920x1080"),
            ),
        )
        val coordinator = mock<GalleryUploadCoordinator>()
        val viewModel = buildViewModel(content = content, coordinator = coordinator)
        viewModel.loadScreenshot(uri)
        advanceUntilIdle()

        viewModel.uploadSelectedScreenshotToGallery()
        advanceUntilIdle()

        assertEquals(
            "VRC+ is required to upload gallery images.",
            viewModel.uiState.value.uploadMessage,
        )
        assertFalse(viewModel.uiState.value.isUploading)
        verify(coordinator, never()).uploadImage(eq(uri), eq(GalleryImageCategory.GALLERY), any())
    }

    @Test
    fun `cancelled upload from an old selection cannot update the new screenshot`() = runTest(testDispatcher) {
        val oldUri = mock<Uri>()
        val currentUri = mock<Uri>()
        val content = mock<ContentImageService>()
        whenever(content.inspectScreenshot(oldUri)).thenReturn(
            InspectedScreenshot(
                ContentFileDetails("old.png", 1L),
                ScreenshotReadResult.NoMetadata("1x1"),
            ),
        )
        whenever(content.inspectScreenshot(currentUri)).thenReturn(
            InspectedScreenshot(
                ContentFileDetails("current.png", 2L),
                ScreenshotReadResult.NoMetadata("2x2"),
            ),
        )
        val uploadStarted = CompletableDeferred<Unit>()
        val releaseUpload = CompletableDeferred<Unit>()
        val coordinator = mock<GalleryUploadCoordinator>()
        whenever(coordinator.uploadImage(eq(oldUri), eq(GalleryImageCategory.GALLERY), any()))
            .doSuspendableAnswer { invocation ->
                val isStillRelevant = invocation.getArgument<() -> Boolean>(2)
                uploadStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseUpload.await()
                    if (isStillRelevant()) {
                        GalleryUploadResult.Uploaded
                    } else {
                        GalleryUploadResult.Obsolete
                    }
                }
            }
        val authState = MutableStateFlow<AuthState>(
            AuthState.LoggedIn(CurrentUser(tags = listOf("system_supporter"))),
        )
        val viewModel = buildViewModel(content, coordinator, authState)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.isVrcPlusSupporter.collect()
        }
        runCurrent()
        viewModel.loadScreenshot(oldUri)
        advanceUntilIdle()
        viewModel.uploadSelectedScreenshotToGallery()
        runCurrent()
        uploadStarted.await()

        viewModel.loadScreenshot(currentUri)
        advanceUntilIdle()
        releaseUpload.complete(Unit)
        advanceUntilIdle()

        assertEquals(currentUri, viewModel.uiState.value.selectedUri)
        assertEquals("current.png", viewModel.uiState.value.fileName)
        assertEquals(null, viewModel.uiState.value.uploadMessage)
        assertFalse(viewModel.uiState.value.isUploading)
    }

    @Test
    fun `cancelled retry cannot clear the active upload for the same screenshot`() = runTest(testDispatcher) {
        val uri = mock<Uri>()
        val content = mock<ContentImageService>()
        whenever(content.inspectScreenshot(uri)).thenReturn(
            InspectedScreenshot(
                ContentFileDetails("VRChat.png", 123L),
                ScreenshotReadResult.NoMetadata("1920x1080"),
            ),
        )
        val attempts = OverlappingUploadAttempts()
        val coordinator = mock<GalleryUploadCoordinator>()
        whenever(coordinator.uploadImage(eq(uri), eq(GalleryImageCategory.GALLERY), any()))
            .doSuspendableAnswer { invocation ->
                attempts.respond(invocation.getArgument(2))
            }
        val authState = MutableStateFlow<AuthState>(
            AuthState.LoggedIn(CurrentUser(tags = listOf("system_supporter"))),
        )
        val viewModel = buildViewModel(content, coordinator, authState)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.isVrcPlusSupporter.collect()
        }
        runCurrent()
        viewModel.loadScreenshot(uri)
        advanceUntilIdle()
        viewModel.uploadSelectedScreenshotToGallery()
        runCurrent()
        attempts.firstStarted.await()

        viewModel.uploadSelectedScreenshotToGallery()
        runCurrent()
        attempts.secondStarted.await()
        attempts.releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.uploadMessage)
        assertTrue(viewModel.uiState.value.isUploading)

        attempts.releaseSecond.complete(Unit)
        advanceUntilIdle()

        assertEquals("Image uploaded to gallery.", viewModel.uiState.value.uploadMessage)
        assertFalse(viewModel.uiState.value.isUploading)
    }

    private class OverlappingUploadAttempts {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        private var attempt = 0

        suspend fun respond(isStillRelevant: () -> Boolean): GalleryUploadResult {
            attempt++
            return if (attempt == 1) {
                firstStarted.complete(Unit)
                withContext(NonCancellable) {
                    releaseFirst.await()
                    if (isStillRelevant()) GalleryUploadResult.Uploaded else GalleryUploadResult.Obsolete
                }
            } else {
                secondStarted.complete(Unit)
                releaseSecond.await()
                GalleryUploadResult.Uploaded
            }
        }
    }

    private fun buildViewModel(
        content: ContentImageService = mock(),
        coordinator: GalleryUploadCoordinator = mock(),
        authState: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.NotLoggedIn),
    ): ScreenshotMetadataViewModel {
        val authRepository = mock<AuthRepository>()
        whenever(authRepository.authState).thenReturn(authState)
        return ScreenshotMetadataViewModel(content, coordinator, authRepository)
    }
}
