package io.github.vrcxandroid.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `<input type=file accept=...>` → picker choice (docs/ARCHITECTURE.md §6.10). */
class FileChooserTest {
    private val extensions = mapOf("json" to "application/json", "png" to "image/png")
    private fun mimes(vararg accept: String) = FileChooser.mimeTypesFor(accept.toList()) { extensions[it] }

    @Test
    fun imageAcceptsUseThePhotoPicker() {
        assertEquals(listOf("image/*"), mimes("image/*"))
        assertTrue(FileChooser.isImagesOnly(mimes("image/*")))
        assertTrue(FileChooser.isImagesOnly(mimes("image/png, image/jpeg")))
        assertTrue(FileChooser.isImagesOnly(mimes(".png")))
    }

    @Test
    fun extensionsAreMappedAndOtherTypesUseSaf() {
        assertEquals(listOf("application/json"), mimes(".json"))
        assertFalse(FileChooser.isImagesOnly(mimes(".json")))
        assertFalse(FileChooser.isImagesOnly(mimes("image/*", ".json")))
    }

    @Test
    fun emptyUnknownOrWildcardAcceptsMeanAnything() {
        assertEquals(emptyList<String>(), mimes())
        assertEquals(emptyList<String>(), mimes(""))
        assertEquals(emptyList<String>(), mimes(".unknownext"))
        assertEquals(emptyList<String>(), mimes("*/*", "image/*"))
        assertFalse(FileChooser.isImagesOnly(mimes()))
    }

    @Test
    fun listsAreSplitTrimmedLowercasedAndDeduplicated() {
        assertEquals(listOf("image/png", "text/plain"), mimes(" IMAGE/PNG ,text/plain", "image/png"))
    }
}
