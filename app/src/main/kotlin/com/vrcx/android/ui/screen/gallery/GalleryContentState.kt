package com.vrcx.android.ui.screen.gallery

import com.vrcx.android.data.api.model.GalleryImage
import com.vrcx.android.data.api.model.InventoryItem
import com.vrcx.android.data.api.model.InventoryTemplate
import com.vrcx.android.data.api.model.VrcPrint
import com.vrcx.android.ui.common.LoadState

internal data class GalleryContentState(
    val uiState: GalleryUiState,
    val isUploading: Boolean,
    val isOverflowMenuExpanded: Boolean,
    val galleryImages: List<GalleryImage>,
    val iconImages: List<GalleryImage>,
    val emojiImages: List<GalleryImage>,
    val stickerImages: List<GalleryImage>,
    val prints: List<VrcPrint>,
    val inventoryItems: List<InventoryItem>,
    val inventoryTemplates: List<InventoryTemplate>,
) {
    val selectedTab: GalleryTab get() = uiState.selectedTab
    val selectedTabState: LoadState<Unit> get() = uiState.selectedTabState

    fun itemCount(tab: GalleryTab): Int = when (tab) {
        GalleryTab.GALLERY -> galleryImages.size
        GalleryTab.ICONS -> iconImages.size
        GalleryTab.EMOJIS -> emojiImages.size
        GalleryTab.STICKERS -> stickerImages.size
        GalleryTab.PRINTS -> prints.size
        GalleryTab.INVENTORY -> inventoryItems.size
    }
}
