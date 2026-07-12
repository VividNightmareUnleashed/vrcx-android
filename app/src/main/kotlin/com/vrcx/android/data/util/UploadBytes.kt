package com.vrcx.android.data.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** VRChat's gallery upload ceiling: 10 MB (decimal, matching the API contract). */
const val MAX_UPLOAD_SIZE_BYTES = 10_000_000

private const val UPLOAD_READ_BUFFER_SIZE = 8 * 1024

/** Outcome of [readUploadBytesBounded]. */
sealed interface UploadBytesResult {
    data class Success(val bytes: ByteArray) : UploadBytesResult
    data object TooLarge : UploadBytesResult
    data object Unreadable : UploadBytesResult
}

/**
 * Reads [input] fully into memory while refusing anything larger than
 * [maxBytes] — the size is bounded *as* it reads, so an oversized file is
 * never buffered in full. The stream is closed on the way out; a null stream
 * is [UploadBytesResult.Unreadable].
 */
fun readUploadBytesBounded(
    input: InputStream?,
    maxBytes: Int = MAX_UPLOAD_SIZE_BYTES,
): UploadBytesResult {
    if (input == null) return UploadBytesResult.Unreadable
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(UPLOAD_READ_BUFFER_SIZE)
    var total = 0

    input.use {
        while (true) {
            val maxReadable = maxBytes + 1 - total
            if (maxReadable <= 0) return UploadBytesResult.TooLarge

            val read = it.read(buffer, 0, minOf(buffer.size, maxReadable))
            if (read == -1) break

            total += read
            if (total > maxBytes) return UploadBytesResult.TooLarge
            output.write(buffer, 0, read)
        }
    }

    return UploadBytesResult.Success(output.toByteArray())
}
