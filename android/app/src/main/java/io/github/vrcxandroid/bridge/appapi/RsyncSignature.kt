package io.github.vrcxandroid.bridge.appapi

import java.io.ByteArrayOutputStream

/**
 * librsync signature as produced by librsync.net `Librsync.ComputeSignature(stream)` with its defaults
 * (upstream Dotnet/AppApi/Common/ImageSaving.cs SignFile):
 *
 * - header, big-endian uint32: magic 0x72730137 (RS_BLAKE2_SIG_MAGIC), block length 2048, strong-sum length 32;
 * - per block (the last one may be shorter; empty input gives the header only): the weak rolling checksum as a
 *   big-endian uint32, then BLAKE2b with digest size 32 of the block.
 *
 * Rolling checksum per block: s1 = Σ(b + 31), s2 = Σ s1 (after each byte), both mod 2^16, weak = (s2 << 16) | s1.
 */
object RsyncSignature {
    const val MAGIC_BLAKE2 = 0x72730137
    const val BLOCK_LENGTH = 2048
    const val STRONG_SUM_LENGTH = 32
    private const val CHAR_OFFSET = 31

    fun compute(data: ByteArray): ByteArray {
        val blocks = (data.size + BLOCK_LENGTH - 1) / BLOCK_LENGTH
        val out = ByteArrayOutputStream(12 + blocks * (4 + STRONG_SUM_LENGTH))
        out.writeInt(MAGIC_BLAKE2)
        out.writeInt(BLOCK_LENGTH)
        out.writeInt(STRONG_SUM_LENGTH)
        val blake = Blake2b(STRONG_SUM_LENGTH)
        var offset = 0
        while (offset < data.size) {
            val length = minOf(BLOCK_LENGTH, data.size - offset)
            out.writeInt(weakSum(data, offset, length))
            blake.update(data, offset, length)
            out.write(blake.digest())
            offset += length
        }
        return out.toByteArray()
    }

    fun weakSum(data: ByteArray, offset: Int, length: Int): Int {
        var s1 = 0
        var s2 = 0
        for (i in offset until offset + length) {
            s1 += (data[i].toInt() and 0xFF) + CHAR_OFFSET
            s2 += s1
        }
        return ((s2 and 0xFFFF) shl 16) or (s1 and 0xFFFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }
}
