package com.vrcx.android.ui.screen.gallery

import android.net.Uri

internal sealed interface GalleryCommand {
    sealed interface State : GalleryCommand

    sealed interface Mutation : GalleryCommand

    sealed interface Upload : GalleryCommand

    data object Refresh : State

    data class SelectTab(val tab: GalleryTab) : State

    data object ClearSnackbar : State

    data class ShowFullscreen(val imageUrl: String) : State

    data object DismissFullscreen : State

    data class DeleteFile(val fileId: String, val tab: GalleryTab) : Mutation

    data class DeletePrint(val printId: String) : Mutation

    data class UploadFile(val uri: Uri, val tab: GalleryTab) : Upload

    data class UploadPrint(val uri: Uri, val note: String?) : Upload

    data class SetProfilePicture(val fileId: String) : Mutation

    data object ClearProfilePicture : Mutation

    data class SetUserIcon(val fileId: String) : Mutation

    data object ClearUserIcon : Mutation

    data class ConsumeBundle(val itemId: String) : Mutation
}
