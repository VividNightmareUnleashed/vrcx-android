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
fun readUploadBytesBounded(input: InputStream?, maxBytes: Int = MAX_UPLOAD_SIZE_BYTES): UploadBytesResult =
    if (input == null) {
        UploadBytesResult.Unreadable
    } else {
        input.use { readUploadBytes(it, maxBytes) }
    }

private fun readUploadBytes(input: InputStream, maxBytes: Int): UploadBytesResult {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(UPLOAD_READ_BUFFER_SIZE)
    var total = 0L
    var complete = false
    var tooLarge = maxBytes < 0

    while (!complete && !tooLarge) {
        val remaining = maxBytes.toLong() - total
        val bytesToRequest = minOf(buffer.size.toLong(), remaining + 1L).toInt()
        val read = input.read(buffer, 0, bytesToRequest)
        if (read == -1) {
            complete = true
        } else {
            total += read
            tooLarge = total > maxBytes
            if (!tooLarge) output.write(buffer, 0, read)
        }
    }

    return if (tooLarge) {
        UploadBytesResult.TooLarge
    } else {
        UploadBytesResult.Success(output.toByteArray())
    }
}
