package io.github.vrcxandroid.bridge.appapi

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.vrcxandroid.bridge.appapi.docs.ContentDoc
import io.github.vrcxandroid.bridge.appapi.docs.Doc
import io.github.vrcxandroid.bridge.appapi.docs.LocalFileDoc
import java.io.File
import java.io.IOException

private const val PREF_UGC_TREE = "ugcTreeUri"
private const val PREF_PHOTOS_TREE = "photosTreeUri"
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** SAF tree helpers shared by the UGC storage and the photos library. */
internal class Trees(private val context: Context) {
    /** [path] as a tree URI this app still holds a persisted permission for, or null. */
    fun granted(path: String?, write: Boolean): Uri? {
        if (path.isNullOrEmpty() || !path.startsWith("content://")) return null
        val uri = Uri.parse(path)
        if (!isTreeUri(uri)) return null
        val ok = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && (!write || it.isWritePermission)
        }
        return if (ok) uri else null
    }

    fun isTreeUri(uri: Uri): Boolean {
        val segments = uri.pathSegments
        return segments.size >= 2 && segments[0] == "tree"
    }

    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    /** Id of the sub folder [name] of [parentId] (case-insensitive like Windows); created when [create]. */
    fun childDir(tree: Uri, parentId: String, name: String, create: Boolean): String? {
        ContentDoc.listTreeChildren(context, tree, parentId)
            .firstOrNull { it.isDirectory && it.doc.name.equals(name, ignoreCase = true) }
            ?.let { return it.documentId }
        if (!create) return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(context.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Could not create the folder '$name'.")
        return DocumentsContract.getDocumentId(created)
    }

    /** Every `*.png` below [startId], breadth first, each folder's files sorted by name. */
    fun listPngs(tree: Uri, startId: String): List<PhotoEntry> {
        val out = mutableListOf<PhotoEntry>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast(startId to "")
        while (queue.isNotEmpty()) {
            val (id, relative) = queue.removeFirst()
            val children = ContentDoc.listTreeChildren(context, tree, id).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.doc.name })
            for (child in children) {
                if (!child.isDirectory && child.doc.name.endsWith(".png", ignoreCase = true)) out += PhotoEntry(child.doc, relative)
            }
            for (child in children) {
                if (child.isDirectory) queue.addLast(child.documentId to (if (relative.isEmpty()) child.doc.name else "$relative/${child.doc.name}"))
            }
        }
        return out
    }

    fun documentUri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
}

/**
 * Prints, stickers and emoji (ARCHITECTURE.md §9): MediaStore `Pictures/VRCX/<type>/<month>/` by default, or the SAF
 * tree the user picked as UGC folder. A non-content path (for example a Windows path from an imported PC config) or a
 * tree whose permission was revoked falls back to the default, like upstream falls back to the photos folder.
 */
class AndroidUgcStorage(private val context: Context, private val prefs: SharedPreferences, private val view: (Uri, String?) -> Boolean) : UgcStorage {
    private val trees = Trees(context)

    /** The granted UGC tree, remembered so the photos library can fall back to it. */
    private fun treeFor(ugcFolderPath: String?, write: Boolean): Uri? {
        val tree = trees.granted(ugcFolderPath, write)
        val value = tree?.toString()
        if (prefs.getString(PREF_UGC_TREE, null) != value) {
            prefs.edit().apply { if (value != null) putString(PREF_UGC_TREE, value) else remove(PREF_UGC_TREE) }.apply()
        }
        return tree
    }

    override fun folder(ugcFolderPath: String?, type: String, monthFolder: String): UgcFolder {
        val segments = listOf(type, monthFolder).filter { it.isNotEmpty() }
        val tree = treeFor(ugcFolderPath, write = true)
        if (tree != null) {
            var id = trees.rootId(tree)
            for (s in segments) id = trees.childDir(tree, id, s, create = true)!!
            return SafFolder(tree, id)
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            MediaStoreFolder(relativePath(segments))
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VRCX/" + segments.joinToString("/"))
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Access to the path '${dir.absolutePath}' is denied.")
            LegacyFolder(dir)
        }
    }

    override fun listPngs(ugcFolderPath: String?, type: String): List<Doc> {
        val tree = treeFor(ugcFolderPath, write = true)
        if (tree != null) {
            val id = trees.childDir(tree, trees.rootId(tree), type, create = false) ?: return emptyList()
            return trees.listPngs(tree, id).map { it.doc }
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            ContentDoc.queryMedia(
                context,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("${DEFAULT_ROOT}/$type/%", "%.png"),
            )
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VRCX/$type")
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) }.map { LocalFileDoc(it) }.toList()
        }
    }

    override fun open(ugcFolderPath: String?): Boolean {
        val tree = treeFor(ugcFolderPath, write = false)
        if (tree != null) return view(trees.documentUri(tree, trees.rootId(tree)), DocumentsContract.Document.MIME_TYPE_DIR)
        if (Build.VERSION.SDK_INT >= 29) {
            val any = ContentDoc.queryMedia(
                context,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$DEFAULT_ROOT/%"),
            ).isNotEmpty()
            if (!any) return false
        }
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$DEFAULT_ROOT")
        return view(folder, DocumentsContract.Document.MIME_TYPE_DIR)
    }

    private fun relativePath(segments: List<String>): String = (listOf(DEFAULT_ROOT) + segments).joinToString("/") + "/"

    private inner class SafFolder(private val tree: Uri, private val dirId: String) : UgcFolder {
        override fun exists(fileName: String): Boolean =
            ContentDoc.listTreeChildren(context, tree, dirId).any { !it.isDirectory && it.doc.name.equals(fileName, ignoreCase = true) }

        override fun create(fileName: String, bytes: ByteArray): String {
            val uri = DocumentsContract.createDocument(context.contentResolver, trees.documentUri(tree, dirId), "image/png", fileName)
                ?: throw IOException("Could not create '$fileName'.")
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: throw IOException("Could not write '$fileName'.")
            return uri.toString()
        }
    }

    private inner class MediaStoreFolder(private val relativePath: String) : UgcFolder {
        private val collection: Uri
            get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        override fun exists(fileName: String): Boolean = ContentDoc.queryMedia(
            context,
            collection,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(relativePath, fileName),
        ).isNotEmpty()

        override fun create(fileName: String, bytes: ByteArray): String {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("Could not create '$fileName'.")
            try {
                resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: throw IOException("Could not write '$fileName'.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri.toString()
        }
    }

    private inner class LegacyFolder(private val dir: File) : UgcFolder {
        override fun exists(fileName: String): Boolean = File(dir, fileName).exists()

        override fun create(fileName: String, bytes: ByteArray): String {
            val file = File(dir, fileName)
            file.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            return file.absolutePath
        }
    }

    companion object {
        const val DEFAULT_ROOT = "Pictures/VRCX"
    }
}

/**
 * The folder the screenshot tools search (upstream `GetVRChatPhotosLocation`): a SAF tree picked through
 * `OpenVrcPhotosFolder`, else the UGC tree, else MediaStore `Pictures/VRChat/` (what the app can see there).
 */
class AndroidPhotosLibrary(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val view: (Uri, String?) -> Boolean,
    private val pickDirectory: suspend () -> Uri?,
) : PhotosLibrary {
    private val trees = Trees(context)

    private fun rootTree(): Uri? =
        trees.granted(prefs.getString(PREF_PHOTOS_TREE, null), write = false)
            ?: trees.granted(prefs.getString(PREF_UGC_TREE, null), write = false)

    override fun location(): String = rootTree()?.toString().orEmpty()

    override fun listPngs(): List<PhotoEntry>? {
        rootTree()?.let { tree -> return trees.listPngs(tree, trees.rootId(tree)) }
        if (Build.VERSION.SDK_INT < 29) return null
        val prefix = "Pictures/VRChat/"
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val out = mutableListOf<PhotoEntry>()
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%", "%.png"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
                val relative = c.getString(4).orEmpty().removePrefix(prefix).trimEnd('/')
                out += PhotoEntry(
                    ContentDoc(context, uri, io.github.vrcxandroid.bridge.appapi.docs.DocInfo(c.getString(1).orEmpty(), c.getLong(2), c.getLong(3) * 1000)),
                    relative,
                )
            }
        }
        return out.sortedWith(compareBy<PhotoEntry> { it.relativeDir.count { ch -> ch == '/' } }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.relativeDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.doc.name })
    }

    override suspend fun open(): Boolean {
        var tree = rootTree()
        if (tree == null) {
            tree = pickDirectory() ?: return false
            prefs.edit().putString(PREF_PHOTOS_TREE, tree.toString()).apply()
        }
        return view(trees.documentUri(tree, trees.rootId(tree)), DocumentsContract.Document.MIME_TYPE_DIR)
    }
}
