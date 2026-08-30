package com.vrcx.android.ui.screen.gallery

internal sealed interface GalleryConfirmation {
    data class DeleteFile(val fileId: String, val tab: GalleryTab) : GalleryConfirmation

    data class DeletePrint(val printId: String) : GalleryConfirmation

    data class ConsumeBundle(val itemId: String) : GalleryConfirmation
}

internal sealed interface GalleryAction {
    sealed interface Route : GalleryAction

    sealed interface Menu : GalleryAction

    sealed interface Content : GalleryAction

    sealed interface Confirmation : GalleryAction

    data object NavigateBack : Route

    data object Retry : Route

    data object Refresh : Route

    data object UploadRequested : Route

    data class SelectTab(val tab: GalleryTab) : Route

    data object OpenOverflowMenu : Menu

    data object DismissOverflowMenu : Menu

    data object ClearProfilePicture : Menu

    data object ClearUserIcon : Menu

    data object DismissFullscreen : Content

    data class ShowFullscreen(val imageUrl: String) : Content

    data class SetProfilePicture(val fileId: String) : Content

    data class SetUserIcon(val fileId: String) : Content

    data object DismissConfirmation : Confirmation

    data object ConfirmRequested : Confirmation

    data class DeleteFileRequested(val fileId: String, val tab: GalleryTab) : Confirmation

    data class DeletePrintRequested(val printId: String) : Confirmation

    data class ConsumeBundleRequested(val itemId: String) : Confirmation
}

internal data class GalleryActionEnvironment(
    val launchUpload: () -> Unit,
    val isUploading: () -> Boolean,
    val confirmation: () -> GalleryConfirmation?,
    val updateConfirmation: (GalleryConfirmation?) -> Unit,
    val updateOverflowMenu: (Boolean) -> Unit,
)

internal class GalleryActionHandler(
    private val viewModel: GalleryViewModel,
    private val onBack: () -> Unit,
    private val environment: GalleryActionEnvironment,
) {
    fun handle(action: GalleryAction) {
        when (action) {
            is GalleryAction.Route -> handleRoute(action)
            is GalleryAction.Menu -> handleMenu(action)
            is GalleryAction.Content -> handleContent(action)
            is GalleryAction.Confirmation -> handleConfirmation(action)
        }
    }

    private fun handleRoute(action: GalleryAction.Route) {
        when (action) {
            GalleryAction.NavigateBack -> onBack()

            GalleryAction.Retry,
            GalleryAction.Refresh,
            -> viewModel.handle(GalleryCommand.Refresh)

            GalleryAction.UploadRequested -> if (!environment.isUploading()) environment.launchUpload()

            is GalleryAction.SelectTab -> viewModel.handle(GalleryCommand.SelectTab(action.tab))
        }
    }

    private fun handleMenu(action: GalleryAction.Menu) {
        when (action) {
            GalleryAction.OpenOverflowMenu -> environment.updateOverflowMenu(true)

            GalleryAction.DismissOverflowMenu -> environment.updateOverflowMenu(false)

            GalleryAction.ClearProfilePicture -> {
                environment.updateOverflowMenu(false)
                viewModel.handle(GalleryCommand.ClearProfilePicture)
            }

            GalleryAction.ClearUserIcon -> {
                environment.updateOverflowMenu(false)
                viewModel.handle(GalleryCommand.ClearUserIcon)
            }
        }
    }

    private fun handleContent(action: GalleryAction.Content) {
        when (action) {
            GalleryAction.DismissFullscreen -> viewModel.handle(GalleryCommand.DismissFullscreen)

            is GalleryAction.ShowFullscreen ->
                viewModel.handle(GalleryCommand.ShowFullscreen(action.imageUrl))

            is GalleryAction.SetProfilePicture ->
                viewModel.handle(GalleryCommand.SetProfilePicture(action.fileId))

            is GalleryAction.SetUserIcon ->
                viewModel.handle(GalleryCommand.SetUserIcon(action.fileId))
        }
    }

    private fun handleConfirmation(action: GalleryAction.Confirmation) {
        when (action) {
            GalleryAction.DismissConfirmation -> environment.updateConfirmation(null)

            GalleryAction.ConfirmRequested -> confirmSelection()

            is GalleryAction.DeleteFileRequested -> {
                environment.updateConfirmation(GalleryConfirmation.DeleteFile(action.fileId, action.tab))
            }

            is GalleryAction.DeletePrintRequested -> {
                environment.updateConfirmation(GalleryConfirmation.DeletePrint(action.printId))
            }

            is GalleryAction.ConsumeBundleRequested -> {
                environment.updateConfirmation(GalleryConfirmation.ConsumeBundle(action.itemId))
            }
        }
    }

    private fun confirmSelection() {
        when (val target = environment.confirmation()) {
            is GalleryConfirmation.DeleteFile ->
                viewModel.handle(GalleryCommand.DeleteFile(target.fileId, target.tab))

            is GalleryConfirmation.DeletePrint ->
                viewModel.handle(GalleryCommand.DeletePrint(target.printId))

            is GalleryConfirmation.ConsumeBundle ->
                viewModel.handle(GalleryCommand.ConsumeBundle(target.itemId))

            null -> Unit
        }
        environment.updateConfirmation(null)
    }
}
