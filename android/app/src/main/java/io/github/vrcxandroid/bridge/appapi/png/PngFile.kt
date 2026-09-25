package io.github.vrcxandroid.bridge.appapi.png

import java.io.Closeable

/**
 * Port of upstream `PNGFile` (Dotnet/ScreenshotMetadata/PNGFile.cs) over a [SeekableStream]. Only the metadata chunks
 * before the first IDAT (at most 16) are read and cached; [writeChunk] and [deleteChunk] edit the stream in place.
 * The quirks are kept on purpose because they decide which bytes end up in the file:
 * - [writeChunk] always inserts after the last *cached* chunk and does not update the cache, so several writes land
 *   in reverse order;
 * - [getChunk] uses `filter.HasFlag(chunk)`, so an UNKNOWN chunk matches any filter.
 */
class PngFile(private val stream: SeekableStream) : Closeable {
    private val metadataChunkCache = mutableListOf<PngChunk>()

    fun getChunk(filter: PngChunkType): PngChunk? {
        readAndCacheMetadata()
        val chunk = metadataChunkCache.firstOrNull { filter.hasFlag(it.chunkTypeEnum) }
        if (chunk == null || chunk.isZero()) return null
        return chunk
    }

    fun getChunkReverse(filter: PngChunkType): PngChunk? {
        val chunk = readChunkReverse(filter)
        if (chunk == null || chunk.isZero()) return null
        return chunk
    }

    fun getChunks(): List<PngChunk> {
        readAndCacheMetadata()
        return metadataChunkCache.toList()
    }

    fun getChunksOfType(filter: PngChunkType): List<PngChunk> {
        readAndCacheMetadata()
        return metadataChunkCache.filter { it.chunkTypeEnum.hasFlag(filter) }
    }

    fun writeChunk(chunk: PngChunk): Boolean {
        readAndCacheMetadata()
        if (metadataChunkCache.isEmpty()) return false
        val lastChunk = metadataChunkCache.last()
        if (lastChunk.isZero()) return false

        val newChunkPosition = lastChunk.index + CHUNK_NONDATA_SIZE + lastChunk.length
        stream.position = newChunkPosition.toLong()
        val rest = ByteArray((stream.length - newChunkPosition).toInt())
        stream.readExactly(rest, 0, rest.size)
        stream.position = newChunkPosition.toLong()

        val chunkBytes = chunk.getBytes()
        stream.setLength(stream.length + CHUNK_NONDATA_SIZE + chunk.length)
        stream.write(chunkBytes, 0, chunkBytes.size)
        stream.write(rest, 0, rest.size)
        return true
    }

    fun deleteChunk(chunk: PngChunk): Boolean {
        if (!chunk.existsInFile(stream)) return false

        val bufferSize = 128 * 1024
        val deleteStart = chunk.index
        val deleteLength = chunk.length + CHUNK_NONDATA_SIZE
        var sourcePos = (deleteStart + deleteLength).toLong()
        var destPos = deleteStart.toLong()
        val buffer = ByteArray(bufferSize)
        while (sourcePos < stream.length) {
            stream.position = sourcePos
            val bytesRead = stream.read(buffer, 0, minOf(buffer.size.toLong(), stream.length - sourcePos).toInt())
            if (bytesRead == 0) break
            stream.position = destPos
            stream.write(buffer, 0, bytesRead)
            sourcePos += bytesRead
            destPos += bytesRead
        }
        stream.setLength(stream.length - deleteLength)

        metadataChunkCache.remove(chunk)
        for (cached in metadataChunkCache) {
            if (cached.index > deleteStart) cached.index -= deleteLength
        }
        return true
    }

    private fun readChunks(): List<PngChunk> {
        val result = mutableListOf<PngChunk>()
        var currentIndex = PNG_SIGNATURE.size.toLong()
        var chunksRead = 0
        val buffer = ByteArray(4)
        while (currentIndex < stream.length) {
            if (chunksRead >= MAX_CHUNKS_TO_READ) break
            chunksRead++
            stream.position = currentIndex
            stream.readExactly(buffer, 0, CHUNK_FIELD_SIZE)
            val chunkLength = PngChunk.readInt32BE(buffer, 0)
            if (chunkLength < 0 || chunkLength > stream.length - currentIndex - CHUNK_NONDATA_SIZE) break
            stream.readExactly(buffer, 0, CHUNK_FIELD_SIZE)
            val chunkType = String(buffer, 0, CHUNK_FIELD_SIZE, Charsets.US_ASCII)
            if (chunkType == "IDAT" || chunkType == "IEND") break
            val chunkData = ByteArray(chunkLength)
            stream.readExactly(chunkData, 0, chunkLength)
            result += PngChunk(
                index = currentIndex.toInt(),
                length = chunkLength,
                chunkType = chunkType,
                chunkTypeEnum = PngChunkType.fromName(chunkType),
                data = chunkData,
            )
            currentIndex += CHUNK_NONDATA_SIZE + chunkLength
        }
        return result
    }

    private fun readChunkReverse(filter: PngChunkType): PngChunk? {
        if (stream.length < 8300) return null
        val search = chunkTypeName(filter)?.toByteArray(Charsets.US_ASCII) ?: return null

        stream.position = stream.length - 8192 - CHUNK_NONDATA_SIZE
        val trailing = ByteArray(8192)
        stream.readExactly(trailing, 0, 8192)

        for (i in 0 until trailing.size - search.size) {
            if (trailing[i] == search[0] && trailing[i + 1] == search[1] &&
                trailing[i + 2] == search[2] && trailing[i + 3] == search[3]
            ) {
                stream.position = stream.length - trailing.size - CHUNK_NONDATA_SIZE + i - CHUNK_FIELD_SIZE
                val buffer = ByteArray(4)
                stream.readExactly(buffer, 0, CHUNK_FIELD_SIZE)
                val chunkLength = PngChunk.readInt32BE(buffer, 0)
                if (chunkLength < 0 || chunkLength > stream.length - i - CHUNK_NONDATA_SIZE) return null
                stream.readExactly(buffer, 0, CHUNK_FIELD_SIZE)
                val chunkType = String(buffer, 0, CHUNK_FIELD_SIZE, Charsets.US_ASCII)
                val chunkData = ByteArray(chunkLength)
                stream.readExactly(chunkData, 0, chunkLength)
                stream.position = stream.position + CHUNK_FIELD_SIZE
                return PngChunk(
                    length = chunkLength,
                    chunkType = chunkType,
                    chunkTypeEnum = PngChunkType.fromName(chunkType),
                    data = chunkData,
                )
            }
        }
        return null
    }

    private fun readAndCacheMetadata() {
        if (metadataChunkCache.isNotEmpty()) return
        if (!isValid()) return
        metadataChunkCache.addAll(readChunks())
    }

    /**
     * At least 57 bytes and the PNG signature. Like upstream it reads the signature from the *current* position (the
     * start of the stream on first use).
     */
    fun isValid(): Boolean {
        if (stream.length < 57) return false
        val signature = ByteArray(8)
        if (stream.read(signature, 0, 8) < 8) return false
        return signature.contentEquals(PNG_SIGNATURE)
    }

    override fun close() = stream.close()

    companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private const val MAX_CHUNKS_TO_READ = 16
        private const val CHUNK_FIELD_SIZE = 4
        private const val CHUNK_NONDATA_SIZE = 12

        private fun chunkTypeName(type: PngChunkType): String? = when (type) {
            PngChunkType.IHDR -> "IHDR"
            PngChunkType.sRGB -> "sRGB"
            PngChunkType.iTXt -> "iTXt"
            PngChunkType.IDAT -> "IDAT"
            PngChunkType.IEND -> "IEND"
            PngChunkType.UNKNOWN -> null
        }
    }
}
