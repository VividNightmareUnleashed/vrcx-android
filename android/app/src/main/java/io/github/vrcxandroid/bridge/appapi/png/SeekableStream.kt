package io.github.vrcxandroid.bridge.appapi.png

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** The subset of .NET `FileStream` that the PNG code uses: positioned reads and writes, length changes. */
interface SeekableStream : Closeable {
    val length: Long
    var position: Long

    /** Reads up to [count] bytes at [position]; returns the number read (0 at the end). */
    fun read(buffer: ByteArray, offset: Int, count: Int): Int

    fun write(buffer: ByteArray, offset: Int, count: Int) {
        throw UnsupportedOperationException("stream is read-only")
    }

    fun setLength(value: Long) {
        throw UnsupportedOperationException("stream is read-only")
    }

    /** `Stream.ReadExactly`: throws [EOFException] when the stream ends first. */
    fun readExactly(buffer: ByteArray, offset: Int, count: Int) {
        var done = 0
        while (done < count) {
            val n = read(buffer, offset + done, count - done)
            if (n <= 0) throw EOFException("Unable to read beyond the end of the stream.")
            done += n
        }
    }
}

/** Growable in-memory stream (like `MemoryStream`), used to edit a PNG before writing it back in one go. */
class MemorySeekableStream(initial: ByteArray) : SeekableStream {
    private var data: ByteArray = initial.copyOf()
    private var size: Int = initial.size
    override val length: Long get() = size.toLong()
    override var position: Long = 0

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (position >= size) return 0
        val n = minOf(count.toLong(), size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        position += n
        return n
    }

    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        val end = position + count
        if (end > Int.MAX_VALUE) throw IllegalStateException("stream too large")
        ensureCapacity(end.toInt())
        if (position > size) data.fill(0, size, position.toInt())
        System.arraycopy(buffer, offset, data, position.toInt(), count)
        position = end
        if (end > size) size = end.toInt()
    }

    override fun setLength(value: Long) {
        val newSize = value.toInt()
        ensureCapacity(newSize)
        if (newSize > size) data.fill(0, size, newSize)
        size = newSize
        if (position > size) position = size.toLong()
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity > data.size) data = data.copyOf(maxOf(capacity, data.size * 2))
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    override fun close() {}
}

/** Read-only stream over a [FileChannel] (a local file or a content document's file descriptor). */
class ChannelSeekableStream(private val channel: FileChannel, private val onClose: () -> Unit = {}) : SeekableStream {
    override val length: Long get() = channel.size()
    override var position: Long = 0

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (count == 0) return 0
        val n = channel.read(ByteBuffer.wrap(buffer, offset, count), position)
        if (n <= 0) return 0
        position += n
        return n
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            onClose()
        }
    }

    companion object {
        fun open(file: File): ChannelSeekableStream {
            val raf = RandomAccessFile(file, "r")
            return ChannelSeekableStream(raf.channel) { raf.close() }
        }
    }
}
