package io.github.vrcxandroid.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalPathsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var files: File
    private lateinit var local: File
    private lateinit var cache: File
    private lateinit var paths: LocalPaths

    @Before
    fun setUp() {
        files = temp.newFolder("files")
        local = File(files, "local").apply { mkdirs() }
        cache = temp.newFolder("cache")
        paths = LocalPaths(local, cache)
    }

    private fun canonical(f: File) = f.canonicalFile

    @Test
    fun urlsForLocalAndCacheFiles() {
        val picked = File(local, "picked/shot 1.png").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals("https://appassets.androidplatform.net/local/picked/shot%201.png", paths.urlFor(picked))
        val cached = File(cache, "ImageCache/file_1/2.png").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals("https://appassets.androidplatform.net/local/cache/ImageCache/file_1/2.png", paths.urlFor(cached))
    }

    @Test
    fun urlEncodingIsUtf8PerSegment() {
        val f = File(local, "Überfahrt #1 (VRChat)?.png")
        assertEquals(
            "https://appassets.androidplatform.net/local/%C3%9Cberfahrt%20%231%20%28VRChat%29%3F.png",
            paths.urlFor(f),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun urlForAFileOutsideTheRootsThrows() {
        paths.urlFor(File(files, "VRCX/VRCX.sqlite3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun localCacheSubdirectoryIsReserved() {
        paths.urlFor(File(local, "cache/x.png"))
    }

    @Test
    fun urlsResolveBackToFiles() {
        val f = File(local, "picked/shot 1.png")
        assertEquals(canonical(f), paths.fileFor(paths.urlFor(f)))
        val c = File(cache, "ImageCache/file_1/2.png")
        assertEquals(canonical(c), paths.fileFor(paths.urlFor(c)))
        assertEquals(canonical(f), paths.fileFor("https://appassets.androidplatform.net/local/picked/shot%201.png?v=2#x"))
    }

    @Test
    fun absolutePathsInsideTheRootsAreAccepted() {
        val f = File(local, "a.png")
        assertEquals(canonical(f), paths.fileFor(f.absolutePath))
        val c = File(cache, "event.ics")
        assertEquals(canonical(c), paths.fileFor(c.absolutePath))
        assertEquals(canonical(f), paths.fileFor(f.toURI().toString()))
    }

    @Test
    fun pathsOutsideTheRootsAreRejected() {
        assertNull(paths.fileFor(File(files, "VRCX/VRCX.sqlite3").absolutePath))
        assertNull(paths.fileFor(File(local, "../VRCX/VRCX.sqlite3").absolutePath))
        assertNull(paths.fileFor("https://appassets.androidplatform.net/local/../VRCX/VRCX.sqlite3"))
        assertNull(paths.fileFor("https://appassets.androidplatform.net/local/%2E%2E/VRCX/VRCX.sqlite3"))
        assertNull(paths.fileFor("https://appassets.androidplatform.net/local/cache/../../files/x"))
        assertNull(paths.fileFor("https://evil.example/local/a.png"))
        assertNull(paths.fileFor("relative/path.png"))
        assertNull(paths.fileFor(""))
        assertNull(paths.fileFor(null))
        assertNull(paths.fileFor("https://appassets.androidplatform.net/local/bad%zzescape"))
    }

    @Test
    fun theRootsThemselvesAreNotFiles() {
        assertNull(paths.fileFor(local.absolutePath))
        assertNull(paths.fileFor("https://appassets.androidplatform.net/local/"))
    }

    @Test
    fun servedPathsStayInsideTheirRoot() {
        File(local, "picked").mkdirs()
        assertEquals(canonical(File(local, "picked/a.png")), paths.resolveServed(local, "picked/a.png"))
        assertNull(paths.resolveServed(local, "../VRCX/VRCX.sqlite3"))
        assertNull(paths.resolveServed(local, ""))
        assertNull(paths.resolveServed(local, "cache/x.png"))
        assertNull(paths.resolveServed(cache, "WebView/Default/Cookies"))
        assertNull(paths.resolveServed(cache, "org.chromium.android_webview/x"))
        assertEquals(canonical(File(cache, "ImageCache/f/1.png")), paths.resolveServed(cache, "ImageCache/f/1.png"))
        assertNull(paths.resolveServed(cache, "ImageCache/f/1.png", setOf("imagecache")))
    }

    @Test
    fun theWebViewCachesCannotBeReachedThroughAnotherFolder() {
        File(cache, "WebView/Default/HTTP Cache").mkdirs()
        File(cache, "ImageCache").mkdirs()
        File(cache, "org.chromium.android_webview").mkdirs()
        // the deny-list applies to the canonical path, not to the first segment of the raw suffix
        assertNull(paths.resolveServed(cache, "ImageCache/../WebView/Cookies"))
        assertNull(paths.resolveServed(cache, "./WebView/Cookies"))
        assertNull(paths.resolveServed(cache, "WebView/Default/HTTP Cache/Cache_Data/index"))
        assertNull(paths.resolveServed(cache, "webview/Cookies"))
        assertNull(paths.resolveServed(cache, "ImageCache/../org.chromium.android_webview/x"))
        assertNull(paths.resolveServed(cache, "org.chromium.other/x"))
        assertEquals(canonical(File(cache, "ImageCache/a.png")), paths.resolveServed(cache, "ImageCache/./a.png"))
        // the same through the AppApi side (URLs, absolute paths, file: URIs)
        assertNull(paths.fileFor("https://appassets.androidplatform.net/local/cache/ImageCache/%2E%2E/WebView/Cookies"))
        assertNull(paths.fileFor(File(cache, "ImageCache/../WebView/Cookies").absolutePath))
        assertNull(paths.fileFor(File(cache, "WebView/Default/Cookies").toURI().toString()))
        assertNull(paths.contained(File(cache, "org.chromium.android_webview/x")))
        assertEquals(canonical(File(cache, "ImageCache/a.png")), paths.contained(File(cache, "ImageCache/a.png")))
        // folders of the same name below filesDir/local are ordinary app files
        assertEquals(canonical(File(local, "WebView/a.png")), paths.fileFor(File(local, "WebView/a.png").absolutePath))
    }

    @Test
    fun percentCodecRoundTrips() {
        val name = "a b/ü/#?%/😀.png"
        assertEquals(name, LocalPaths.decodePath(LocalPaths.encodePath(name)))
        assertEquals("a+b", LocalPaths.decodePath("a+b"))
        assertNull(LocalPaths.decodePath("%4"))
    }
}
