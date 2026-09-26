package io.github.vrcxandroid.bridge.appapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class CustomFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun kindsMapToTheTwoFileNames() {
        assertEquals("custom.css", CustomFiles.nameFor("css"))
        assertEquals("custom.js", CustomFiles.nameFor(" JS "))
        assertNull(CustomFiles.nameFor("html"))
        assertNull(CustomFiles.nameFor("../custom.js"))
        assertNull(CustomFiles.nameFor(null))
        assertEquals(File(File("files"), "custom"), CustomFiles.dir(File("files")))
    }

    @Test
    fun migrationCopiesTheOldCssOnceAndNeverTheExternalScript() {
        val external = tmp.newFolder("external")
        val dir = File(tmp.root, "files/custom")
        File(external, "custom.css").writeText("a{}")
        File(external, "custom.js").writeText("planted()")

        assertTrue(CustomFiles.migrate(dir, external, legacyScriptDir = null))
        assertEquals("a{}", CustomFiles.read(dir, "custom.css"))
        // the external folder is writable over USB and, on some versions, by other apps
        assertEquals("", CustomFiles.read(dir, "custom.js"))

        // once only: a later change in the old location is not picked up, and a removed file stays removed
        File(external, "custom.css").writeText("b{}")
        assertTrue(CustomFiles.remove(dir, "custom.css"))
        assertFalse(CustomFiles.migrate(dir, external, legacyScriptDir = null))
        assertEquals("", CustomFiles.read(dir, "custom.css"))
    }

    @Test
    fun migrationKeepsTheInternalScriptOfAndroid8To10AndNeverOverwrites() {
        val internal = tmp.newFolder("internal")
        val dir = File(tmp.root, "files/custom").apply { mkdirs() }
        File(internal, "custom.js").writeText("mine()")
        File(dir, "custom.css").writeText("new{}")
        val external = tmp.newFolder("external")
        File(external, "custom.css").writeText("old{}")

        CustomFiles.migrate(dir, external, legacyScriptDir = internal)
        assertEquals("mine()", CustomFiles.read(dir, "custom.js"))
        assertEquals("new{}", CustomFiles.read(dir, "custom.css"))
    }

    @Test
    fun installReplacesAtomicallyAndStripsNothingButTheBomOnRead() {
        val dir = File(tmp.root, "custom")
        CustomFiles.install(dir, "custom.css", ByteArrayInputStream("﻿body{}".toByteArray()))
        assertEquals("body{}", CustomFiles.read(dir, "custom.css"))
        CustomFiles.install(dir, "custom.css", ByteArrayInputStream("p{}".toByteArray()))
        assertEquals("p{}", CustomFiles.read(dir, "custom.css"))
        assertEquals(listOf("custom.css"), dir.list()!!.toList())
    }

    @Test
    fun installRejectsBinaryAndOversizedFilesAndKeepsTheOldOne() {
        val dir = File(tmp.root, "custom")
        CustomFiles.install(dir, "custom.js", ByteArrayInputStream("ok()".toByteArray()))
        try {
            CustomFiles.install(dir, "custom.js", ByteArrayInputStream(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 1)))
            fail("binary accepted")
        } catch (e: IOException) {
            assertEquals("The file is not a text file.", e.message)
        }
        try {
            CustomFiles.install(dir, "custom.js", ByteArrayInputStream(ByteArray((CustomFiles.MAX_BYTES + 1).toInt()) { 'a'.code.toByte() }))
            fail("oversized file accepted")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.startsWith("The file is larger than"))
        }
        assertEquals("ok()", CustomFiles.read(dir, "custom.js"))
        assertEquals(listOf("custom.js"), dir.list()!!.toList())
    }

    @Test
    fun removeIsTrueWhenNoFileRemains() {
        val dir = File(tmp.root, "custom")
        assertTrue(CustomFiles.remove(dir, "custom.js"))
        CustomFiles.install(dir, "custom.js", ByteArrayInputStream("x()".toByteArray()))
        assertTrue(CustomFiles.remove(dir, "custom.js"))
        assertEquals("", CustomFiles.read(dir, "custom.js"))
        assertEquals("", CustomFiles.read(null, "custom.js"))
    }
}
