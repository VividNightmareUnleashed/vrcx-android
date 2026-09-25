package io.github.vrcxandroid.bridge.appapi

/**
 * Unkeyed BLAKE2b (RFC 7693) with a configurable digest length (1..64 bytes). Used by [RsyncSignature], which needs
 * BLAKE2b with digest_size = 32 exactly as librsync.net computes its strong sums.
 */
class Blake2b(private val digestLength: Int = 64) {
    private val h = LongArray(8)
    private val buffer = ByteArray(BLOCK)
    private var bufferLength = 0
    private var counterLow = 0L
    private var counterHigh = 0L
    private val m = LongArray(16)
    private val v = LongArray(16)

    init {
        require(digestLength in 1..64) { "digest length must be 1..64" }
        reset()
    }

    fun reset() {
        IV.copyInto(h)
        h[0] = h[0] xor (0x01010000L or digestLength.toLong())
        bufferLength = 0
        counterLow = 0
        counterHigh = 0
    }

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size) {
        var off = offset
        var len = length
        while (len > 0) {
            // Keep the last block in the buffer: it must be compressed with the final flag.
            if (bufferLength == BLOCK) {
                increment(BLOCK)
                compress(buffer, 0, false)
                bufferLength = 0
            }
            val n = minOf(BLOCK - bufferLength, len)
            System.arraycopy(input, off, buffer, bufferLength, n)
            bufferLength += n
            off += n
            len -= n
        }
    }

    fun digest(): ByteArray {
        increment(bufferLength)
        buffer.fill(0, bufferLength, BLOCK)
        compress(buffer, 0, true)
        val out = ByteArray(digestLength)
        for (i in 0 until digestLength) {
            out[i] = (h[i ushr 3] ushr (8 * (i and 7))).toByte()
        }
        reset()
        return out
    }

    private fun increment(n: Int) {
        val before = counterLow
        counterLow += n
        if (java.lang.Long.compareUnsigned(counterLow, before) < 0) counterHigh++
    }

    private fun compress(block: ByteArray, offset: Int, last: Boolean) {
        for (i in 0 until 16) {
            var w = 0L
            for (b in 7 downTo 0) {
                w = (w shl 8) or (block[offset + i * 8 + b].toLong() and 0xFF)
            }
            m[i] = w
        }
        for (i in 0 until 8) {
            v[i] = h[i]
            v[i + 8] = IV[i]
        }
        v[12] = v[12] xor counterLow
        v[13] = v[13] xor counterHigh
        if (last) v[14] = v[14].inv()
        for (r in 0 until 12) {
            val s = SIGMA[r % 10]
            g(0, 4, 8, 12, m[s[0]], m[s[1]])
            g(1, 5, 9, 13, m[s[2]], m[s[3]])
            g(2, 6, 10, 14, m[s[4]], m[s[5]])
            g(3, 7, 11, 15, m[s[6]], m[s[7]])
            g(0, 5, 10, 15, m[s[8]], m[s[9]])
            g(1, 6, 11, 12, m[s[10]], m[s[11]])
            g(2, 7, 8, 13, m[s[12]], m[s[13]])
            g(3, 4, 9, 14, m[s[14]], m[s[15]])
        }
        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
    }

    private fun g(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
        v[a] = v[a] + v[b] + x
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
        v[a] = v[a] + v[b] + y
        v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
    }

    companion object {
        private const val BLOCK = 128

        private val IV = longArrayOf(
            0x6a09e667f3bcc908uL.toLong(), 0xbb67ae8584caa73buL.toLong(),
            0x3c6ef372fe94f82buL.toLong(), 0xa54ff53a5f1d36f1uL.toLong(),
            0x510e527fade682d1uL.toLong(), 0x9b05688c2b3e6c1fuL.toLong(),
            0x1f83d9abfb41bd6buL.toLong(), 0x5be0cd19137e2179uL.toLong(),
        )

        private val SIGMA = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
        )

        fun hash(data: ByteArray, digestLength: Int = 64, offset: Int = 0, length: Int = data.size): ByteArray =
            Blake2b(digestLength).apply { update(data, offset, length) }.digest()
    }
}
