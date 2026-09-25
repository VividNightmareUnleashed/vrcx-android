package io.github.vrcxandroid.companion

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.random.Random

class FrameCodecTest {
    private fun header(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

    /** Builds a frame by hand, independent of the encoder. */
    private fun rawFrame(type: Int, payload: ByteArray, declaredLength: Int = payload.size + 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(declaredLength ushr 24)
        out.write(declaredLength ushr 16)
        out.write(declaredLength ushr 8)
        out.write(declaredLength)
        out.write(type)
        out.write(payload)
        return out.toByteArray()
    }

    private fun dataPayload(headerJson: String, body: ByteArray): ByteArray {
        val h = header(headerJson)
        val out = ByteArrayOutputStream()
        out.write(h.size ushr 8)
        out.write(h.size)
        out.write(h)
        out.write(body)
        return out.toByteArray()
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.java.simpleName}, got $t", t)
        }
        fail("expected ${T::class.java.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun controlFrameLayoutAndRoundTrip() {
        val msg = buildJsonObject {
            put("t", "hello")
            put("v", 1)
            put("name", "PC ä")
        }
        val encoded = FrameCodec.encodeControl(msg)
        val json = msg.toString().toByteArray(Charsets.UTF_8)
        assertEquals(4 + 1 + json.size, encoded.size)
        val length = ((encoded[0].toInt() and 0xFF) shl 24) or ((encoded[1].toInt() and 0xFF) shl 16) or
            ((encoded[2].toInt() and 0xFF) shl 8) or (encoded[3].toInt() and 0xFF)
        assertEquals(json.size + 1, length)
        assertEquals(0x01, encoded[4].toInt())

        val frame = FrameCodec.decode(encoded) as Frame.Control
        assertEquals("hello", frame.type)
        assertEquals(msg, frame.json)
        assertEquals(encoded.size, frame.wireBytes)
    }

    @Test
    fun controlWithoutTypeDecodesWithEmptyType() {
        val frame = FrameCodec.decode(rawFrame(1, header("{\"x\":1}"))) as Frame.Control
        assertEquals("", frame.type)
    }

    @Test
    fun rawDataRoundTrip() {
        val raw = "2024.01.01 10:00:00 Log        -  [Behaviour] Joining wrld_x\r\n".toByteArray()
        val encoded = FrameCodec.encodeData("output_log_2024-01-01_09-59-00.txt", "ab12", 1234, raw, compress = false)
        val frame = FrameCodec.decode(encoded) as Frame.Data
        assertEquals("output_log_2024-01-01_09-59-00.txt", frame.name)
        assertEquals("ab12", frame.fileId)
        assertEquals(1234L, frame.offset)
        assertFalse(frame.compressed)
        assertArrayEquals(raw, frame.bytes)
        assertEquals(encoded.size, frame.wireBytes)
    }

    @Test
    fun deflatedDataRoundTrip() {
        val line = "2024.01.01 10:00:00 Log        -  [Behaviour] OnPlayerJoined Someone (usr_1234)\n"
        val raw = line.repeat(3000).toByteArray()
        val encoded = FrameCodec.encodeData("f.txt", "id", 0, raw)
        val frame = FrameCodec.decode(encoded) as Frame.Data
        assertTrue(frame.compressed)
        assertTrue("compressed frame should be much smaller", encoded.size < raw.size / 5)
        assertArrayEquals(raw, frame.bytes)
    }

    @Test
    fun incompressibleDataIsSentRaw() {
        val raw = Random(7).nextBytes(8192)
        val frame = FrameCodec.decode(FrameCodec.encodeData("f", "id", 5, raw)) as Frame.Data
        assertFalse(frame.compressed)
        assertArrayEquals(raw, frame.bytes)
    }

    @Test
    fun emptyDataFrame() {
        val frame = FrameCodec.decode(FrameCodec.encodeData("f", "id", 42, ByteArray(0))) as Frame.Data
        assertEquals(0, frame.bytes.size)
        assertEquals(42L, frame.offset)
    }

    @Test
    fun decodesIndependentRawDeflateVector() {
        // "hello" as raw DEFLATE (RFC 1951, fixed Huffman block, no zlib header).
        val body = byteArrayOf(0xCB.toByte(), 0x48, 0xCD.toByte(), 0xC9.toByte(), 0xC9.toByte(), 0x07, 0x00)
        val payload = dataPayload("{\"name\":\"a\",\"fileId\":\"b\",\"offset\":0,\"len\":5,\"z\":1}", body)
        val frame = FrameCodec.decode(rawFrame(2, payload)) as Frame.Data
        assertEquals("hello", String(frame.bytes))
        assertTrue(frame.compressed)
    }

    @Test
    fun deflateOutputHasNoZlibHeader() {
        val deflated = FrameCodec.deflate("hello hello hello hello".toByteArray())
        // A zlib stream would start with 0x78; raw DEFLATE starts with a block header.
        assertFalse((deflated[0].toInt() and 0xFF) == 0x78 && ((deflated[0].toInt() and 0xFF) * 256 + (deflated[1].toInt() and 0xFF)) % 31 == 0)
        val back = FrameCodec.inflateExactly(deflated, 0, deflated.size, 23)
        assertEquals("hello hello hello hello", String(back))
    }

    @Test
    fun deflateLengthMismatchIsRejected() {
        val body = FrameCodec.deflate("hello".toByteArray())
        val tooShort = dataPayload("{\"name\":\"a\",\"fileId\":\"b\",\"offset\":0,\"len\":6,\"z\":1}", body)
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(2, tooShort)) }
        val tooLong = dataPayload("{\"name\":\"a\",\"fileId\":\"b\",\"offset\":0,\"len\":4,\"z\":1}", body)
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(2, tooLong)) }
        val garbage = dataPayload("{\"name\":\"a\",\"fileId\":\"b\",\"offset\":0,\"len\":4,\"z\":1}", byteArrayOf(-1, -1, -1))
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(2, garbage)) }
    }

    @Test
    fun rawBodyLengthMismatchIsRejected() {
        val payload = dataPayload("{\"name\":\"a\",\"fileId\":\"b\",\"offset\":0,\"len\":3,\"z\":0}", "abcd".toByteArray())
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(2, payload)) }
    }

    @Test
    fun malformedDataHeadersAreRejected() {
        assertThrows<ProtocolException> {
            FrameCodec.decode(rawFrame(2, dataPayload("{\"fileId\":\"b\",\"offset\":0,\"len\":0,\"z\":0}", ByteArray(0))))
        }
        assertThrows<ProtocolException> {
            FrameCodec.decode(rawFrame(2, dataPayload("{\"name\":\"a\",\"fileId\":\"b\",\"offset\":-1,\"len\":0,\"z\":0}", ByteArray(0))))
        }
        assertThrows<ProtocolException> {
            FrameCodec.decode(rawFrame(2, dataPayload("{\"name\":\"a\",\"fileId\":\"b\",\"offset\":0,\"len\":0,\"z\":2}", ByteArray(0))))
        }
        // header length pointing past the frame
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(2, byteArrayOf(0x10, 0x00, '{'.code.toByte()))) }
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(2, byteArrayOf(0x00))) }
    }

    @Test
    fun invalidControlJsonIsRejected() {
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(1, header("[1,2]"))) }
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(1, header("{not json"))) }
    }

    @Test
    fun unknownFrameTypeIsRejected() {
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(3, header("{}"))) }
        assertThrows<ProtocolException> { FrameCodec.decode(rawFrame(0, header("{}"))) }
    }

    @Test
    fun oneMebibyteLimit() {
        // Largest legal control frame: length field exactly 1 048 576.
        val overhead = buildJsonObject {
            put("t", "x")
            put("p", "")
        }.toString().length
        val filler = "a".repeat(FrameCodec.MAX_FRAME_LENGTH - 1 - overhead)
        val max = buildJsonObject {
            put("t", "x")
            put("p", filler)
        }
        val encoded = FrameCodec.encodeControl(max)
        assertEquals(4 + FrameCodec.MAX_FRAME_LENGTH, encoded.size)
        val decoded = FrameCodec.decode(encoded) as Frame.Control
        assertEquals(JsonPrimitive(filler), decoded.json["p"])

        val over = buildJsonObject {
            put("t", "x")
            put("p", filler + "a")
        }
        assertThrows<ProtocolException> { FrameCodec.encodeControl(over) }

        // The decoder refuses a length above the limit before reading the body.
        val prefixOnly = rawFrame(1, ByteArray(0), declaredLength = FrameCodec.MAX_FRAME_LENGTH + 1)
        assertThrows<ProtocolException> { FrameCodec.read(ByteArrayInputStream(prefixOnly)) }
        assertThrows<ProtocolException> { FrameCodec.read(ByteArrayInputStream(rawFrame(1, ByteArray(0), 0))) }

        // A data frame of exactly the limit (raw body) round-trips.
        val headerLen = buildJsonObject {
            put("name", "f")
            put("fileId", "i")
            put("offset", 0)
            put("len", 0)
            put("z", 0)
        }.toString().length
        var bodySize = FrameCodec.MAX_FRAME_LENGTH - 1 - 2 - headerLen - 6 // "len" grows from 1 to 7 digits
        var data = FrameCodec.encodeData("f", "i", 0, ByteArray(bodySize) { (it % 251).toByte() }, compress = false)
        bodySize += 4 + FrameCodec.MAX_FRAME_LENGTH - data.size
        data = FrameCodec.encodeData("f", "i", 0, ByteArray(bodySize) { (it % 251).toByte() }, compress = false)
        assertEquals(4 + FrameCodec.MAX_FRAME_LENGTH, data.size)
        assertEquals(bodySize, (FrameCodec.decode(data) as Frame.Data).bytes.size)
        assertThrows<ProtocolException> {
            FrameCodec.encodeData("f", "i", 0, ByteArray(bodySize + 1), compress = false)
        }
    }

    @Test
    fun endOfStream() {
        assertThrows<EOFException> { FrameCodec.read(ByteArrayInputStream(ByteArray(0))) }
        val encoded = FrameCodec.encodeControl(buildJsonObject { put("t", "ping") })
        assertThrows<EOFException> { FrameCodec.read(ByteArrayInputStream(encoded.copyOf(encoded.size - 1))) }
        assertThrows<EOFException> { FrameCodec.read(ByteArrayInputStream(encoded.copyOf(2))) }
    }

    @Test
    fun consecutiveFramesInOneStream() {
        val out = ByteArrayOutputStream()
        out.write(FrameCodec.encodeControl(buildJsonObject { put("t", "snapshot") }))
        out.write(FrameCodec.encodeData("a", "1", 0, "abc".toByteArray()))
        out.write(FrameCodec.encodeControl(buildJsonObject { put("t", "syncComplete") }))
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals("snapshot", (FrameCodec.read(input) as Frame.Control).type)
        assertEquals("abc", String((FrameCodec.read(input) as Frame.Data).bytes))
        assertEquals("syncComplete", (FrameCodec.read(input) as Frame.Control).type)
        assertThrows<EOFException> { FrameCodec.read(input) }
    }
}
