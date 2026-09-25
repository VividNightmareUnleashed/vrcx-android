package io.github.vrcxandroid.companion

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** One decoded frame (PROTOCOL.md §4). [wireBytes] is the full size on the wire, length prefix included. */
sealed class Frame {
    abstract val wireBytes: Int

    /** Type 0x01. [type] is the `t` field ("" when missing). */
    class Control(val type: String, val json: JsonObject, override val wireBytes: Int) : Frame()

    /** Type 0x02, body already inflated to exactly `len` bytes. */
    class Data(
        val name: String,
        val fileId: String,
        val offset: Long,
        val bytes: ByteArray,
        val compressed: Boolean,
        override val wireBytes: Int,
    ) : Frame()
}

/**
 * Length-prefixed frames:
 * ```
 * uint32 BE length (type + payload, <= 1 MiB) | uint8 type | payload
 * ```
 * Control payloads are UTF-8 JSON objects. Data payloads are `uint16 BE headerLength | header JSON | body`, where the
 * body is raw (`z=0`) or raw DEFLATE (`z=1`, RFC 1951, no zlib header) inflating to exactly `len` bytes.
 */
object FrameCodec {
    const val MAX_FRAME_LENGTH = 1_048_576
    const val TYPE_CONTROL = 0x01
    const val TYPE_DATA = 0x02
    const val LENGTH_PREFIX = 4

    /** Guard against decompression bombs: the companion sends chunks of at most 256 KiB. */
    const val MAX_RAW_DATA_LENGTH = 8 * 1024 * 1024

    // ---- Encoding ----

    fun encodeControl(message: JsonObject): ByteArray {
        val json = message.toString().toByteArray(Charsets.UTF_8)
        return frame(TYPE_CONTROL, json)
    }

    /**
     * Encodes a data frame. With [compress] the body is deflated and sent with `z=1` only when that is smaller than
     * the raw bytes, as the companion does.
     */
    fun encodeData(name: String, fileId: String, offset: Long, raw: ByteArray, compress: Boolean = true): ByteArray {
        var body = raw
        var z = 0
        if (compress && raw.isNotEmpty()) {
            val deflated = deflate(raw)
            if (deflated.size < raw.size) {
                body = deflated
                z = 1
            }
        }
        val header = buildJsonObject {
            put("name", name)
            put("fileId", fileId)
            put("offset", offset)
            put("len", raw.size)
            put("z", z)
        }.toString().toByteArray(Charsets.UTF_8)
        if (header.size > 0xFFFF) throw ProtocolException("data header too long")
        val payload = ByteArray(2 + header.size + body.size)
        payload[0] = (header.size ushr 8).toByte()
        payload[1] = header.size.toByte()
        header.copyInto(payload, 2)
        body.copyInto(payload, 2 + header.size)
        return frame(TYPE_DATA, payload)
    }

    private fun frame(type: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 1
        if (length > MAX_FRAME_LENGTH) throw ProtocolException("frame length $length exceeds $MAX_FRAME_LENGTH")
        val out = ByteArray(LENGTH_PREFIX + length)
        out[0] = (length ushr 24).toByte()
        out[1] = (length ushr 16).toByte()
        out[2] = (length ushr 8).toByte()
        out[3] = length.toByte()
        out[4] = type.toByte()
        payload.copyInto(out, 5)
        return out
    }

    // ---- Decoding ----

    /**
     * Reads one frame. Throws [EOFException] when the stream ends (cleanly or mid-frame) and [ProtocolException] for
     * an oversized length, an unknown frame type or a malformed payload; either way the caller closes the connection.
     */
    fun read(input: InputStream): Frame {
        val prefix = ByteArray(LENGTH_PREFIX)
        readFully(input, prefix)
        val length = ((prefix[0].toLong() and 0xFF) shl 24) or ((prefix[1].toLong() and 0xFF) shl 16) or
            ((prefix[2].toLong() and 0xFF) shl 8) or (prefix[3].toLong() and 0xFF)
        if (length < 1 || length > MAX_FRAME_LENGTH) throw ProtocolException("invalid frame length $length")
        val body = ByteArray(length.toInt())
        readFully(input, body)
        return decode(body[0].toInt() and 0xFF, body, 1, body.size - 1, LENGTH_PREFIX + body.size)
    }

    /** Decodes a whole encoded frame (length prefix included), for tests and tools. */
    fun decode(encoded: ByteArray): Frame = read(encoded.inputStream())

    private fun decode(type: Int, buf: ByteArray, off: Int, len: Int, wireBytes: Int): Frame = when (type) {
        TYPE_CONTROL -> {
            val json = parseJsonObject(buf, off, len, "control")
            Frame.Control(json.typeOrEmpty(), json, wireBytes)
        }
        TYPE_DATA -> decodeData(buf, off, len, wireBytes)
        else -> throw ProtocolException("unknown frame type $type")
    }

    private fun decodeData(buf: ByteArray, off: Int, len: Int, wireBytes: Int): Frame.Data {
        if (len < 2) throw ProtocolException("data frame too short")
        val headerLength = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
        if (headerLength > len - 2) throw ProtocolException("data header length $headerLength exceeds frame")
        val header = parseJsonObject(buf, off + 2, headerLength, "data header")
        val name = header.str("name") ?: throw ProtocolException("data header without name")
        val fileId = header.str("fileId") ?: throw ProtocolException("data header without fileId")
        val offset = header.long("offset") ?: throw ProtocolException("data header without offset")
        val rawLength = header.long("len") ?: throw ProtocolException("data header without len")
        val z = header.int("z") ?: 0
        if (offset < 0) throw ProtocolException("negative data offset")
        if (rawLength < 0 || rawLength > MAX_RAW_DATA_LENGTH) throw ProtocolException("invalid data len $rawLength")
        val bodyOffset = off + 2 + headerLength
        val bodyLength = len - 2 - headerLength
        val bytes = when (z) {
            0 -> {
                if (bodyLength.toLong() != rawLength) {
                    throw ProtocolException("raw data body is $bodyLength bytes, header says $rawLength")
                }
                buf.copyOfRange(bodyOffset, bodyOffset + bodyLength)
            }
            1 -> inflateExactly(buf, bodyOffset, bodyLength, rawLength.toInt())
            else -> throw ProtocolException("unknown data compression z=$z")
        }
        return Frame.Data(name, fileId, offset, bytes, z == 1, wireBytes)
    }

    private fun parseJsonObject(buf: ByteArray, off: Int, len: Int, what: String): JsonObject {
        val text = String(buf, off, len, Charsets.UTF_8)
        return try {
            CompanionJson.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ProtocolException("$what is not a JSON object")
    }

    // ---- DEFLATE (raw, RFC 1951) ----

    fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, raw.size / 4))
            val chunk = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** Inflates a raw DEFLATE stream that must produce exactly [expected] bytes and then end. */
    fun inflateExactly(buf: ByteArray, off: Int, len: Int, expected: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            // With nowrap, zlib may need one byte past the end of the stream to finish; a trailing dummy byte is
            // harmless (java.util.zip.Inflater documentation).
            val input = ByteArray(len + 1)
            System.arraycopy(buf, off, input, 0, len)
            inflater.setInput(input)
            val out = ByteArray(expected)
            var produced = 0
            while (produced < expected) {
                val n = inflater.inflate(out, produced, expected - produced)
                // With output space left, no progress means the stream ended, ran out of input or wants a dictionary.
                if (n == 0) throw ProtocolException("DEFLATE body inflated to $produced bytes, expected $expected")
                produced += n
            }
            if (!inflater.finished()) {
                val extra = inflater.inflate(ByteArray(1))
                if (extra > 0 || !inflater.finished()) {
                    throw ProtocolException("DEFLATE body inflates to more than $expected bytes")
                }
            }
            return out
        } catch (e: DataFormatException) {
            throw ProtocolException("invalid DEFLATE body: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) {
                throw if (read == 0 && target.size == LENGTH_PREFIX) EOFException("connection closed") else
                    EOFException("connection closed mid-frame")
            }
            read += n
        }
    }
}
