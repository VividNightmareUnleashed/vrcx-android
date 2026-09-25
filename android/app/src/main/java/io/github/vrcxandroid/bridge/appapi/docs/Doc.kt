package io.github.vrcxandroid.bridge.appapi.docs

import io.github.vrcxandroid.bridge.appapi.png.ChannelSeekableStream
import io.github.vrcxandroid.bridge.appapi.png.SeekableStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

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

    /**
     * Creation time in epoch milliseconds, kept when [writeBytes] replaces the content; falls back to [lastModified]
     * where the storage has none (SAF documents, and local files on Linux, whose "creation time" is the mtime).
     */
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

    override fun creationTime(): Long = creationFileTime()?.toMillis() ?: file.lastModified()

    private fun creationFileTime(): FileTime? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).creationTime()
    } catch (e: Exception) {
        null
    }

    override fun openRead(): SeekableStream {
        if (!file.isFile) throw FileNotFoundException("Could not find file '${file.absolutePath}'.")
        return ChannelSeekableStream.open(file)
    }

    override fun readBytes(): ByteArray {
        if (!file.isFile) throw FileNotFoundException("Could not find file '${file.absolutePath}'.")
        return file.readBytes()
    }

    /**
     * Writes a temporary file and renames it over the original. The replacement is a new file, so the original
     * creation time is put back where the file system keeps one (upstream edits in place and keeps it).
     */
    override fun writeBytes(bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory")
        val created = creationFileTime()
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
        if (created != null) {
            try {
                // only the creation time; Unix file systems ignore it
                Files.getFileAttributeView(file.toPath(), BasicFileAttributeView::class.java).setTimes(null, null, created)
            } catch (e: Exception) {
                // the content is written; a lost creation time only affects ordering
            }
        }
    }

    override fun siblings(): List<Doc>? =
        file.absoluteFile.parentFile?.listFiles()?.filter { it.isFile }?.map { LocalFileDoc(it) }
}
