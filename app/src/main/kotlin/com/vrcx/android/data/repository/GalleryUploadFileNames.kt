package com.vrcx.android.data.repository

internal object GalleryUploadFileNames {
    fun defaultFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "image.jpg"
        "image/webp" -> "image.webp"
        "image/gif" -> "image.gif"
        else -> "image.png"
    }
}
