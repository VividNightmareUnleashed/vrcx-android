package io.github.vrcxandroid.bridge.appapi.docs

import io.github.vrcxandroid.bridge.appapi.png.ChannelSeekableStream
import io.github.vrcxandroid.bridge.appapi.png.SeekableStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A file the AppApi file methods work on: a local file, a SAF document or a MediaStore item. Upstream works on paths;
 * on Android a path string is resolved to a [Doc] by [DocResolver].
 */
interface Doc {
    /** Identity: an absolute path or a `content://` URI. */
    val key: String

    /** Display name including the extension (`VRChat_...png`). */
    val name: String

    fun exists(): Boolean
    fun length(): Long

    /** Last modification time in epoch milliseconds (0 when unknown). */
    fun lastModified(): Long

    /** Creation time in epoch milliseconds; falls back to [lastModified] where the storage has none. */
    fun creationTime(): Long = lastModified()

    /** Seekable read-only view of the content. */
    fun openRead(): SeekableStream

    fun readBytes(): ByteArray

    /** Replaces the whole content (atomically where the storage allows it). */
    fun writeBytes(bytes: ByteArray)

    /** Other entries of the same folder (files only, unsorted), or null when the folder cannot be listed. */
    fun siblings(): List<Doc>?

    /** The backing local file, when there is one. */
    val file: File? get() = null
}

/** A plain file on the local file system. */
class LocalFileDoc(override val file: File) : Doc {
    override val key: String get() = file.absolutePath
    override val name: String get() = file.name

    override fun exists(): Boolean = file.isFile
    override fun length(): Long = file.length()
    override fun lastModified(): Long = file.lastModified()

    override fun creationTime(): Long = try {
        java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            .creationTime().toMillis()
    } catch (e: Exception) {
        file.lastModified()
    }

    override fun openRead(): SeekableStream {
        if (!file.isFile) throw FileNotFoundException("Could not find file '${file.absolutePath}'.")
        return ChannelSeekableStream.open(file)
    }

    override fun readBytes(): ByteArray {
        if (!file.isFile) throw FileNotFoundException("Could not find file '${file.absolutePath}'.")
        return file.readBytes()
    }

    override fun writeBytes(bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory")
        val temp = File(dir, file.name + ".temp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            // renameTo does not replace on every file system
            file.delete()
            if (!temp.renameTo(file)) {
                temp.delete()
                throw IOException("Could not replace '${file.absolutePath}'.")
            }
        }
    }

    override fun siblings(): List<Doc>? =
        file.absoluteFile.parentFile?.listFiles()?.filter { it.isFile }?.map { LocalFileDoc(it) }
}
