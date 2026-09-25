package io.github.vrcxandroid.bridge.appapi.screenshot

import io.github.vrcxandroid.bridge.BridgeJson
import io.github.vrcxandroid.bridge.appapi.docs.Doc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Cache of the fields `FindScreenshotsBySearch` matches on, keyed by document and invalidated by size and modification
 * time (upstream keeps a `metadataCache.db` keyed by path). Persisted to one small JSON file so repeated searches over
 * a large photo folder do not reopen every PNG.
 */
class ScreenshotSearchIndex(private val file: File) {
    class Record(
        val stamp: String,
        val valid: Boolean,
        val playerIds: List<String?>,
        val playerNames: List<String?>,
        val worldId: String?,
        val worldName: String?,
    ) {
        /** The four upstream search types: 0 user name, 1 user id, 2 world name, 3 world id. */
        fun matches(query: String, searchType: Int): Boolean {
            if (!valid) return false
            return when (searchType) {
                0 -> playerNames.any { it != null && it.contains(query, ignoreCase = true) }
                1 -> playerIds.any { it == query }
                2 -> worldName != null && worldName.contains(query, ignoreCase = true)
                3 -> worldId == query
                else -> false
            }
        }

        companion object {
            fun of(stamp: String, metadata: ScreenshotMetadata?): Record {
                if (metadata == null || metadata.error != null) return Record(stamp, false, emptyList(), emptyList(), null, null)
                val players = metadata.players.orEmpty()
                return Record(
                    stamp,
                    true,
                    players.map { it.id },
                    players.map { it.displayName },
                    metadata.world?.id,
                    metadata.world?.name,
                )
            }
        }
    }

    private var records: MutableMap<String, Record>? = null
    private var dirty = false

    fun stampOf(doc: Doc): String = "${doc.length()}:${doc.lastModified()}"

    @Synchronized
    fun get(doc: Doc): Record? {
        val record = load()[doc.key] ?: return null
        return if (record.stamp == stampOf(doc)) record else null
    }

    @Synchronized
    fun put(doc: Doc, record: Record) {
        load()[doc.key] = record
        dirty = true
    }

    /** Drops entries for documents that were not part of the last full listing, then writes the file if needed. */
    @Synchronized
    fun save(seenKeys: Set<String>) {
        val map = load()
        if (map.keys.retainAll(seenKeys)) dirty = true
        if (!dirty) return
        val json = buildJsonObject {
            put("v", 1)
            put(
                "records",
                JsonObject(
                    map.mapValues { (_, r) ->
                        buildJsonObject {
                            put("s", r.stamp)
                            put("ok", r.valid)
                            put("pi", JsonArray(r.playerIds.map { if (it == null) JsonNull else JsonPrimitive(it) }))
                            put("pn", JsonArray(r.playerNames.map { if (it == null) JsonNull else JsonPrimitive(it) }))
                            put("wi", r.worldId)
                            put("wn", r.worldName)
                        }
                    },
                ),
            )
        }
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            dirty = false
        } catch (e: Exception) {
            // a cache: losing it only costs time
        }
    }

    private fun load(): MutableMap<String, Record> {
        records?.let { return it }
        val map = HashMap<String, Record>()
        try {
            if (file.isFile) {
                val root = BridgeJson.parseToJsonElement(file.readText()).jsonObject
                for ((key, value) in root["records"]?.jsonObject.orEmpty()) {
                    val o = value.jsonObject
                    map[key] = Record(
                        stamp = o["s"]?.jsonPrimitive?.contentOrNull ?: continue,
                        valid = o["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
                        playerIds = o["pi"]?.jsonArray?.map { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                        playerNames = o["pn"]?.jsonArray?.map { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                        worldId = (o["wi"] as? JsonPrimitive)?.contentOrNull,
                        worldName = (o["wn"] as? JsonPrimitive)?.contentOrNull,
                    )
                }
            }
        } catch (e: Exception) {
            map.clear()
        }
        records = map
        return map
    }
}
