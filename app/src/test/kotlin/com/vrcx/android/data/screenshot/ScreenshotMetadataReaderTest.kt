package com.vrcx.android.data.screenshot

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotMetadataReaderTest {
    @Test
    fun `read parses VRCX description metadata`() {
        val json = """
            {
              "application": "VRCX",
              "version": 1,
              "author": {
                "id": "usr_author",
                "displayName": "Alice"
              },
              "world": {
                "id": "wrld_world",
                "name": "Test World",
                "instanceId": "wrld_world:12345"
              },
              "timestamp": "2026-05-12T08:00:00Z",
              "players": [
                {
                  "id": "usr_bob",
                  "displayName": "Bob"
                }
              ]
            }
        """.trimIndent()

        val result = ScreenshotMetadataReader.read(
            png(
                "IHDR" to ihdr(width = 1920, height = 1080),
                "iTXt" to iTxt(keyword = "Description", text = json),
                "IDAT" to byteArrayOf(),
            ).inputStream(),
            fileName = "VRChat_1920x1080_2026-05-12_10-20-30.000.png",
        ).parsed()

        assertEquals("1920x1080", result.resolution)
        assertEquals("VRCX", result.metadata.application)
        assertEquals("Alice", result.metadata.author.displayName)
        assertEquals("wrld_world:12345", result.metadata.world.instanceId)
        assertEquals("Bob", result.metadata.players.single().displayName)
        assertEquals(Instant.parse("2026-05-12T08:00:00Z").toEpochMilli(), result.capturedAtEpochMillis)
    }

    @Test
    fun `read parses VRChat XMP metadata`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description
                  xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                  xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
                  xmlns:vrc="http://ns.vrchat.com/vrc/1.0/"
                  xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <xmp:CreatorTool>VRChat</xmp:CreatorTool>
                  <xmp:Author>Alice</xmp:Author>
                  <tiff:DateTime>2026-05-12T08:00:00Z</tiff:DateTime>
                  <vrc:AuthorID>usr_author</vrc:AuthorID>
                  <vrc:WorldID>wrld_world</vrc:WorldID>
                  <vrc:WorldDisplayName>Test World</vrc:WorldDisplayName>
                  <dc:title>
                    <rdf:Alt>
                      <rdf:li>hello from a screenshot</rdf:li>
                    </rdf:Alt>
                  </dc:title>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val result = ScreenshotMetadataReader.read(
            png(
                "IHDR" to ihdr(width = 1200, height = 900),
                "iTXt" to iTxt(keyword = "XML:com.adobe.xmp", text = xmp),
                "IDAT" to byteArrayOf(),
            ).inputStream(),
        ).parsed()

        assertEquals("1200x900", result.resolution)
        assertEquals("VRChat", result.metadata.application)
        assertEquals("usr_author", result.metadata.author.id)
        assertEquals("Alice", result.metadata.author.displayName)
        assertEquals("Test World", result.metadata.world.name)
        assertEquals("hello from a screenshot", result.metadata.note)
        assertEquals(Instant.parse("2026-05-12T08:00:00Z").toEpochMilli(), result.capturedAtEpochMillis)
    }

    @Test
    fun `read falls back to VRChat screenshot filename capture date`() {
        val json = """
            {
              "application": "VRCX",
              "version": 1,
              "author": {
                "id": "usr_author",
                "displayName": "Alice"
              },
              "world": {
                "id": "wrld_world",
                "name": "Test World",
                "instanceId": "wrld_world:12345"
              },
              "players": []
            }
        """.trimIndent()

        val result = ScreenshotMetadataReader.read(
            png(
                "IHDR" to ihdr(width = 1920, height = 1080),
                "iTXt" to iTxt(keyword = "Description", text = json),
                "IDAT" to byteArrayOf(),
            ).inputStream(),
            fileName = "VRChat_1920x1080_2026-05-12_10-20-30.000.png",
        ).parsed()

        val expected = LocalDateTime.of(2026, 5, 12, 10, 20, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, result.capturedAtEpochMillis)
    }

    @Test
    fun `read keeps the XMP author fallback when AuthorID is absent`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description xmlns:xmp="http://ns.adobe.com/xap/1.0/">
                  <xmp:CreatorTool>VRChat</xmp:CreatorTool>
                  <xmp:Author>Alice</xmp:Author>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val result = ScreenshotMetadataReader.read(
            png(
                "IHDR" to ihdr(width = 1200, height = 900),
                "iTXt" to iTxt(keyword = "XML:com.adobe.xmp", text = xmp),
                "IDAT" to byteArrayOf(),
            ).inputStream(),
        ).parsed()

        assertEquals("Alice", result.metadata.author.id)
        assertNull(result.metadata.author.displayName)
    }

    @Test
    fun `read reports invalid PNG`() {
        val result = ScreenshotMetadataReader.read("not a png".toByteArray().inputStream())

        assertEquals(
            ScreenshotReadResult.Failed("Invalid file selected. Please select a valid PNG screenshot."),
            result,
        )
    }

    @Test
    fun `read reports a readable PNG that carries no metadata`() {
        val result = ScreenshotMetadataReader.read(
            png(
                "IHDR" to ihdr(width = 640, height = 480),
                "IDAT" to byteArrayOf(1, 2, 3),
            ).inputStream(),
        )

        assertEquals(ScreenshotReadResult.NoMetadata("640x480"), result)
    }

    @Test
    fun `read finds legacy description appended after IDAT`() {
        val legacy = "lfs|2|author:usr_author,Alice|world:wrld_world,12345,Test World|pos:1,2,3"
        val result = ScreenshotMetadataReader.read(
            png(
                "IHDR" to ihdr(width = 1920, height = 1080),
                "IDAT" to byteArrayOf(1, 2, 3),
                "iTXt" to iTxt(keyword = "Description", text = legacy),
            ).inputStream(),
        ).parsed()

        assertEquals("lfs", result.metadata.application)
        assertEquals("usr_author", result.metadata.author.id)
        assertEquals("wrld_world:12345", result.metadata.world.instanceId)
    }

    @Test
    fun `read rejects an IHDR chunk declaring a huge length`() {
        val result = ScreenshotMetadataReader.read(
            pngWithDeclaredLength(
                type = "IHDR",
                declaredLength = 0x03FFFFFF,
                data = ihdr(width = 1920, height = 1080),
            ).inputStream(),
        )

        assertEquals(
            ScreenshotReadResult.Failed("Invalid file selected. Please select a valid PNG screenshot."),
            result,
        )
    }

    @Test
    fun `read skips an oversized IHDR chunk instead of parsing it`() {
        val json = """{"application":"VRCX","version":1}"""
        val result = ScreenshotMetadataReader.read(
            png(
                "IHDR" to ihdr(width = 1920, height = 1080).copyOf(1024),
                "iTXt" to iTxt(keyword = "Description", text = json),
                "IDAT" to byteArrayOf(),
            ).inputStream(),
        ).parsed()

        assertNull(result.resolution)
        assertEquals("VRCX", result.metadata.application)
    }

    @Test
    fun `read reports a stream truncated after the signature`() {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        val result = ScreenshotMetadataReader.read(signature.inputStream())

        assertEquals(
            ScreenshotReadResult.Failed("Invalid file selected. Please select a valid PNG screenshot."),
            result,
        )
    }

    @Test
    fun `read stops once the chunk limit is reached`() {
        val json = """{"application":"VRCX","version":1}"""
        val chunks = buildList {
            add("IHDR" to ihdr(width = 8, height = 8))
            repeat(4200) { add("tEXt" to byteArrayOf()) }
            add("iTXt" to iTxt(keyword = "Description", text = json))
        }

        val result = ScreenshotMetadataReader.read(png(*chunks.toTypedArray()).inputStream())

        // The Description chunk sits past the limit, so it is never reached.
        assertEquals(ScreenshotReadResult.NoMetadata("8x8"), result)
    }

    /** Asserts the read produced metadata, and hands back the parsed result. */
    private fun ScreenshotReadResult.parsed(): ScreenshotReadResult.Parsed {
        assertTrue("expected parsed metadata, got $this", this is ScreenshotReadResult.Parsed)
        return this as ScreenshotReadResult.Parsed
    }

    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunks.forEach { (type, data) ->
            DataOutputStream(output).useChunk(type, data)
        }
        if (chunks.none { it.first == "IEND" }) {
            DataOutputStream(output).useChunk("IEND", byteArrayOf())
        }
        return output.toByteArray()
    }

    private fun pngWithDeclaredLength(type: String, declaredLength: Int, data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        DataOutputStream(output).apply {
            writeInt(declaredLength)
            write(type.toByteArray(StandardCharsets.US_ASCII))
            write(data)
            writeInt(0)
            flush()
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.useChunk(type: String, data: ByteArray) {
        writeInt(data.size)
        write(type.toByteArray(StandardCharsets.US_ASCII))
        write(data)
        writeInt(0)
        flush()
    }

    private fun ihdr(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(width)
            data.writeInt(height)
            data.write(byteArrayOf(8, 6, 0, 0, 0))
        }
        return output.toByteArray()
    }

    private fun iTxt(keyword: String, text: String): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(keyword.toByteArray(StandardCharsets.UTF_8))
        output.write(0)
        output.write(0)
        output.write(0)
        output.write(0)
        output.write(0)
        output.write(text.toByteArray(StandardCharsets.UTF_8))
        return output.toByteArray()
    }
}
