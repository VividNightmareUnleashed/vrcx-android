package io.github.vrcxandroid.bridge.appapi

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.vrcxandroid.bridge.appapi.docs.ContentDoc
import io.github.vrcxandroid.bridge.appapi.docs.Doc
import io.github.vrcxandroid.bridge.appapi.docs.DocInfo
import io.github.vrcxandroid.bridge.appapi.docs.LocalFileDoc
import java.io.File
import java.io.IOException

private const val PREF_UGC_TREE = "ugcTreeUri"
private const val PREF_PHOTOS_TREE = "photosTreeUri"
private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** A SAF tree (ACTION_OPEN_DOCUMENT_TREE result) as a [DocumentTree]. */
class SafDocumentTree(private val context: Context, val treeUri: Uri) : DocumentTree {
    override val location: String get() = treeUri.toString()
    override val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun children(parentId: String): List<TreeEntry> =
        ContentDoc.listTreeChildren(context, treeUri, parentId).map { TreeEntry(it.documentId, it.doc, it.isDirectory) }

    override fun createDirectory(parentId: String, name: String): String {
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            documentUri(parentId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: throw IOException("Could not create the folder '$name'.")
        return DocumentsContract.getDocumentId(created)
    }

    override fun createFile(parentId: String, name: String, mimeType: String, bytes: ByteArray): String {
        val resolver = context.contentResolver
        val uri = DocumentsContract.createDocument(resolver, documentUri(parentId), mimeType, name)
            ?: throw IOException("Could not create '$name'.")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: throw IOException("Could not write '$name'.")
        } catch (e: Exception) {
            try {
                DocumentsContract.deleteDocument(resolver, uri)
            } catch (ignored: Exception) {
                // keep the original error
            }
            throw e
        }
        return uri.toString()
    }

    override fun folderUri(id: String): String = documentUri(id).toString()

    private fun documentUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    companion object {
        /** [path] as a tree this app still holds a persisted permission for, or null. */
        fun granted(context: Context, path: String?, write: Boolean): SafDocumentTree? {
            if (path.isNullOrEmpty() || !path.startsWith("content://")) return null
            val uri = Uri.parse(path)
            val segments = uri.pathSegments
            if (segments.size < 2 || segments[0] != "tree") return null
            val ok = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && (!write || it.isWritePermission)
            }
            return if (ok) SafDocumentTree(context, uri) else null
        }
    }
}

/**
 * Prints, stickers and emoji (ARCHITECTURE.md §9): MediaStore `Pictures/VRCX/<type>/<month>/` by default, or the SAF
 * tree the user picked as UGC folder. A non-content path (for example a Windows path from an imported PC config) or a
 * tree whose permission was revoked falls back to the default, like upstream falls back to the photos folder.
 */
class AndroidUgcStorage(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val view: (Uri, String?) -> Boolean,
) : UgcStorage {
    /** The granted UGC tree, remembered so the photos library can fall back to it. */
    private fun treeFor(ugcFolderPath: String?, write: Boolean): SafDocumentTree? {
        val tree = SafDocumentTree.granted(context, ugcFolderPath, write)
        val value = tree?.location
        if (prefs.getString(PREF_UGC_TREE, null) != value) {
            prefs.edit().apply { if (value != null) putString(PREF_UGC_TREE, value) else remove(PREF_UGC_TREE) }.apply()
        }
        return tree
    }

    override fun folder(ugcFolderPath: String?, type: String, monthFolder: String): UgcFolder {
        treeFor(ugcFolderPath, write = true)?.let { return TreeUgc.folder(it, type, monthFolder) }
        val segments = listOf(type, monthFolder).filter { it.isNotEmpty() }
        if (Build.VERSION.SDK_INT >= 29) return MediaStoreFolder(relativePath(segments))
        val dir = File(legacyRoot(), segments.joinToString("/"))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Access to the path '${dir.absolutePath}' is denied.")
        return LegacyFolder(dir)
    }

    override fun listPngs(ugcFolderPath: String?, type: String): List<Doc> {
        treeFor(ugcFolderPath, write = true)?.let { return TreeUgc.listPngs(it, type) }
        if (Build.VERSION.SDK_INT >= 29) {
            return ContentDoc.queryMedia(
                context,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$DEFAULT_ROOT/$type/%", "%.png"),
            )
        }
        return File(legacyRoot(), type).walkTopDown()
            .filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) }
            .map { LocalFileDoc(it) }
            .toList()
    }

    override fun open(ugcFolderPath: String?): Boolean {
        treeFor(ugcFolderPath, write = false)?.let { tree ->
            return view(Uri.parse(tree.folderUri(tree.rootId)), DocumentsContract.Document.MIME_TYPE_DIR)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val any = ContentDoc.queryMedia(
                context,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$DEFAULT_ROOT/%"),
            ).isNotEmpty()
            // upstream returns false when the folder does not exist yet
            if (!any) return false
            val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$DEFAULT_ROOT")
            return view(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        }
        val root = legacyRoot()
        if (!root.isDirectory) return false
        // file:// URIs cannot be shared with other apps; address the folder through the external storage provider
        @Suppress("DEPRECATION")
        val storage = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        if (!root.absolutePath.startsWith("$storage/")) return false
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:" + root.absolutePath.substring(storage.length + 1))
        return view(folder, DocumentsContract.Document.MIME_TYPE_DIR)
    }

    private fun relativePath(segments: List<String>): String = (listOf(DEFAULT_ROOT) + segments).joinToString("/") + "/"

    /**
     * Android 8-9: the public `Pictures/VRCX` when the app may write there (WRITE_EXTERNAL_STORAGE), otherwise the
     * app's own `Android/data/<package>/files/Pictures/VRCX`, which needs no permission.
     */
    @Suppress("DEPRECATION")
    private fun legacyRoot(): File {
        val canWritePublic = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val pictures = if (canWritePublic) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: throw IOException("Shared storage is not available.")
        }
        return File(pictures, "VRCX")
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
    private fun rootTree(): SafDocumentTree? =
        SafDocumentTree.granted(context, prefs.getString(PREF_PHOTOS_TREE, null), write = false)
            ?: SafDocumentTree.granted(context, prefs.getString(PREF_UGC_TREE, null), write = false)

    override fun location(): String = rootTree()?.location.orEmpty()

    override fun listPngs(): List<PhotoEntry>? {
        rootTree()?.let { tree -> return TreeWalk.listPngs(tree, tree.rootId) }
        if (Build.VERSION.SDK_INT < 29) return null
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
            arrayOf("$MEDIA_PREFIX%", "%.png"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                val relative = c.getString(4).orEmpty().removePrefix(MEDIA_PREFIX).trimEnd('/')
                out += PhotoEntry(ContentDoc(context, uri, DocInfo(c.getString(1).orEmpty(), c.getLong(2), c.getLong(3) * 1000)), relative)
            }
        }
        return TreeWalk.sortLikeListPngs(out)
    }

    override suspend fun open(): Boolean {
        var tree = rootTree()
        if (tree == null) {
            val picked = pickDirectory() ?: return false
            prefs.edit().putString(PREF_PHOTOS_TREE, picked.toString()).apply()
            tree = SafDocumentTree(context, picked)
        }
        return view(Uri.parse(tree.folderUri(tree.rootId)), DocumentsContract.Document.MIME_TYPE_DIR)
    }

    companion object {
        /** MediaStore relative path of VRChat's photo folder on PC-like layouts. */
        const val MEDIA_PREFIX = "Pictures/VRChat/"
    }
}
