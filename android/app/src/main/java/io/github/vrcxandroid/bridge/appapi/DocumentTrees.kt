package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.appapi.docs.Doc

/** One entry of a [DocumentTree] folder. */
class TreeEntry(val id: String, val doc: Doc, val isDirectory: Boolean)

/**
 * A folder tree the app may read and write: on Android a SAF tree the user granted ([SafDocumentTree]); in tests a
 * local directory. Ids are opaque (SAF document ids).
 */
interface DocumentTree {
    /** The string the frontend knows the tree by (the tree URI). */
    val location: String
    val rootId: String

    /** Files and folders directly inside the folder [parentId] (unsorted). */
    fun children(parentId: String): List<TreeEntry>

    /** Creates the folder [name] inside [parentId] and returns its id. */
    fun createDirectory(parentId: String, name: String): String

    /** Writes a new file [name] inside [parentId] and returns its path string (a content URI on Android). */
    fun createFile(parentId: String, name: String, mimeType: String, bytes: ByteArray): String

    /** URI string of the folder [id], for opening it in a file manager. */
    fun folderUri(id: String): String
}

/**
 * The folder logic of upstream `Directory.CreateDirectory` / `Directory.GetFiles(dir, "*.png", AllDirectories)` on a
 * [DocumentTree]. Names are matched case-insensitively, like the Windows file system VRCX was written for.
 */
object TreeWalk {
    /** Id of the folder [name] inside [parentId]; created when missing and [create] is set, else null. */
    fun childDir(tree: DocumentTree, parentId: String, name: String, create: Boolean): String? {
        tree.children(parentId).firstOrNull { it.isDirectory && it.doc.name.equals(name, ignoreCase = true) }?.let { return it.id }
        return if (create) tree.createDirectory(parentId, name) else null
    }

    /** Id of the folder at [segments] below the root (empty segments are skipped); see [childDir]. */
    fun dir(tree: DocumentTree, segments: List<String>, create: Boolean): String? {
        var id = tree.rootId
        for (segment in segments) {
            if (segment.isEmpty()) continue
            id = childDir(tree, id, segment, create) ?: return null
        }
        return id
    }

    /**
     * Every `*.png` below [startId]: breadth first, the files of each folder in case-insensitive name order before
     * its sub folders. [PhotoEntry.relativeDir] is the folder path below [startId] ("" for [startId] itself).
     */
    fun listPngs(tree: DocumentTree, startId: String): List<PhotoEntry> {
        val out = mutableListOf<PhotoEntry>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.addLast(startId to "")
        while (queue.isNotEmpty()) {
            val (id, relative) = queue.removeFirst()
            val children = tree.children(id).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.doc.name })
            for (child in children) {
                if (!child.isDirectory && child.doc.name.endsWith(".png", ignoreCase = true)) out += PhotoEntry(child.doc, relative)
            }
            for (child in children) {
                if (child.isDirectory) queue.addLast(child.id to (if (relative.isEmpty()) child.doc.name else "$relative/${child.doc.name}"))
            }
        }
        return out
    }

    /** Puts entries found by a flat query (MediaStore) in the order [listPngs] would produce. */
    fun sortLikeListPngs(entries: List<PhotoEntry>): List<PhotoEntry> {
        val parents = HashMap<String, List<String>>()
        fun path(relative: String) = parents.getOrPut(relative) { if (relative.isEmpty()) emptyList() else relative.split('/') }
        return entries.sortedWith { a, b ->
            val pa = path(a.relativeDir)
            val pb = path(b.relativeDir)
            // breadth first: shallower folders first, then folders in the order their ancestors were visited
            if (pa.size != pb.size) return@sortedWith pa.size - pb.size
            for (i in pa.indices) {
                val c = String.CASE_INSENSITIVE_ORDER.compare(pa[i], pb[i])
                if (c != 0) return@sortedWith c
            }
            String.CASE_INSENSITIVE_ORDER.compare(a.doc.name, b.doc.name)
        }
    }
}

/** The `<root>/<type>/<month>` part of the UGC storage on a [DocumentTree]. */
object TreeUgc {
    fun folder(tree: DocumentTree, type: String, monthFolder: String): UgcFolder =
        TreeUgcFolder(tree, TreeWalk.dir(tree, listOf(type, monthFolder), create = true)!!)

    /** Every PNG below `<root>/<type>`, recursively; empty when that folder does not exist. */
    fun listPngs(tree: DocumentTree, type: String): List<Doc> {
        val id = TreeWalk.dir(tree, listOf(type), create = false) ?: return emptyList()
        return TreeWalk.listPngs(tree, id).map { it.doc }
    }
}

class TreeUgcFolder(private val tree: DocumentTree, private val dirId: String) : UgcFolder {
    override fun exists(fileName: String): Boolean =
        tree.children(dirId).any { !it.isDirectory && it.doc.name.equals(fileName, ignoreCase = true) }

    override fun create(fileName: String, bytes: ByteArray): String = tree.createFile(dirId, fileName, "image/png", bytes)
}
