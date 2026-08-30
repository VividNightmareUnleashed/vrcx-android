package com.vrcx.android.ui.screen.tools

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vrcx.android.data.content.ContentImageService
import com.vrcx.android.data.gallery.GalleryImageCategory
import com.vrcx.android.data.gallery.GalleryUploadCoordinator
import com.vrcx.android.data.gallery.GalleryUploadResult
import com.vrcx.android.data.repository.AuthRepository
import com.vrcx.android.data.repository.AuthState
import com.vrcx.android.data.screenshot.ScreenshotReadResult
import com.vrcx.android.data.util.runCatchingCancellable
import com.vrcx.android.ui.common.whileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScreenshotMetadataUiState(
    val selectedUri: Uri? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long? = null,
    val result: ScreenshotReadResult? = null,
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val uploadMessage: String? = null,
)

@HiltViewModel
class ScreenshotMetadataViewModel @Inject constructor(
    private val contentImageService: ContentImageService,
    private val uploadCoordinator: GalleryUploadCoordinator,
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScreenshotMetadataUiState())
    val uiState: StateFlow<ScreenshotMetadataUiState> = _uiState.asStateFlow()
    val isVrcPlusSupporter: StateFlow<Boolean> =
        authRepository.authState
            .map { state ->
                (state as? AuthState.LoggedIn)?.user?.tags?.contains("system_supporter") == true
            }.stateIn(viewModelScope, whileUiSubscribed, false)

    private var selectionGeneration = 0L
    private var uploadGeneration = 0L
    private var loadJob: Job? = null
    private var uploadJob: Job? = null

    fun loadScreenshot(uri: Uri) {
        uploadGeneration++
        loadJob?.cancel()
        uploadJob?.cancel()
        val generation = ++selectionGeneration
        _uiState.value = ScreenshotMetadataUiState(selectedUri = uri, isLoading = true)
        loadJob =
            viewModelScope.launch {
                runCatchingCancellable { contentImageService.inspectScreenshot(uri) }
                    .fold(
                        onSuccess = { inspected ->
                            updateIfCurrent(uri, generation) { current ->
                                current.copy(
                                    fileName = inspected.details.fileName,
                                    fileSizeBytes = inspected.details.fileSizeBytes,
                                    result = inspected.result,
                                    isLoading = false,
                                )
                            }
                        },
                        onFailure = {
                            updateIfCurrent(uri, generation) { current ->
                                current.copy(
                                    result =
                                        ScreenshotReadResult.Failed(
                                            "Unable to read the selected image.",
                                        ),
                                    isLoading = false,
                                )
                            }
                        },
                    )
            }
    }

    fun uploadSelectedScreenshotToGallery() {
        val uri = _uiState.value.selectedUri ?: return
        val generation = selectionGeneration
        val uploadAttempt = ++uploadGeneration
        uploadJob?.cancel()
        if (!isVrcPlusSupporter.value) {
            updateIfCurrent(uri, generation) {
                it.copy(
                    isUploading = false,
                    uploadMessage = "VRC+ is required to upload gallery images.",
                )
            }
            return
        }

        updateIfCurrent(uri, generation) { it.copy(isUploading = true, uploadMessage = null) }
        uploadJob =
            viewModelScope.launch {
                try {
                    val message =
                        runCatchingCancellable {
                            uploadCoordinator.uploadImage(
                                uri,
                                GalleryImageCategory.GALLERY,
                            ) {
                                isCurrentUpload(uri, generation, uploadAttempt)
                            }
                        }.fold(
                            onSuccess = ::uploadResultMessage,
                            onFailure = { failure ->
                                "Upload failed: ${failure.message ?: "Unknown error"}"
                            },
                        )
                    message?.let {
                        updateIfCurrentUpload(uri, generation, uploadAttempt) { current ->
                            current.copy(uploadMessage = it)
                        }
                    }
                } finally {
                    updateIfCurrentUpload(uri, generation, uploadAttempt) {
                        it.copy(isUploading = false)
                    }
                }
            }
    }

    private fun isCurrent(uri: Uri, generation: Long): Boolean =
        generation == selectionGeneration && _uiState.value.selectedUri == uri

    private fun isCurrentUpload(uri: Uri, generation: Long, uploadAttempt: Long): Boolean =
        uploadAttempt == uploadGeneration && isCurrent(uri, generation)

    private fun updateIfCurrentUpload(
        uri: Uri,
        generation: Long,
        uploadAttempt: Long,
        transform: (ScreenshotMetadataUiState) -> ScreenshotMetadataUiState,
    ) {
        if (uploadAttempt == uploadGeneration) {
            updateIfCurrent(uri, generation, transform)
        }
    }

    private fun updateIfCurrent(
        uri: Uri,
        generation: Long,
        transform: (ScreenshotMetadataUiState) -> ScreenshotMetadataUiState,
    ) {
        if (generation == selectionGeneration) {
            _uiState.update { current ->
                if (current.selectedUri == uri) transform(current) else current
            }
        }
    }
}

private fun uploadResultMessage(result: GalleryUploadResult): String? = when (result) {
    GalleryUploadResult.Uploaded -> "Image uploaded to gallery."

    GalleryUploadResult.TooLarge -> "Image too large (max 10 MB)."

    GalleryUploadResult.Unreadable -> "Unable to open the selected image."

    GalleryUploadResult.Obsolete -> null

    GalleryUploadResult.RefreshRequiresAuthentication ->
        "Image uploaded to gallery, but refresh requires signing in again."

    is GalleryUploadResult.RefreshFailed ->
        "Image uploaded to gallery, but refresh failed: " +
            (result.error.message ?: "Unknown error")
}
