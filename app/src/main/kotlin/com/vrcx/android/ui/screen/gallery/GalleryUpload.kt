package com.vrcx.android.ui.screen.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.vrcx.android.data.repository.GalleryRepository
import com.vrcx.android.data.util.MAX_UPLOAD_SIZE_BYTES
import com.vrcx.android.data.util.UploadBytesResult
import com.vrcx.android.data.util.readUploadBytesBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Longest edge a gallery upload is downsampled to before it is re-encoded. */
internal const val MAX_UPLOAD_DIMENSION = 2_000

internal data class PreparedUpload(val bytes: ByteArray, val mimeType: String, val fileName: String)

internal sealed interface UploadReadResult {
    data class Success(val upload: PreparedUpload) : UploadReadResult
    data object TooLarge : UploadReadResult
    data object Unreadable : UploadReadResult
}

/**
 * Turns a picked image into the bytes VRChat's gallery accepts.
 *
 * Every upload entry point goes through here, so "put this image in my gallery"
 * means the same thing from the Gallery screen and from the Screenshot Metadata
 * tool: an image over the dimension or the size ceiling is downsampled and
 * re-encoded, and only one that still cannot be squeezed under the ceiling is
 * rejected as too large.
 */
internal suspend fun prepareGalleryUpload(context: Context, uri: Uri): UploadReadResult =
    withContext(Dispatchers.IO) {
        val mimeType = context.contentResolver.getType(uri) ?: "image/png"
        val fileName = resolveUploadFileName(context, uri, mimeType)
        val metadataSize = resolveUploadFileSize(context, uri)

        // decodeStream always returns null under inJustDecodeBounds — the call is
        // here so `bounds` comes back carrying the image's dimensions.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }
        val hasDimensions = bounds.outWidth > 0 && bounds.outHeight > 0
        val needsResize = hasDimensions && (
            bounds.outWidth > MAX_UPLOAD_DIMENSION ||
                bounds.outHeight > MAX_UPLOAD_DIMENSION ||
                (metadataSize != null && metadataSize > MAX_UPLOAD_SIZE_BYTES)
            )

        if (needsResize) {
            resizeUpload(context, uri, mimeType, fileName, bounds.outWidth, bounds.outHeight)
        } else {
            readBoundedUpload(metadataSize, mimeType, fileName) {
                context.contentResolver.openInputStream(uri)
            }
        }
    }

/**
 * The size check that guards the read: a file the provider already reports as
 * oversized is rejected before a stream is opened, and the read that does
 * happen is bounded, so an oversized pick is never buffered in full.
 */
internal fun readBoundedUpload(
    metadataSize: Long?,
    mimeType: String,
    fileName: String,
    openStream: () -> InputStream?,
): UploadReadResult {
    if (metadataSize != null && metadataSize > MAX_UPLOAD_SIZE_BYTES) return UploadReadResult.TooLarge
    return when (val read = readUploadBytesBounded(openStream())) {
        UploadBytesResult.Unreadable -> UploadReadResult.Unreadable
        UploadBytesResult.TooLarge -> UploadReadResult.TooLarge
        is UploadBytesResult.Success ->
            UploadReadResult.Success(PreparedUpload(read.bytes, mimeType, fileName))
    }
}

/**
 * Pulls the original file name from the URI's OpenableColumns when the source
 * provides it (the gallery picker does), so the multipart upload reports the
 * user's actual file name. Falls back to a MIME-appropriate default name so the
 * extension matches the bytes regardless.
 */
private fun resolveUploadFileName(context: Context, uri: Uri, mimeType: String): String {
    val pickerName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()
    return pickerName?.takeIf { it.isNotBlank() }
        ?: GalleryRepository.defaultFileNameFor(mimeType)
}

private fun resolveUploadFileSize(context: Context, uri: Uri): Long? {
    val querySize = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) cursor.getLong(idx).takeIf { it >= 0 } else null
            } else null
        }
    }.getOrNull()

    if (querySize != null) return querySize

    return runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }
        }
    }.getOrNull()
}

private fun resizeUpload(
    context: Context,
    uri: Uri,
    sourceMimeType: String,
    sourceFileName: String,
    width: Int,
    height: Int,
): UploadReadResult {
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateUploadSampleSize(width, height)
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: return UploadReadResult.Unreadable

    val outputMimeType = when (sourceMimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "image/jpeg"
        "image/webp" -> "image/webp"
        else -> "image/png"
    }
    val extension = when (outputMimeType) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }
    val outputName = sourceFileName.substringBeforeLast('.', sourceFileName) + ".$extension"

    var scaled: Bitmap? = null
    val bytes = try {
        scaled = scaleBitmapToFit(decoded)
        compressBitmapBounded(scaled, outputMimeType)
    } finally {
        scaled?.takeIf { it !== decoded }?.recycle()
        decoded.recycle()
    }
    return bytes?.let {
        UploadReadResult.Success(PreparedUpload(it, outputMimeType, outputName))
    } ?: UploadReadResult.TooLarge
}

private fun scaleBitmapToFit(bitmap: Bitmap): Bitmap {
    val (targetWidth, targetHeight) = fitUploadDimensions(bitmap.width, bitmap.height)
    return if (targetWidth == bitmap.width && targetHeight == bitmap.height) bitmap else {
        Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}

private fun compressBitmapBounded(bitmap: Bitmap, mimeType: String): ByteArray? {
    val format = when (mimeType) {
        "image/jpeg" -> Bitmap.CompressFormat.JPEG
        "image/webp" -> Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.PNG
    }
    val qualities = if (format == Bitmap.CompressFormat.PNG) listOf(100) else listOf(92, 84, 76, 68, 60)
    for (quality in qualities) {
        val output = ByteArrayOutputStream()
        if (bitmap.compress(format, quality, output) && output.size() <= MAX_UPLOAD_SIZE_BYTES) {
            return output.toByteArray()
        }
    }
    return null
}

/**
 * Smallest power-of-two subsample that puts the decoded bitmap at or below
 * [maxDimension] on both edges, so the decode itself never allocates a bitmap
 * larger than the upload needs.
 */
internal fun calculateUploadSampleSize(
    width: Int,
    height: Int,
    maxDimension: Int = MAX_UPLOAD_DIMENSION,
): Int {
    var sampleSize = 1
    while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun fitUploadDimensions(
    width: Int,
    height: Int,
    maxDimension: Int = MAX_UPLOAD_DIMENSION,
): Pair<Int, Int> {
    if (width <= maxDimension && height <= maxDimension) return width to height
    val scale = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}
