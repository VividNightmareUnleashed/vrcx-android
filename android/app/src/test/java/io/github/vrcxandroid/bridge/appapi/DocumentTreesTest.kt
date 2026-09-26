package io.github.vrcxandroid.bridge.appapi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The SAF-tree folder logic (UGC saving, photo listing) over a folder-backed [DocumentTree]. */
class DocumentTreesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var tree: FileDocumentTree

    @Before
    fun setUp() {
        dir = tmp.newFolder("tree")
        tree = FileDocumentTree(dir)
    }

    private fun file(path: String, bytes: ByteArray = byteArrayOf(1)) =
        File(dir, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test
    fun childDirMatchesCaseInsensitivelyAndCreatesOnlyWhenAsked() {
        File(dir, "Prints").mkdirs()
        assertEquals("Prints", TreeWalk.childDir(tree, tree.rootId, "prints", create = false))
        assertNull(TreeWalk.childDir(tree, tree.rootId, "Stickers", create = false))
        assertFalse(File(dir, "Stickers").exists())
        assertEquals("Stickers", TreeWalk.childDir(tree, tree.rootId, "Stickers", create = true))
        assertTrue(File(dir, "Stickers").isDirectory)
        // a file with the folder's name is not a folder
        file("Emoji")
        assertNull(TreeWalk.childDir(tree, tree.rootId, "Emoji", create = false))
    }

    @Test
    fun ugcFolderIsCreatedOnceAndReused() {
        val first = TreeUgc.folder(tree, "Prints", "2025-09")
        assertTrue(File(dir, "Prints/2025-09").isDirectory)
        assertFalse(first.exists("a.png"))
        val path = first.create("a.png", byteArrayOf(1, 2, 3))
        assertEquals(File(dir, "Prints/2025-09/a.png").absolutePath, path)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(path).readBytes())
        // FileDocumentTree.createDirectory fails for existing folders, so this also checks nothing is recreated
        val second = TreeUgc.folder(tree, "prints", "2025-09")
        assertTrue(second.exists("A.PNG"))
        assertEquals(listOf("Prints"), dir.list()!!.toList())
    }

    @Test
    fun emptyMonthFolderSavesIntoTheTypeFolder() {
        TreeUgc.folder(tree, "Emoji", "").create("e.png", byteArrayOf(9))
        assertTrue(File(dir, "Emoji/e.png").isFile)
    }

    @Test
    fun ugcListingIsRecursiveBelowTheTypeFolderOnly() {
        file("Prints/2025-08/a.png")
        file("Prints/2025-09/b.PNG")
        file("Prints/2025-09/notes.txt")
        file("Prints/c.png")
        file("Stickers/2025-09/s.png")
        file("top.png")
        val names = TreeUgc.listPngs(tree, "prints").map { it.name }
        assertEquals(listOf("c.png", "a.png", "b.PNG"), names)
        assertEquals(emptyList<String>(), TreeUgc.listPngs(tree, "Emoji").map { it.name })
        assertFalse(File(dir, "Emoji").exists())
    }

    @Test
    fun photoListingIsBreadthFirstWithRelativeFolders() {
        file("b.png")
        file("A.png")
        file("2025-09/VRChat_2.png")
        file("2025-09/VRChat_1.png")
        file("2025-08/x/deep.png")
        file("2025-08/VRChat_0.png")
        file("2025-08/readme.md")
        val entries = TreeWalk.listPngs(tree, tree.rootId)
        assertEquals(
            listOf("A.png", "b.png", "VRChat_0.png", "VRChat_1.png", "VRChat_2.png", "deep.png"),
            entries.map { it.doc.name },
        )
        assertEquals(listOf("", "", "2025-08", "2025-09", "2025-09", "2025-08/x"), entries.map { it.relativeDir })
        // every folder is listed exactly once: root, 2025-08, 2025-09, 2025-08/x
        assertEquals(4, tree.listings)
    }
}
