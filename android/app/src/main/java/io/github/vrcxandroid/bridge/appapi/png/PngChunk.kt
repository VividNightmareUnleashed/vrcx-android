package io.github.vrcxandroid.bridge.appapi.png

/**
 * Port of upstream `PNGChunkTypeFilter` (Dotnet/ScreenshotMetadata/PNGChunkTypeFilter.cs). The enum is not a
 * `[Flags]` enum upstream, but the code calls `HasFlag` on it; [hasFlag] reproduces that bitwise test.
 */
enum class PngChunkType(val value: Int) {
    UNKNOWN(0), IHDR(1), sRGB(2), iTXt(3), IDAT(4), IEND(5);

    /** .NET `Enum.HasFlag`: `(this & flag) == flag`. */
    fun hasFlag(flag: PngChunkType): Boolean = (value and flag.value) == flag.value

    companion object {
        fun fromName(name: String): PngChunkType = when (name) {
            "IHDR" -> IHDR
            "sRGB" -> sRGB
            "iTXt" -> iTXt
            "IDAT" -> IDAT
            "IEND" -> IEND
            else -> UNKNOWN
        }
    }
}

/** Port of upstream `PNGChunk` (Dotnet/ScreenshotMetadata/PNGChunk.cs). */
class PngChunk(
    var index: Int = 0,
    var length: Int = 0,
    var chunkType: String = "",
    var chunkTypeEnum: PngChunkType = PngChunkType.UNKNOWN,
    var data: ByteArray = ByteArray(0),
) {
    fun isZero(): Boolean = data.isEmpty()

    /**
     * Keyword and text of an iTXt chunk. Like upstream it skips the compression flag, compression method, language tag
     * and translated keyword as if they were empty (text starts at keyword length + 5). Returns null when the keyword
     * is empty or longer than 79 bytes.
     */
    fun readITXtChunk(): Pair<String, String>? {
        if (isZero()) throw IllegalStateException("Tried to read from invalid PNG chunk")
        if (chunkTypeEnum != PngChunkType.iTXt) throw IllegalStateException("Cannot read text from chunk type $chunkType")
        val chunkLength = length
        var keywordLength = 0
        for (i in 0 until chunkLength) {
            if (data[i].toInt() == 0) {
                keywordLength = i
                break
            }
        }
        if (keywordLength == 0 || keywordLength > 79 || chunkLength < keywordLength) return null
        val keyword = String(data, 0, keywordLength, Charsets.UTF_8)
        val textOffset = keywordLength + 5
        val textLength = chunkLength - textOffset
        if (textLength < 0) throw IndexOutOfBoundsException("Index and count must refer to a location within the buffer.")
        return keyword to String(data, textOffset, textLength, Charsets.UTF_8)
    }

    /**
     * Width and height from an IHDR chunk. Upstream reverses the first 8 data bytes in place to read them; this port
     * reads big-endian without mutating (upstream only ever reads each chunk once).
     */
    fun readIHDRChunkResolution(): Pair<Int, Int> {
        if (isZero()) throw IllegalStateException("Tried to read from invalid PNG chunk")
        if (chunkTypeEnum != PngChunkType.IHDR) throw IllegalStateException("Cannot read text from chunk type $chunkType")
        if (data.size < 8) throw IndexOutOfBoundsException("Destination array is not long enough")
        return readInt32BE(data, 0) to readInt32BE(data, 4)
    }

    /** Checks the length and CRC of this chunk at [index] in [stream]. */
    fun existsInFile(stream: SeekableStream): Boolean {
        stream.position = index.toLong()
        val buffer = ByteArray(4)
        stream.readExactly(buffer, 0, 4)
        val chunkLength = readInt32BE(buffer, 0)
        if (chunkLength != length) return false
        stream.position = stream.position + 4 + chunkLength
        stream.readExactly(buffer, 0, 4)
        val crc = readInt32BE(buffer, 0).toLong() and 0xFFFFFFFFL
        return crc == calculateCrc()
    }

    /** Length, type, data and CRC, ready to be inserted into a PNG file. */
    fun getBytes(): ByteArray {
        val typeBytes = chunkType.toByteArray(Charsets.US_ASCII)
        val total = data.size + 12
        val result = ByteArray(total)
        writeInt32BE(result, 0, data.size)
        System.arraycopy(typeBytes, 0, result, 4, typeBytes.size)
        System.arraycopy(data, 0, result, 8, data.size)
        writeInt32BE(result, total - 4, calculateCrc().toInt())
        return result
    }

    fun calculateCrc(): Long {
        val typeBytes = chunkType.toByteArray(Charsets.UTF_8)
        return crc32(data, 0, data.size, crc32(typeBytes, 0, typeBytes.size, 0))
    }

    companion object {
        private val crcTable: LongArray by lazy {
            LongArray(256) { n ->
                var c = n.toLong()
                repeat(8) {
                    c = if (c and 1L == 1L) 0xEDB88320L xor ((c ushr 1) and 0x7FFFFFFFL) else (c ushr 1) and 0x7FFFFFFFL
                }
                c
            }
        }

        fun crc32(bytes: ByteArray, offset: Int, length: Int, crc: Long): Long {
            var c = crc xor 0xFFFFFFFFL
            for (i in offset until offset + length) {
                c = crcTable[((c xor bytes[i].toLong()) and 255).toInt()] xor ((c ushr 8) and 0xFFFFFFL)
            }
            return c xor 0xFFFFFFFFL
        }

        fun readInt32BE(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

        fun writeInt32BE(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte()
            b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte()
            b[off + 3] = v.toByte()
        }
    }
}
