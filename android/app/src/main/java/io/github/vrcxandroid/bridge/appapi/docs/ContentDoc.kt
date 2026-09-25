package io.github.vrcxandroid.bridge.appapi.docs

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import io.github.vrcxandroid.bridge.appapi.png.ChannelSeekableStream
import io.github.vrcxandroid.bridge.appapi.png.MemorySeekableStream
import io.github.vrcxandroid.bridge.appapi.png.SeekableStream
import java.io.FileNotFoundException

/**
 * Name, size, modification time and creation time (MediaStore `DATE_ADDED`, which rewrites keep; 0 when the provider
 * has none, as for SAF documents) of a content document, times in epoch milliseconds.
 */
class DocInfo(val name: String, val size: Long, val lastModified: Long, val created: Long = 0)

/**
 * A SAF document (possibly inside a granted tree) or a MediaStore item. Metadata is read lazily with one query and
 * refreshed after a write. [parentDocumentId] is known when the document was found by listing a tree.
 */
class ContentDoc(
    private val context: Context,
    val uri: Uri,
    private var info: DocInfo? = null,
    private val parentDocumentId: String? = null,
) : Doc {
    private val resolver: ContentResolver get() = context.contentResolver
    private var queried = info != null

    override val key: String get() = uri.toString()
    override val name: String get() = info()?.name ?: uri.lastPathSegment.orEmpty()

    override fun exists(): Boolean = info() != null
    override fun length(): Long = info()?.size ?: 0
    override fun lastModified(): Long = info()?.lastModified ?: 0
    override fun creationTime(): Long = info()?.created?.takeIf { it > 0 } ?: lastModified()

    private fun info(): DocInfo? {
        if (!queried) {
            info = query(context, uri)
            queried = true
        }
        return info
    }

    override fun openRead(): SeekableStream {
        val pfd = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (pfd != null) {
            // Pipes and sockets (statSize -1) are not seekable; fall back to reading everything.
            if (pfd.statSize >= 0) {
                // AutoCloseInputStream owns the descriptor, so it is closed exactly once
                val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                return ChannelSeekableStream(input.channel) { input.close() }
            }
            pfd.close()
        }
        return MemorySeekableStream(readBytes())
    }

    override fun readBytes(): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException("Could not find file '$uri'.")

    override fun writeBytes(bytes: ByteArray) {
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(uri, "rwt")
        } catch (e: FileNotFoundException) {
            resolver.openOutputStream(uri, "rwt")
        } ?: throw FileNotFoundException("Could not open '$uri' for writing.")
        stream.use { it.write(bytes) }
        queried = false
    }

    override fun siblings(): List<Doc>? = try {
        when {
            isTreeDocument(uri) -> treeSiblings()
            uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= 29 -> mediaSiblings()
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun treeSiblings(): List<Doc>? {
        val parentId = parentDocumentId ?: run {
            val path = DocumentsContract.findDocumentPath(resolver, uri)?.path ?: return null
            if (path.size < 2) return null
            path[path.size - 2]
        }
        return listTreeChildren(context, uri, parentId).filter { !it.isDirectory }.map { it.doc }
    }

    @RequiresApi(29)
    private fun mediaSiblings(): List<Doc>? {
        val relative = resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return null
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.getVolumeName(uri))
        return queryMedia(context, collection, "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(relative))
    }

    class TreeChild(val doc: ContentDoc, val documentId: String, val isDirectory: Boolean)

    companion object {
        fun isTreeDocument(uri: Uri): Boolean {
            val segments = uri.pathSegments
            return segments.size >= 4 && segments[0] == "tree" && segments[2] == "document"
        }

        /** Name, size and times of [uri], or null when it does not exist or cannot be read. */
        fun query(context: Context, uri: Uri): DocInfo? = try {
            if (uri.authority == MediaStore.AUTHORITY) {
                context.contentResolver.query(uri, MEDIA_INFO_COLUMNS, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null else mediaInfo(c, 0)
                }
            } else if (DocumentsContract.isDocumentUri(context, uri)) {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (!c.moveToFirst() || c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) null
                    else DocInfo(c.getString(0).orEmpty(), if (c.isNull(1)) 0 else c.getLong(1), if (c.isNull(2)) 0 else c.getLong(2))
                }
            } else {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null else DocInfo(c.getString(0).orEmpty(), if (c.isNull(1)) 0 else c.getLong(1), 0)
                }
            }
        } catch (e: Exception) {
            null
        }

        /** Children of the directory [parentDocumentId] inside the tree of [anyUriInTree]. */
        fun listTreeChildren(context: Context, anyUriInTree: Uri, parentDocumentId: String): List<TreeChild> {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(anyUriInTree, parentDocumentId)
            val out = mutableListOf<TreeChild>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val size = if (c.isNull(3)) 0 else c.getLong(3)
                    val modified = if (c.isNull(4)) 0 else c.getLong(4)
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(anyUriInTree, id)
                    out += TreeChild(ContentDoc(context, docUri, DocInfo(name, size, modified), parentDocumentId), id, isDir)
                }
            }
            return out
        }

        /** Columns [mediaInfo] reads, in order. */
        val MEDIA_INFO_COLUMNS = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        /** [DocInfo] from the [MEDIA_INFO_COLUMNS] starting at column [first] (MediaStore dates are in seconds). */
        fun mediaInfo(c: Cursor, first: Int): DocInfo = DocInfo(
            c.getString(first).orEmpty(),
            c.getLong(first + 1),
            c.getLong(first + 2) * 1000,
            if (c.isNull(first + 3)) 0 else c.getLong(first + 3) * 1000,
        )

        /** MediaStore image items matching [selection], as documents. */
        fun queryMedia(context: Context, collection: Uri, selection: String, args: Array<String>): List<ContentDoc> {
            val out = mutableListOf<ContentDoc>()
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, *MEDIA_INFO_COLUMNS),
                selection, args, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                    out += ContentDoc(context, uri, mediaInfo(c, 1))
                }
            }
            return out
        }
    }
}
