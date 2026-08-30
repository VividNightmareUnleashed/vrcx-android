package com.vrcx.android.data.content

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import com.vrcx.android.data.repository.GalleryUploadFileNames
import com.vrcx.android.data.screenshot.ScreenshotMetadataReader
import com.vrcx.android.data.screenshot.ScreenshotReadResult
import com.vrcx.android.data.util.MAX_UPLOAD_SIZE_BYTES
import com.vrcx.android.data.util.UploadBytesResult
import com.vrcx.android.data.util.readUploadBytesBounded
import com.vrcx.android.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Longest edge an uploaded image is downsampled to before it is re-encoded. */
internal const val MAX_UPLOAD_DIMENSION = 2_000

private const val LOSSLESS_COMPRESSION_QUALITY = 100
private const val INITIAL_LOSSY_COMPRESSION_QUALITY = 92
private const val HIGH_LOSSY_COMPRESSION_QUALITY = 84
private const val MEDIUM_LOSSY_COMPRESSION_QUALITY = 76
private const val LOW_LOSSY_COMPRESSION_QUALITY = 68
private const val MINIMUM_LOSSY_COMPRESSION_QUALITY = 60

private val LOSSY_COMPRESSION_QUALITIES = listOf(
    INITIAL_LOSSY_COMPRESSION_QUALITY,
    HIGH_LOSSY_COMPRESSION_QUALITY,
    MEDIUM_LOSSY_COMPRESSION_QUALITY,
    LOW_LOSSY_COMPRESSION_QUALITY,
    MINIMUM_LOSSY_COMPRESSION_QUALITY,
)

data class ContentFileDetails(val fileName: String?, val fileSizeBytes: Long?)

data class InspectedScreenshot(val details: ContentFileDetails, val result: ScreenshotReadResult)

data class PreparedGalleryUpload(val bytes: ByteArray, val mimeType: String, val fileName: String)

sealed interface GalleryPreparationResult {
    data class Success(val upload: PreparedGalleryUpload) : GalleryPreparationResult
    data object TooLarge : GalleryPreparationResult
    data object Unreadable : GalleryPreparationResult
}

/** Owns Android content-provider reads and image decoding for picked images. */
@Singleton
class ContentImageService @Inject constructor(
    @ApplicationContext context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val contentResolver = context.contentResolver

    suspend fun inspectScreenshot(uri: Uri): InspectedScreenshot = withContext(ioDispatcher) {
        val details = contentResolver.queryFileDetailsOrEmpty(uri)
        val result = contentResolver.openInputStream(uri)?.use { input ->
            ScreenshotMetadataReader.read(input, details.fileName)
        } ?: ScreenshotReadResult.Failed("Unable to open the selected image.")
        InspectedScreenshot(details, result)
    }

    suspend fun prepareGalleryUpload(uri: Uri): GalleryPreparationResult = withContext(ioDispatcher) {
        val declaredMimeType = contentResolver.getType(uri)
        val details = contentResolver.queryFileDetailsOrEmpty(uri)
        val metadataSize = details.fileSizeBytes ?: contentResolver.resolveAssetSize(uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }
        val mimeType = GalleryImageProcessor.resolveMimeType(
            declared = declaredMimeType,
            detected = bounds.outMimeType,
            width = bounds.outWidth,
            height = bounds.outHeight,
        )
            ?: return@withContext GalleryPreparationResult.Unreadable
        val fileName = details.fileName?.takeIf { it.isNotBlank() }
            ?: GalleryUploadFileNames.defaultFor(mimeType)
        val needsResize =
            bounds.outWidth > MAX_UPLOAD_DIMENSION ||
                bounds.outHeight > MAX_UPLOAD_DIMENSION ||
                (metadataSize != null && metadataSize > MAX_UPLOAD_SIZE_BYTES)

        if (needsResize) {
            resizeUpload(uri, mimeType, fileName, bounds.outWidth, bounds.outHeight)
        } else {
            readBoundedUpload(metadataSize, mimeType, fileName) {
                contentResolver.openInputStream(uri)
            }
        }
    }

    private fun resizeUpload(
        uri: Uri,
        sourceMimeType: String,
        sourceFileName: String,
        width: Int,
        height: Int,
    ): GalleryPreparationResult {
        val options = BitmapFactory.Options().apply {
            inSampleSize = GalleryImageProcessor.calculateSampleSize(width, height)
        }
        val decoded = contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return GalleryPreparationResult.Unreadable

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
            scaled = GalleryImageProcessor.scaleToFit(decoded)
            GalleryImageProcessor.compressBounded(scaled, outputMimeType)
        } finally {
            scaled?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
        return bytes?.let {
            GalleryPreparationResult.Success(PreparedGalleryUpload(it, outputMimeType, outputName))
        } ?: GalleryPreparationResult.TooLarge
    }
}

/** Reads at most the upload ceiling even when a content provider under-reports size. */
internal fun readBoundedUpload(
    metadataSize: Long?,
    mimeType: String,
    fileName: String,
    openStream: () -> InputStream?,
): GalleryPreparationResult {
    if (metadataSize != null && metadataSize > MAX_UPLOAD_SIZE_BYTES) {
        return GalleryPreparationResult.TooLarge
    }
    return when (val read = readUploadBytesBounded(openStream())) {
        UploadBytesResult.Unreadable -> GalleryPreparationResult.Unreadable

        UploadBytesResult.TooLarge -> GalleryPreparationResult.TooLarge

        is UploadBytesResult.Success -> GalleryPreparationResult.Success(
            PreparedGalleryUpload(read.bytes, mimeType, fileName),
        )
    }
}

private fun ContentResolver.queryFileDetails(uri: Uri): ContentFileDetails {
    var fileName: String? = null
    var fileSizeBytes: Long? = null
    query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (cursor.moveToFirst()) {
            if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                fileSizeBytes = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
    }
    return ContentFileDetails(fileName, fileSizeBytes)
}

private fun ContentResolver.queryFileDetailsOrEmpty(uri: Uri): ContentFileDetails = runCatching {
    queryFileDetails(uri)
}
    .getOrDefault(ContentFileDetails(fileName = null, fileSizeBytes = null))

private fun ContentResolver.resolveAssetSize(uri: Uri): Long? = runCatching {
    openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        descriptor.length.takeIf { it >= 0 }
    }
}.getOrNull()

internal object GalleryImageProcessor {
    fun resolveMimeType(declared: String?, detected: String?, width: Int, height: Int): String? =
        normalizeMimeType(detected)
            ?.takeIf { width > 0 && height > 0 }
            ?.let { detectedMimeType ->
                normalizeMimeType(declared)
                    ?.takeIf { it == detectedMimeType }
                    ?: detectedMimeType
            }

    fun calculateSampleSize(width: Int, height: Int, maxDimension: Int = MAX_UPLOAD_DIMENSION): Int {
        var sampleSize = 1
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun fitDimensions(width: Int, height: Int, maxDimension: Int = MAX_UPLOAD_DIMENSION): Pair<Int, Int> {
        if (width <= maxDimension && height <= maxDimension) return width to height
        val scale = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    fun scaleToFit(bitmap: Bitmap): Bitmap {
        val (targetWidth, targetHeight) = fitDimensions(bitmap.width, bitmap.height)
        return if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
    }

    fun compressBounded(bitmap: Bitmap, mimeType: String): ByteArray? {
        val format = compressFormat(mimeType)
        val qualities = if (format == Bitmap.CompressFormat.PNG) {
            listOf(LOSSLESS_COMPRESSION_QUALITY)
        } else {
            LOSSY_COMPRESSION_QUALITIES
        }
        for (quality in qualities) {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(format, quality, output) && output.size() <= MAX_UPLOAD_SIZE_BYTES) {
                return output.toByteArray()
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    fun compressFormat(mimeType: String): Bitmap.CompressFormat = when (mimeType) {
        "image/jpeg" -> Bitmap.CompressFormat.JPEG

        "image/webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            webpLossyCompressFormat()
        } else {
            Bitmap.CompressFormat.WEBP
        }

        else -> Bitmap.CompressFormat.PNG
    }

    private fun normalizeMimeType(value: String?): String? = value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.startsWith("image/") && it.length > "image/".length }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun webpLossyCompressFormat(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP_LOSSY
}
