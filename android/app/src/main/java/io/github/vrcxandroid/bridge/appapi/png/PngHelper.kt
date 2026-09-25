package io.github.vrcxandroid.bridge.appapi.png

import java.io.ByteArrayOutputStream

/** Port of upstream `PNGHelper` (Dotnet/ScreenshotMetadata/PNGHelper.cs). */
object PngHelper {
    fun readResolution(png: PngFile): String {
        val ihdr = png.getChunk(PngChunkType.IHDR) ?: return "0x0"
        val (w, h) = ihdr.readIHDRChunkResolution()
        return "${w}x$h"
    }

    /**
     * Text of the first iTXt chunk with [keyword]. With [legacySearch] only the chunk found by the reverse search of the
     * last 8 KB is considered (files written by old VRChat mods). Chunks whose keyword cannot be read are skipped
     * (upstream would throw a NullReferenceException on them).
     */
    fun readTextChunk(keyword: String, png: PngFile, legacySearch: Boolean = false): String? {
        if (legacySearch) {
            val legacy = png.getChunkReverse(PngChunkType.iTXt) ?: return null
            val data = legacy.readITXtChunk() ?: return null
            return if (data.first == keyword) data.second else null
        }
        for (chunk in png.getChunksOfType(PngChunkType.iTXt)) {
            val data = chunk.readITXtChunk() ?: continue
            if (data.first == keyword) return data.second
        }
        return null
    }

    fun deleteTextChunk(keyword: String, png: PngFile): Boolean {
        for (chunk in png.getChunksOfType(PngChunkType.iTXt)) {
            val data = chunk.readITXtChunk() ?: continue
            if (data.first == keyword) return png.deleteChunk(chunk)
        }
        return false
    }

    /** An iTXt chunk with empty compression flag/method, language tag and translated keyword. */
    fun generateTextChunk(keyword: String, text: String): PngChunk {
        val out = ByteArrayOutputStream()
        out.write(keyword.toByteArray(Charsets.UTF_8))
        out.write(byteArrayOf(0, 0, 0, 0, 0))
        out.write(text.toByteArray(Charsets.UTF_8))
        val bytes = out.toByteArray()
        return PngChunk(chunkType = "iTXt", chunkTypeEnum = PngChunkType.iTXt, data = bytes, length = bytes.size)
    }

    /**
     * The iTXt-copy step of upstream `CropPrintImage`: every iTXt chunk of [source] is written into [target] with
     * [PngFile.writeChunk] (so they end up in reverse order right after the target's last metadata chunk). Returns
     * the new target bytes.
     */
    fun copyITxtChunks(source: ByteArray, target: ByteArray): ByteArray {
        val chunks = PngFile(MemorySeekableStream(source)).use { it.getChunksOfType(PngChunkType.iTXt) }
        val out = MemorySeekableStream(target)
        val png = PngFile(out)
        for (chunk in chunks) png.writeChunk(chunk)
        return out.toByteArray()
    }
}
