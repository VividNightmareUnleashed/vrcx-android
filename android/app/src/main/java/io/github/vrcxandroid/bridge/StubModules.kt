package io.github.vrcxandroid.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * `Discord`: Rich Presence has no Android equivalent. The shim answers
 * `SetActive`/`SetAssets` itself; these are the same values for calls that reach native anyway.
 */
class DiscordModule : BridgeModule {
    override val className = "Discord"

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = when (method) {
        // Returns the argument unchanged, so the frontend's state machine behaves as upstream.
        "SetActive" -> JsonPrimitive(args.bool(0) ?: false)
        // Host-only upstream; no-ops here.
        "SetAssets", "Init", "Exit", "Update" -> JsonNull
        else -> missingMethod(className, method)
    }
}

/**
 * `AssetBundleManager`: the VRChat PC cache is not reachable from a phone.
 * The cache methods return the "nothing cached" values; the pure helpers are ported as-is.
 */
class AssetBundleManagerModule : BridgeModule {
    override val className = "AssetBundleManager"

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = when (method) {
        "CheckVRChatCache" -> buildJsonObject {
            put("Item1", -1)
            put("Item2", false)
            put("Item3", "")
        }
        "GetVRChatCacheFullLocation", "GetVRChatCacheLocation" -> JsonPrimitive("")
        "DeleteCache", "DeleteAllCache" -> JsonNull
        "SweepCache" -> JsonArray(emptyList())
        "GetCacheSize", "DirSize" -> JsonPrimitive(0)
        "GetAssetId" -> JsonPrimitive(assetId(args.strOr(0, ""), args.strOr(1, "")))
        "GetAssetVersion" -> JsonPrimitive(assetVersion(args.int(0) ?: 0, args.int(1) ?: 0))
        "ReverseHexToDecimal" -> reverseHexToDecimal(args.strOr(0, "")).let { (version, variantVersion) ->
            buildJsonObject {
                put("Item1", version)
                put("Item2", variantVersion)
            }
        }
        else -> missingMethod(className, method)
    }

    companion object {
        /** Upper-case hex SHA-256 of UTF-8 `id + variant`, first 16 characters. */
        fun assetId(id: String, variant: String = ""): String {
            val hash = MessageDigest.getInstance("SHA-256").digest((id + variant).toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }.uppercase().substring(0, 16)
        }

        /** Little-endian bytes of `variantVersion` then `version` as hex, left-padded with zeros to 32, lower-case. */
        fun assetVersion(version: Int, variantVersion: Int = 0): String {
            val hex = littleEndianHex(variantVersion) + littleEndianHex(version)
            return hex.padStart(32, '0').lowercase()
        }

        /** Inverse used by upstream to sort cache folders: variant from chars 0-8, version from 24-32; (0, 0) if invalid. */
        fun reverseHexToDecimal(hex: String): Pair<Int, Int> {
            if (hex.length != 32) return 0 to 0
            return try {
                littleEndianInt(hex.substring(24, 32)) to littleEndianInt(hex.substring(0, 8))
            } catch (e: NumberFormatException) {
                0 to 0
            }
        }

        private fun littleEndianHex(value: Int): String =
            (0 until 4).joinToString("") { "%02X".format((value ushr (8 * it)) and 0xFF) }

        private fun littleEndianInt(hex: String): Int {
            var result = 0
            for (i in 0 until 4) result = result or (hex.substring(i * 2, i * 2 + 2).toInt(16) shl (8 * i))
            return result
        }
    }
}
