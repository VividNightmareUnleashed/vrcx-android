package io.github.vrcxandroid.companion

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The store cannot be read or written right now; a later call may succeed. Nothing is known about its content. */
class SecureStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Small key/value store for secrets. Android: EncryptedSharedPreferences ([EncryptedPrefsSecureStore]); JVM tests:
 * [InMemorySecureStore].
 */
interface SecureStore {
    /** The value of [key], or null when it is absent. Throws [SecureStoreException] when it cannot be read now. */
    fun get(key: String): String?

    /**
     * Stores [value], or removes the key when it is null. Writes are durable when this returns; throws
     * [SecureStoreException] when the store cannot be written now.
     */
    fun put(key: String, value: String?)
}

class InMemorySecureStore : SecureStore {
    private val map = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/**
 * One paired companion. [token] is secret and never leaves native code. [machineName] and [tz] are the last values the
 * companion reported in `info`, kept so the settings page can show them while disconnected.
 */
data class PairedCompanion(
    val id: String,
    val name: String,
    val fp: String,
    val token: String,
    val hosts: List<String>,
    val port: Int,
    val pairedAt: Long,
    val lastSeen: Long,
    val machineName: String? = null,
    val tz: JsonObject? = null,
) {
    /** Entry of `state().paired` (ARCHITECTURE.md §5.1): no token. */
    fun toPublicJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("hosts", buildJsonArray { hosts.forEach { add(JsonPrimitive(it)) } })
        put("port", port)
        put("fp", fp)
        put("pairedAt", pairedAt)
        put("lastSeen", lastSeen)
    }

    internal fun toStoredJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("fp", fp)
        put("token", token)
        put("hosts", buildJsonArray { hosts.forEach { add(JsonPrimitive(it)) } })
        put("port", port)
        put("pairedAt", pairedAt)
        put("lastSeen", lastSeen)
        put("machineName", jsonOrNull(machineName))
        if (tz != null) put("tz", tz)
    }

    /** Moves [host] to the front of [hosts] (the address that worked last is tried first). */
    fun withPreferredHost(host: String): PairedCompanion =
        if (hosts.firstOrNull() == host) this
        else copy(hosts = (listOf(host) + hosts.filter { it != host }).take(MAX_HOSTS))

    companion object {
        /** Addresses remembered per companion (newest first); older DHCP leases fall off the end. */
        const val MAX_HOSTS = 8

        internal fun fromStoredJson(json: JsonObject): PairedCompanion? {
            val id = json.str("id")?.takeIf(CompanionProtocol::isSafeId) ?: return null
            val fp = json.str("fp") ?: return null
            val token = json.str("token") ?: return null
            return PairedCompanion(
                id = id,
                name = json.str("name").orEmpty(),
                fp = fp,
                token = token,
                hosts = (json["hosts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                port = json.int("port") ?: CompanionProtocol.DEFAULT_TCP_PORT,
                pairedAt = json.long("pairedAt") ?: 0L,
                lastSeen = json.long("lastSeen") ?: 0L,
                machineName = json.str("machineName"),
                tz = json.obj("tz"),
            )
        }
    }
}

/**
 * Everything the client persists: pairings, the active companion and this phone's device id.
 *
 * [load] lets a [SecureStoreException] through: a store that cannot be read now is never mistaken for an empty one, so
 * no new device id replaces the stored one (which would break every pairing) and nothing is overwritten.
 */
class PairingRepository(private val store: SecureStore) {
    data class Snapshot(val records: List<PairedCompanion>, val activeId: String?, val deviceId: String)

    /** @throws SecureStoreException when the store cannot be read now */
    fun load(): Snapshot {
        val rawPaired = store.get(KEY_PAIRED)
        val storedDeviceId = store.get(KEY_DEVICE_ID)
        val storedActive = store.get(KEY_ACTIVE)
        val records = try {
            (rawPaired?.let { CompanionJson.parseToJsonElement(it) } as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(PairedCompanion::fromStoredJson) }
                .orEmpty()
        } catch (e: Exception) {
            // Readable but not valid JSON: it will never parse, so the next save replaces it.
            Log.w(TAG, "stored pairings unreadable, starting empty")
            emptyList()
        }
        val deviceId = storedDeviceId?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { store.put(KEY_DEVICE_ID, it) }
        val activeId = storedActive?.takeIf { id -> records.any { it.id == id } } ?: records.firstOrNull()?.id
        return Snapshot(records, activeId, deviceId)
    }

    fun save(records: List<PairedCompanion>, activeId: String?) {
        store.put(KEY_PAIRED, JsonArray(records.map { it.toStoredJson() }).toString())
        store.put(KEY_ACTIVE, activeId)
    }

    companion object {
        private const val TAG = "VRCXCompanion"
        const val KEY_PAIRED = "paired"
        const val KEY_ACTIVE = "activeId"
        const val KEY_DEVICE_ID = "deviceId"
    }
}
