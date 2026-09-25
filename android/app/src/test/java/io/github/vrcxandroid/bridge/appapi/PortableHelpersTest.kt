package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.DotNetException
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/** Byte-exact checks of the portable helpers against the .NET vectors. */
class PortableHelpersTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun colourFromUserIdMatchesDotNet() {
        for (v in Vectors.root["colour"]!!.jsonArray) {
            val o = v.jsonObject
            assertEquals(o["id"]!!.jsonPrimitive.content, o["colour"]!!.jsonPrimitive.int, Hashing.colourFromUserId(o["id"]!!.jsonPrimitive.content))
        }
    }

    @Test
    fun colourBulkReturnsPairsInInputOrder() {
        val v = Vectors.root["colourBulk"]!!.jsonObject
        val ids = v["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
        val expected = v["pairs"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.int }
        assertEquals(expected, Hashing.colourBulk(ids))
    }

    @Test
    fun colourBulkRejectsDuplicatesLikeDictionaryAdd() {
        val e = assertThrows(DotNetException::class.java) { Hashing.colourBulk(listOf("a", "b", "a")) }
        assertEquals("ArgumentException", e.type)
    }

    private fun inputOf(o: kotlinx.serialization.json.JsonObject): ByteArray {
        val n = o["n"]!!.jsonPrimitive.int
        return when (o["generator"]!!.jsonPrimitive.content) {
            "bytes" -> Base64.getDecoder().decode(o["b64"]!!.jsonPrimitive.content)
            "pattern" -> Vectors.pattern(n)
            "lcg" -> Vectors.lcg(n, o["seed"]!!.jsonPrimitive.long)
            else -> error("unknown generator")
        }
    }

    @Test
    fun md5FileLengthAndSignFileMatchDotNet() {
        for (v in Vectors.root["files"]!!.jsonArray) {
            val o = v.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val b64 = Base64.getEncoder().encodeToString(inputOf(o))
            assertEquals(name, o["md5"]!!.jsonPrimitive.content, Hashing.md5File(b64))
            assertEquals(name, o["length"]!!.jsonPrimitive.content, Hashing.fileLength(b64))
            val signature = Hashing.signFile(b64)
            assertEquals(name, o["signatureSha256"]!!.jsonPrimitive.content, Vectors.sha256(Base64.getDecoder().decode(signature)))
            o["signature"]?.let { assertEquals(name, it.jsonPrimitive.content, signature) }
        }
    }

    @Test
    fun signFileMatchesTheDocumentedVector() {
        // 5000 bytes b[i] = (i*7) & 0xFF → 120-byte signature
        val sig = RsyncSignature.compute(Vectors.pattern(5000))
        assertEquals(120, sig.size)
        assertEquals("72730137000008000000002" + "0a800f40004ee9d9c2bc7fa9cadff6681440d89bb8322f282c2527dc9f429ada7609704c1", hex(sig.copyOfRange(0, 48)))
        assertEquals("b4282bfcdd7b78f2ee4e15c8767c24efa8ea1e6f45fc16b9f0790276e5e80b1b75456e6d", hex(sig.copyOfRange(84, 120)))
    }

    @Test
    fun blake2bMatchesRfc7693() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(Blake2b.hash("abc".toByteArray())),
        )
        assertEquals("0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8", hex(Blake2b.hash(ByteArray(0), 32)))
        // multi-block input with a full final block
        val data = ByteArray(256) { it.toByte() }
        val oneShot = Blake2b.hash(data, 32)
        val incremental = Blake2b(32).apply {
            update(data, 0, 100)
            update(data, 100, 156)
        }.digest()
        assertArrayEquals(oneShot, incremental)
    }

    @Test
    fun base64DecodingFollowsDotNet() {
        assertArrayEquals("hello".toByteArray(), Hashing.decodeBase64("aGVs\r\nbG8="))
        val e = assertThrows(DotNetException::class.java) { Hashing.decodeBase64("aGVsbG8") }
        assertEquals("FormatException", e.type)
        assertThrows(DotNetException::class.java) { Hashing.decodeBase64("aGV*bG8=") }
    }

    @Test
    fun makeValidFileNameMatchesDotNetOnWindows() {
        for (v in Vectors.root["makeValidFileName"]!!.jsonArray) {
            val o = v.jsonObject
            val input = o["input"]!!.jsonPrimitive.content
            assertEquals(input, o["output"]!!.jsonPrimitive.content, FileNames.makeValidFileName(input))
        }
    }

    @Test
    fun floatFormattingMatchesNewtonsoft() {
        for (v in Vectors.root["floatFormat"]!!.jsonArray) {
            val o = v.jsonObject
            val f = java.lang.Float.intBitsToFloat(o["bits"]!!.jsonPrimitive.int)
            assertEquals("$f", o["text"]!!.jsonPrimitive.content, NJ.formatFloat(f))
        }
    }

    @Test
    fun fileSizeTextMatchesDotNet() {
        for (v in Vectors.root["fileSize"]!!.jsonArray) {
            val o = v.jsonObject
            assertEquals(o["text"]!!.jsonPrimitive.content, "${NJ.formatMegabytes(o["bytes"]!!.jsonPrimitive.long)} MB")
        }
    }

    @Test
    fun jsonWriterMatchesNewtonsoftIndentation() {
        val o = NJ.Obj()
        o.put("a", "q\"b\\c\n\u0001" + Char(0x2028) + "é" + Char(0x85))
        o["empty"] = NJ.Arr()
        o["obj"] = NJ.Obj()
        o["list"] = NJ.Arr(mutableListOf(NJ.Int(1), NJ.Null, NJ.Obj().also { it["x"] = NJ.Flt(1f) }))
        assertEquals(
            "{\n  \"a\": \"q\\\"b\\\\c\\n\\u0001\\u2028é\\u0085\",\n  \"empty\": [],\n  \"obj\": {},\n  \"list\": [\n    1,\n    null,\n" +
                "    {\n      \"x\": 1.0\n    }\n  ]\n}",
            o.toIndentedString(),
        )
    }

    @Test
    fun versionStringFollowsProgramGetVersion() {
        assertEquals("VRCX 2026.09.16", VersionInfo.versionString("2026.09.16\n"))
        assertEquals("VRCX Nightly 2026.09.16-22bcd96", VersionInfo.versionString("2026.09.16-22bcd96"))
        assertEquals("VRCX 2026.09.16-beta", VersionInfo.versionString("2026.09.16-beta"))
    }

    @Test
    fun cultureTagsDropExtensionsAndDefaultToEnUs() {
        assertEquals("de-DE", VersionInfo.cultureTag(java.util.Locale.forLanguageTag("de-DE-u-mu-celsius")))
        assertEquals("zh-Hans-CN", VersionInfo.cultureTag(java.util.Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("en-US", VersionInfo.cultureTag(java.util.Locale.ROOT))
        assertEquals("en-US", VersionInfo.cultureTag(null))
    }

    @Test
    fun resizeGeometryMatchesImageSharpRuns() {
        for (v in Vectors.root["resize"]!!.jsonArray) {
            val o = v.jsonObject
            val w = o["w"]!!.jsonPrimitive.int
            val h = o["h"]!!.jsonPrimitive.int
            var (fw, fh) = ImageGeometry.fitLimits(w, h, 2000, 2000)
            if (o["matching"]!!.jsonPrimitive.content == "true" && fw != fh) {
                fw = maxOf(fw, fh)
                fh = fw
            }
            assertEquals("$w x $h", o["outW"]!!.jsonPrimitive.int to o["outH"]!!.jsonPrimitive.int, fw to fh)
        }
    }

    @Test
    fun printGeometryMatchesImageSharpRuns() {
        for (v in Vectors.root["resizePrint"]!!.jsonArray) {
            val o = v.jsonObject
            val w = o["w"]!!.jsonPrimitive.int
            val h = o["h"]!!.jsonPrimitive.int
            val box = ImageGeometry.printPictureBox(ImageGeometry.printLayout(w, h))
            val expected = listOf("x", "y", "innerW", "innerH").map { o[it]!!.jsonPrimitive.int }
            assertEquals("$w x $h", expected, box.toList())
        }
    }

    @Test
    fun shrinkStepKeepsAspectRatio() {
        assertEquals(1975 to 1481, ImageGeometry.shrinkStep(2000, 1500))
        assertEquals(1481 to 1975, ImageGeometry.shrinkStep(1500, 2000))
        assertEquals(1975 to 1975, ImageGeometry.shrinkStep(2000, 2000))
    }
}
