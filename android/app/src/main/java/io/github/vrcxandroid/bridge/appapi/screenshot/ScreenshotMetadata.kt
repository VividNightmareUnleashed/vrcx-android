package io.github.vrcxandroid.bridge.appapi.screenshot

import io.github.vrcxandroid.bridge.appapi.NJ
import io.github.vrcxandroid.bridge.appapi.NetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.ZoneId

/**
 * Port of upstream `ScreenshotMetadata` (Dotnet/ScreenshotMetadata/ScreenshotMetadata.cs), including how Newtonsoft
 * reads it from the VRCX JSON description chunk and writes it back (camelCase, indented, `sourceFile` first because
 * it is a field).
 */
class ScreenshotMetadata {
    var application: String? = "VRCX"
    var version: Int = 1
    var author: AuthorDetail? = AuthorDetail()
    var world: WorldDetail? = WorldDetail()
    var players: MutableList<PlayerDetail>? = mutableListOf()
    var sourceFile: String? = null
    var pos: Vec3? = null
    var timestamp: NetDateTime? = null
    var note: String? = null

    /** Parse error; when set nothing else is meaningful. Not serialized. */
    var error: String? = null

    class AuthorDetail(var id: String? = null, var displayName: String? = null)
    class WorldDetail(var id: String? = null, var name: String? = null, var instanceId: String? = null)
    class PlayerDetail(var id: String? = null, var displayName: String? = null, var pos: Vec3? = null)
    data class Vec3(val x: Float, val y: Float, val z: Float)

    fun containsPlayerId(id: String): Boolean = players.orEmpty().any { it.id == id }

    /** Partial, case-insensitive display-name match (upstream `ContainsPlayerName(name, true, true)`). */
    fun containsPlayerName(name: String): Boolean =
        players.orEmpty().any { it.displayName?.contains(name, ignoreCase = true) == true }

    /** Newtonsoft serialization with the camelCase contract resolver used by `AppApi.GetScreenshotMetadata`. */
    fun toJsonTree(): NJ.Obj {
        val o = NJ.Obj()
        sourceFile?.let { o.put("sourceFile", it) }
        o.put("application", application)
        o["version"] = NJ.Int(version.toLong())
        o["author"] = author?.let { a -> NJ.Obj().also { it.put("id", a.id); it.put("displayName", a.displayName) } } ?: NJ.Null
        o["world"] = world?.let { w ->
            NJ.Obj().also {
                it.put("id", w.id)
                it.put("name", w.name)
                it.put("instanceId", w.instanceId)
            }
        } ?: NJ.Null
        o["players"] = players?.let { list ->
            NJ.Arr(
                list.mapTo(mutableListOf()) { p ->
                    NJ.Obj().also {
                        it.put("id", p.id)
                        it.put("displayName", p.displayName)
                        p.pos?.let { v -> it["pos"] = vecTree(v) }
                    }
                },
            )
        } ?: NJ.Null
        pos?.let { o["pos"] = vecTree(it) }
        timestamp?.let { o.put("timestamp", it.format()) }
        note?.let { o.put("note", it) }
        return o
    }

    companion object {
        fun justError(sourceFile: String, error: String) = ScreenshotMetadata().also {
            it.error = error
            it.sourceFile = sourceFile
        }

        private fun vecTree(v: Vec3) = NJ.Obj().also {
            it["x"] = NJ.Flt(v.x)
            it["y"] = NJ.Flt(v.y)
            it["z"] = NJ.Flt(v.z)
        }

        private val lenientJson = Json { isLenient = true }

        /**
         * `JsonConvert.DeserializeObject<ScreenshotMetadata>(text)`: case-insensitive member names, unknown members
         * ignored, type mismatches throw.
         */
        fun fromJson(text: String, zone: ZoneId = ZoneId.systemDefault()): ScreenshotMetadata? {
            val root = lenientJson.parseToJsonElement(text)
            if (root is JsonNull) return null
            val obj = root as? JsonObject ?: throw IllegalArgumentException("Cannot deserialize the current JSON array")
            val m = ScreenshotMetadata()
            for ((key, value) in obj) {
                when (key.lowercase()) {
                    "application" -> m.application = string(value)
                    "version" -> m.version = int(value)
                    "author" -> m.author = (value.objOrNull())?.let { a ->
                        AuthorDetail().also { d ->
                            for ((k, v) in a) {
                                when (k.lowercase()) {
                                    "id" -> d.id = string(v)
                                    "displayname" -> d.displayName = string(v)
                                }
                            }
                        }
                    }
                    "world" -> m.world = (value.objOrNull())?.let { w ->
                        WorldDetail().also { d ->
                            for ((k, v) in w) {
                                when (k.lowercase()) {
                                    "id" -> d.id = string(v)
                                    "name" -> d.name = string(v)
                                    "instanceid" -> d.instanceId = string(v)
                                }
                            }
                        }
                    }
                    "players" -> m.players = when (value) {
                        is JsonNull -> null
                        is JsonArray -> (m.players ?: mutableListOf()).also { list ->
                            for (item in value) {
                                if (item is JsonNull) {
                                    throw IllegalArgumentException("null player")
                                }
                                val p = item as? JsonObject ?: throw IllegalArgumentException("player is not an object")
                                list += PlayerDetail().also { d ->
                                    for ((k, v) in p) {
                                        when (k.lowercase()) {
                                            "id" -> d.id = string(v)
                                            "displayname" -> d.displayName = string(v)
                                            "pos" -> d.pos = vec(v)
                                        }
                                    }
                                }
                            }
                        }
                        else -> throw IllegalArgumentException("players is not an array")
                    }
                    "sourcefile" -> m.sourceFile = string(value)
                    "pos" -> m.pos = vec(value)
                    "timestamp" -> m.timestamp = when (value) {
                        is JsonNull -> null
                        is JsonPrimitive -> NetDateTime.parseJson(value.content, zone)
                            ?: NetDateTime.tryParse(value.content, zone)
                            ?: throw IllegalArgumentException("String was not recognized as a valid DateTime.")
                        else -> throw IllegalArgumentException("timestamp is not a string")
                    }
                    "note" -> m.note = string(value)
                }
            }
            return m
        }

        private fun JsonElement.objOrNull(): JsonObject? = when (this) {
            is JsonNull -> null
            is JsonObject -> this
            else -> throw IllegalArgumentException("expected an object")
        }

        private fun string(e: JsonElement): String? = when (e) {
            is JsonNull -> null
            is JsonPrimitive -> e.content
            else -> throw IllegalArgumentException("expected a string")
        }

        private fun int(e: JsonElement): Int {
            val p = e as? JsonPrimitive ?: throw IllegalArgumentException("expected an integer")
            if (p is JsonNull) throw IllegalArgumentException("Error converting value {null} to type 'System.Int32'.")
            p.longOrNull?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) return it.toInt() }
            val d = p.doubleOrNull
            if (d != null && d == Math.floor(d) && d in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) return d.toInt()
            throw IllegalArgumentException("Could not convert to integer: ${p.contentOrNull}")
        }

        private fun float(e: JsonElement?): Float {
            if (e == null || e is JsonNull) return 0f
            val p = e as? JsonPrimitive ?: throw IllegalArgumentException("expected a number")
            return p.content.toFloatOrNull() ?: throw IllegalArgumentException("Could not convert to float: ${p.content}")
        }

        private fun vec(e: JsonElement): Vec3? {
            val o = e.objOrNull() ?: return null
            var x = 0f
            var y = 0f
            var z = 0f
            for ((k, v) in o) {
                when (k.lowercase()) {
                    "x" -> x = float(v)
                    "y" -> y = float(v)
                    "z" -> z = float(v)
                }
            }
            return Vec3(x, y, z)
        }
    }
}
