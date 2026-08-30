package com.vrcx.android.data.screenshot

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal object VrchatXmpParser {
    private val valueNames = setOf(
        "Author",
        "AuthorID",
        "CreatorTool",
        "DateTime",
        "World",
        "WorldDisplayName",
        "WorldID",
        "li",
    )

    fun parse(xml: String): ScreenshotMetadata? = xml.xmpDocumentOrNull()
        ?.let { document ->
            runCatching {
                secureXmlPullParser().apply { setInput(StringReader(document)) }.readValues()
            }.getOrNull()
        }
        ?.toMetadata()

    private fun String.xmpDocumentOrNull(): String? = when {
        contains("<!DOCTYPE", ignoreCase = true) -> null
        else -> indexOf("<x:xmpmeta").takeIf { it >= 0 }?.let(::substring)
    }

    private fun secureXmlPullParser(): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().apply {
        // Pull parsing does not validate schemas or process XInclude. Keeping
        // DOCDECL disabled also prevents entity declarations and external DTDs.
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        check(getFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES))
        check(!getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL))
        check(!getFeature(XmlPullParser.FEATURE_VALIDATION))
    }

    private fun XmlPullParser.readValues(): Map<String, String> {
        val values = mutableMapOf<String, String>()
        while (next() != XmlPullParser.END_DOCUMENT) {
            check(eventType != XmlPullParser.DOCDECL) { "DOCTYPE declarations are not allowed" }
            if (eventType == XmlPullParser.START_TAG && name in valueNames) {
                nextText().trim().takeIf(String::isNotEmpty)?.let { value ->
                    values.putIfAbsent(name, value)
                }
            }
        }
        return values
    }

    private fun Map<String, String>.toMetadata(): ScreenshotMetadata {
        val authorName = this["Author"]
        val authorId = this["AuthorID"]
        val worldId = this["WorldID"] ?: this["World"].orEmpty()
        return ScreenshotMetadata(
            application = this["CreatorTool"],
            version = 1,
            author = ScreenshotAuthor(
                id = authorId ?: authorName.orEmpty(),
                displayName = if (authorId != null) authorName else null,
            ),
            world = ScreenshotWorld(
                id = worldId,
                name = this["WorldDisplayName"],
                instanceId = worldId,
            ),
            timestamp = this["DateTime"],
            note = this["li"],
        )
    }
}
