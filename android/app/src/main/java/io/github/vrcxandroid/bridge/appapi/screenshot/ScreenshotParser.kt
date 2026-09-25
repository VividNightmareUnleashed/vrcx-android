package io.github.vrcxandroid.bridge.appapi.screenshot

import android.util.Log
import io.github.vrcxandroid.bridge.appapi.NetDateTime
import io.github.vrcxandroid.bridge.appapi.docs.Doc
import io.github.vrcxandroid.bridge.appapi.png.MemorySeekableStream
import io.github.vrcxandroid.bridge.appapi.png.PngChunkType
import io.github.vrcxandroid.bridge.appapi.png.PngFile
import io.github.vrcxandroid.bridge.appapi.png.PngHelper
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory

/** Port of upstream `ScreenshotHelper` (Dotnet/ScreenshotMetadata/ScreenshotHelper.cs), reading from a [Doc]. */
object ScreenshotParser {
    private const val TAG = "VRCXScreenshot"
    const val PARSE_ERROR = "Failed to parse metadata. Check log file for details."
    const val NO_METADATA = "Image has no valid metadata."

    private const val NS_RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val NS_XMP = "http://ns.adobe.com/xap/1.0/"
    private const val NS_TIFF = "http://ns.adobe.com/tiff/1.0/"
    private const val NS_DC = "http://purl.org/dc/elements/1.1/"
    private const val NS_VRC = "http://ns.vrchat.com/vrc/1.0/"

    /**
     * `ScreenshotHelper.GetScreenshotMetadata(path)`: null when the file does not exist or is not a `.png`; an error
     * object when the text chunks cannot be parsed or none is usable. [sourcePath] is echoed as `sourceFile`.
     */
    fun getScreenshotMetadata(doc: Doc, sourcePath: String, zone: ZoneId = ZoneId.systemDefault()): ScreenshotMetadata? {
        if (!doc.exists() || !doc.name.endsWith(".png")) return null
        val metadata = readTextMetadata(doc)
        var result = ScreenshotMetadata()
        for (text in metadata) {
            var gotVrchatMetadata = false
            try {
                if (text.startsWith("<x:xmpmeta")) {
                    result = parseVrcImage(text, zone)
                    result.sourceFile = sourcePath
                    gotVrchatMetadata = true
                }
                if (text.startsWith("{") && text.endsWith("}")) {
                    val vrcx = ScreenshotMetadata.fromJson(text, zone)
                    if (vrcx != null) {
                        vrcx.sourceFile = sourcePath
                        if (gotVrchatMetadata) {
                            result.players = vrcx.players
                            result.world!!.instanceId = vrcx.world!!.instanceId
                        } else {
                            result = vrcx
                        }
                    }
                }
                if (text.startsWith("lfs") || text.startsWith("screenshotmanager")) {
                    result = parseLfsPicture(text)
                    result.sourceFile = sourcePath
                }
            } catch (e: Exception) {
                logError("Failed to parse metadata for file '${doc.key}'", e)
                return ScreenshotMetadata.justError(sourcePath, PARSE_ERROR)
            }
        }
        if (result.application == null || metadata.isEmpty()) {
            return ScreenshotMetadata.justError(sourcePath, NO_METADATA)
        }
        return result
    }

    /**
     * Text chunks with metadata: the VRChat XMP first, then the VRCX description; legacy mods' trailing chunks are only
     * searched when neither exists and the file has an sRGB (or unknown) chunk.
     */
    fun readTextMetadata(doc: Doc): List<String> = doc.openRead().use { stream ->
        val png = PngFile(stream)
        val result = mutableListOf<String>()
        val description = PngHelper.readTextChunk("Description", png)
        val vrchat = PngHelper.readTextChunk("XML:com.adobe.xmp", png)
        if (!vrchat.isNullOrEmpty()) result += vrchat
        if (!description.isNullOrEmpty()) result += description
        if (result.isEmpty() && png.getChunk(PngChunkType.sRGB) != null) {
            val lfs = PngHelper.readTextChunk("Description", png, legacySearch = true)
            if (!lfs.isNullOrEmpty()) result += lfs
        }
        result
    }

    /**
     * `DeleteTextMetadata(path, deleteVRChatMetadata)` on file bytes. Returns the new bytes, or null when nothing was
     * removed (the file is left untouched then).
     */
    fun deleteTextMetadata(bytes: ByteArray, deleteVrchatMetadata: Boolean = true): ByteArray? {
        val stream = MemorySeekableStream(bytes)
        val png = PngFile(stream)
        var changed = false
        if (deleteVrchatMetadata) changed = PngHelper.deleteTextChunk("XML:com.adobe.xmp", png)
        changed = PngHelper.deleteTextChunk("Description", png) || changed
        return if (changed) stream.toByteArray() else null
    }

    /** `WriteVRCXMetadata`: inserts a "Description" iTXt chunk. Returns the new bytes, or null when it cannot. */
    fun writeVrcxMetadata(text: String, bytes: ByteArray): ByteArray? {
        val stream = MemorySeekableStream(bytes)
        val ok = PngFile(stream).writeChunk(PngHelper.generateTextChunk("Description", text))
        return if (ok) stream.toByteArray() else null
    }

    fun parseVrcImage(xml: String, zone: ZoneId = ZoneId.systemDefault()): ScreenshotMetadata {
        val index = xml.indexOf("<x:xmpmeta")
        val doc = parseXml(xml.substring(index))
        fun first(ns: String, local: String): String? = doc.getElementsByTagNameNS(ns, local).item(0)?.textContent

        val creatorTool = first(NS_XMP, "CreatorTool")
        var authorName = first(NS_XMP, "Author")
        val dateTime = first(NS_TIFF, "DateTime")
        val note = titleNote(doc)
        var worldId = first(NS_VRC, "WorldID")
        val worldDisplayName = first(NS_VRC, "WorldDisplayName")
        var authorId = first(NS_VRC, "AuthorID")
        if (worldId.isNullOrEmpty()) worldId = first(NS_VRC, "World")
        if (authorId.isNullOrEmpty()) {
            // legacy format: xmp:Author held the author id
            authorId = authorName
            authorName = null
        }
        return ScreenshotMetadata().apply {
            application = creatorTool
            version = 1
            author = ScreenshotMetadata.AuthorDetail(authorId, authorName)
            world = ScreenshotMetadata.WorldDetail(id = worldId, name = worldDisplayName, instanceId = worldId)
            timestamp = NetDateTime.tryParse(dateTime, zone)
            this.note = note
        }
    }

    /** `//dc:title/rdf:Alt/rdf:li` */
    private fun titleNote(doc: Document): String? {
        val items = doc.getElementsByTagNameNS(NS_RDF, "li")
        for (i in 0 until items.length) {
            val li = items.item(i)
            val alt = li.parentNode as? Element ?: continue
            if (alt.namespaceURI != NS_RDF || alt.localName != "Alt") continue
            val title = alt.parentNode as? Element ?: continue
            if (title.namespaceURI == NS_DC && title.localName == "title") return li.textContent
        }
        return null
    }

    private fun parseXml(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        for (feature in listOf(
            "http://apache.org/xml/features/disallow-doctype-decl",
            "http://javax.xml.XMLConstants/feature/secure-processing",
        )) {
            try {
                factory.setFeature(feature, true)
            } catch (e: Exception) {
                // not supported by this parser
            }
        }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) {}
            override fun error(exception: SAXParseException) = throw exception
            override fun fatalError(exception: SAXParseException) = throw exception
        })
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(StringReader(xml)))
    }

    /** Port of `ParseLfsPicture` (LFS v1/v2, LFS CVR edition, ScreenshotManager). Index errors throw like upstream. */
    fun parseLfsPicture(metadataString: String): ScreenshotMetadata {
        val metadata = ScreenshotMetadata()
        var lfsParts = metadataString.split('|')
        if (lfsParts[1] == "cvr") lfsParts = lfsParts.drop(1)

        val version = parseNetInt(lfsParts[1])
        val application = lfsParts[0]
        metadata.application = application
        metadata.version = version
        val isCvr = application == "cvr"

        if (application == "screenshotmanager") {
            val author = lfsParts[2].split(',')
            metadata.author!!.id = author[0]
            metadata.author!!.displayName = author[1]
            val world = lfsParts[3].split(',')
            metadata.world!!.id = world[0]
            metadata.world!!.name = world[2]
            metadata.world!!.instanceId = world[0] + ":" + world[1]
            return metadata
        }

        for (i in 2 until lfsParts.size) {
            val split = lfsParts[i].split(':')
            val key = split[0]
            val value = split[1]
            if (value.isEmpty()) continue
            val parts = value.split(',')
            when (key) {
                "author" -> {
                    metadata.author!!.id = if (isCvr) "" else parts[0]
                    metadata.author!!.displayName = if (isCvr) "${parts[1]} (${parts[0]})" else parts[1]
                }
                "world" -> {
                    metadata.world!!.id = if (isCvr || version == 1) "" else parts[0]
                    metadata.world!!.instanceId = if (isCvr || version == 1) "" else parts[0] + ":" + parts[1]
                    metadata.world!!.name = if (isCvr) "${parts[2]} (${parts[0]})" else if (version == 1) value else parts[2]
                }
                "pos" -> {
                    val x = parseNetFloat(parts[0])
                    val y = parseNetFloat(parts[1])
                    val z = parseNetFloat(parts[2])
                    metadata.pos = ScreenshotMetadata.Vec3(x, y, z)
                }
                "players" -> {
                    val list = metadata.players!!
                    for (player in value.split(';')) {
                        val p = player.split(',')
                        val x2 = parseNetFloat(p[1])
                        val y2 = parseNetFloat(p[2])
                        val z2 = parseNetFloat(p[3])
                        list += ScreenshotMetadata.PlayerDetail(
                            id = if (isCvr) "" else p[0],
                            displayName = if (isCvr) "${p[4]} (${p[0]})" else p[4],
                            pos = ScreenshotMetadata.Vec3(x2, y2, z2),
                        )
                    }
                }
            }
        }
        return metadata
    }

    private val netFloat = Regex("^\\s*[+-]?(?:\\d[\\d,]*)?(?:\\.\\d*)?(?:[eE][+-]?\\d+)?\\s*$")

    /** `float.TryParse` (en-US, `NumberStyles.Float | AllowThousands`); 0 when it does not parse. */
    fun parseNetFloat(text: String): Float {
        val t = text.trim()
        when (t.lowercase()) {
            "nan" -> return Float.NaN
            "infinity", "+infinity" -> return Float.POSITIVE_INFINITY
            "-infinity" -> return Float.NEGATIVE_INFINITY
        }
        if (!netFloat.matches(t) || t.none { it.isDigit() }) return 0f
        val mantissaDigits = t.substringBefore('e').substringBefore('E')
        if (mantissaDigits.none { it.isDigit() }) return 0f
        return t.replace(",", "").toFloatOrNull() ?: 0f
    }

    /** `int.Parse` (`NumberStyles.Integer`): surrounding white space and a leading sign are allowed. */
    fun parseNetInt(text: String): Int {
        val t = text.trim()
        if (!Regex("^[+-]?\\d+$").matches(t)) throw NumberFormatException("The input string '$text' was not in a correct format.")
        return t.toInt()
    }

    private fun logError(message: String, e: Exception) {
        try {
            Log.w(TAG, message, e)
        } catch (ignored: RuntimeException) {
            // android.util.Log is not available in JVM unit tests
        }
    }
}
