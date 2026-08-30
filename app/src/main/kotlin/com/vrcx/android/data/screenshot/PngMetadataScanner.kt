package com.vrcx.android.data.screenshot

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal data class PngMetadata(val resolution: String?, val textChunks: Map<String, List<String>>)

/** Reads only the bounded PNG structure needed by the screenshot metadata decoders. */
internal object PngMetadataScanner {
    private const val MAX_CHUNKS_TO_READ = 4096
    private const val MAX_CHUNK_BYTES = 64 * 1024 * 1024
    private const val MAX_TEXT_CHUNK_BYTES = 1024 * 1024
    private const val MAX_RETAINED_TEXT_BYTES = 4L * 1024 * 1024
    private const val MAX_IHDR_BYTES = 64
    private const val MAX_BYTES_AFTER_IDAT = 128L * 1024 * 1024

    private const val CHUNK_TYPE_BYTES = 4
    private const val CHUNK_CRC_BYTES = 4
    private const val CHUNK_OVERHEAD_BYTES = Int.SIZE_BYTES + CHUNK_TYPE_BYTES + CHUNK_CRC_BYTES
    private const val PNG_SIGNATURE_MARKER = 0x89
    private const val PNG_SUBSTITUTE_MARKER = 0x1A
    private const val IHDR_CHUNK = "IHDR"
    private const val IMAGE_DATA_CHUNK = "IDAT"
    private const val INTERNATIONAL_TEXT_CHUNK = "iTXt"
    private const val END_CHUNK = "IEND"

    private val pngSignature = byteArrayOf(
        PNG_SIGNATURE_MARKER.toByte(),
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        '\r'.code.toByte(),
        '\n'.code.toByte(),
        PNG_SUBSTITUTE_MARKER.toByte(),
        '\n'.code.toByte(),
    )

    fun read(inputStream: InputStream): PngMetadata? {
        val input = DataInputStream(inputStream.buffered())
        val signature = ByteArray(pngSignature.size).also(input::readFully)
        return if (signature.contentEquals(pngSignature)) scanChunks(input) else null
    }

    private fun scanChunks(input: DataInputStream): PngMetadata {
        val state = ScanState()
        var chunksRead = 0
        var scanNext = true
        while (chunksRead < MAX_CHUNKS_TO_READ && scanNext) {
            scanNext = input.readAndProcessChunk(state)
            chunksRead++
        }
        return state.toMetadata()
    }

    private fun DataInputStream.readAndProcessChunk(state: ScanState): Boolean {
        val header = readChunkHeader()
        return header != null && state.accepts(header) && consumeChunk(header, state)
    }

    private fun DataInputStream.readChunkHeader(): ChunkHeader? {
        val length = readInt()
        return if (length in 0..MAX_CHUNK_BYTES) {
            val typeBytes = ByteArray(CHUNK_TYPE_BYTES).also(::readFully)
            ChunkHeader(length, String(typeBytes, StandardCharsets.US_ASCII))
        } else {
            null
        }
    }

    private fun DataInputStream.consumeChunk(header: ChunkHeader, state: ScanState): Boolean {
        state.reserveTextBytes(header)
        val data = if (header.shouldRetainData) {
            ByteArray(header.length).also(::readFully)
        } else {
            skipPngData(header.length)
            null
        }
        skipPngCrc()
        state.collect(header.type, data)
        return header.type != END_CHUNK
    }

    private fun DataInputStream.skipPngCrc() {
        val crc = ByteArray(CHUNK_CRC_BYTES)
        readFully(crc)
    }

    private fun DataInputStream.skipPngData(length: Int) {
        var remaining = length
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (read() == -1) throw EOFException()
                remaining--
            }
        }
    }

    private fun readIhdrResolution(data: ByteArray): String? {
        if (data.size < Int.SIZE_BYTES * 2) return null
        val dimensions = ByteBuffer.wrap(data)
        return "${dimensions.int}x${dimensions.int}"
    }

    private data class ChunkHeader(val length: Int, val type: String) {
        val shouldRetainData: Boolean
            get() = when (type) {
                IHDR_CHUNK -> length <= MAX_IHDR_BYTES
                INTERNATIONAL_TEXT_CHUNK -> length <= MAX_TEXT_CHUNK_BYTES
                else -> false
            }

        val retainedTextBytes: Int
            get() = if (type == INTERNATIONAL_TEXT_CHUNK && length <= MAX_TEXT_CHUNK_BYTES) {
                length
            } else {
                0
            }
    }

    private class ScanState {
        private var resolution: String? = null
        private val textChunks = mutableMapOf<String, MutableList<String>>()
        private var reachedImageData = false
        private var bytesAfterImageData = 0L
        private var retainedTextBytes = 0L

        fun accepts(header: ChunkHeader): Boolean {
            if (header.type == IMAGE_DATA_CHUNK) reachedImageData = true
            if (reachedImageData) {
                bytesAfterImageData += header.length.toLong() + CHUNK_OVERHEAD_BYTES
            }
            return bytesAfterImageData <= MAX_BYTES_AFTER_IDAT
        }

        fun reserveTextBytes(header: ChunkHeader) {
            retainedTextBytes += header.retainedTextBytes
            require(retainedTextBytes <= MAX_RETAINED_TEXT_BYTES) {
                "PNG text metadata exceeds the supported limit"
            }
        }

        fun collect(type: String, data: ByteArray?) {
            when (type) {
                IHDR_CHUNK -> resolution = data?.let(::readIhdrResolution)

                INTERNATIONAL_TEXT_CHUNK -> data?.let(InternationalTextChunkParser::read)?.let { (keyword, text) ->
                    textChunks.getOrPut(keyword) { mutableListOf() }.add(text)
                }
            }
        }

        fun toMetadata() = PngMetadata(resolution, textChunks)
    }
}

private object InternationalTextChunkParser {
    private const val MAX_KEYWORD_BYTES = 79
    private const val HEADER_BYTES_AFTER_KEYWORD = 5
    private const val COMPRESSION_FIELDS_BYTES = 2
    private const val UNCOMPRESSED_TEXT_FLAG = 0

    fun read(data: ByteArray): Pair<String, String>? {
        val keywordEnd = data.indexOf(0)
        return keywordEnd
            .takeIf { it in 1..MAX_KEYWORD_BYTES }
            ?.takeIf { data.size >= it + HEADER_BYTES_AFTER_KEYWORD }
            ?.let { readUncompressed(data, it) }
    }

    private fun readUncompressed(data: ByteArray, keywordEnd: Int): Pair<String, String>? {
        val compressionFlagOffset = keywordEnd + 1
        return if (data[compressionFlagOffset].toInt() == UNCOMPRESSED_TEXT_FLAG) {
            readText(data, keywordEnd, compressionFlagOffset)
        } else {
            null
        }
    }

    private fun readText(data: ByteArray, keywordEnd: Int, compressionFlagOffset: Int): Pair<String, String>? {
        val textOffset = data.findTextOffset(compressionFlagOffset) ?: return null
        val keyword = String(data, 0, keywordEnd, StandardCharsets.UTF_8)
        val text = String(data, textOffset, data.size - textOffset, StandardCharsets.UTF_8)
        return keyword to text
    }

    private fun ByteArray.findTextOffset(compressionFlagOffset: Int): Int? {
        val languageStart = compressionFlagOffset + COMPRESSION_FIELDS_BYTES
        val languageEnd = indexOf(0, languageStart).takeIf { it >= 0 }
        val translatedKeywordEnd = languageEnd
            ?.let { indexOf(0, it + 1) }
            ?.takeIf { it >= 0 }
        return translatedKeywordEnd?.plus(1)
    }

    private fun ByteArray.indexOf(value: Int, startIndex: Int = 0): Int {
        for (index in startIndex until size) {
            if (this[index].toInt() == value) return index
        }
        return -1
    }
}
