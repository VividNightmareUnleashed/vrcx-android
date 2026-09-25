package io.github.vrcxandroid.companion

import io.github.vrcxandroid.bridge.DotNetException
import io.github.vrcxandroid.logwatcher.CompanionInfo
import io.github.vrcxandroid.logwatcher.PcFileMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

/** Constants of docs/PROTOCOL.md, version 1. */
object CompanionProtocol {
    const val VERSION = 1
    const val DEFAULT_TCP_PORT = 49460
    const val DEFAULT_DISCOVERY_PORT = 49461

    // Control message types (PROTOCOL.md §5).
    const val T_HELLO = "hello"
    const val T_PAIR = "pair"
    const val T_PAIRED = "paired"
    const val T_PAIR_FAIL = "pairFail"
    const val T_AUTH = "auth"
    const val T_AUTH_OK = "authOk"
    const val T_AUTH_FAIL = "authFail"
    const val T_INFO = "info"
    const val T_SUBSCRIBE = "subscribe"
    const val T_SNAPSHOT = "snapshot"
    const val T_PROCESS = "process"
    const val T_TRUNCATE = "truncate"
    const val T_SYNC_COMPLETE = "syncComplete"
    const val T_HEARTBEAT = "heartbeat"
    const val T_PING = "ping"
    const val T_ACK = "ack"
    const val T_FETCH = "fetch"
    const val T_DISCOVER = "vrcx-discover"
    const val T_DISCOVER_REPLY = "vrcx-companion"

    /** Companion ids become directory names of the log mirror, so only a safe character set is accepted. */
    private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun isSafeId(id: String?): Boolean = id != null && SAFE_ID.matches(id)
}

/** The JSON configuration used for every control message. */
internal val CompanionJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

/** A protocol violation: the connection is closed (PROTOCOL.md §4). */
open class ProtocolException(message: String) : IOException(message)

/**
 * Rejection for `AndroidHost.CompanionPair` / `CompanionScanQr`: the page sees `PairingException: <code>`
 * (ARCHITECTURE.md §5.1). Codes: `code`, `expired`, `closed`, `unreachable`, `not-local`, `fingerprint`, plus
 * `version` (the companion speaks another protocol version), `invalid-qr` (not a vrcxc://pair payload) and `protocol`
 * (malformed companion messages).
 */
class PairingException(val code: String, cause: Throwable? = null) : DotNetException("PairingException", code) {
    init {
        if (cause != null) initCause(cause)
    }

    companion object {
        const val CODE = "code"
        const val EXPIRED = "expired"
        const val CLOSED = "closed"
        const val UNREACHABLE = "unreachable"
        const val NOT_LOCAL = "not-local"
        const val FINGERPRINT = "fingerprint"
        const val VERSION = "version"
        const val INVALID_QR = "invalid-qr"
        const val PROTOCOL = "protocol"

        /** `pairFail.reason` values the companion may send; anything else is reported as [CODE]. */
        fun fromPairFail(reason: String?): PairingException = PairingException(
            when (reason) {
                CODE, EXPIRED, CLOSED -> reason
                else -> CODE
            },
        )
    }
}

// ---- JSON field helpers (tolerant: wrong types read as null) ----

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let {
    if (it.isString) null else it.longOrNull ?: it.doubleOrNull?.toLong()
}

internal fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let {
    if (it.isString) null else it.booleanOrNull
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun jsonOrNull(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

internal fun control(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
    buildJsonObject {
        put("t", type)
        build()
    }

/** `hello` (PROTOCOL.md §5.1). */
data class HelloMessage(val version: Int, val id: String, val name: String, val nonce: String, val pairing: Boolean) {
    companion object {
        fun parse(json: JsonObject): HelloMessage = HelloMessage(
            version = json.int("v") ?: -1,
            id = json.str("id").orEmpty(),
            name = json.str("name").orEmpty(),
            nonce = json.str("nonce").orEmpty(),
            pairing = json.bool("pairing") ?: false,
        )
    }
}

/** Parses `info` (PROTOCOL.md §5.4) into the shape the log side consumes. */
internal fun parseInfo(json: JsonObject): CompanionInfo {
    val tz = json.obj("tz")
    return CompanionInfo(
        companionVersion = json.str("companionVersion").orEmpty(),
        machineName = json.str("machineName").orEmpty(),
        pcUtcNowMs = json.long("pcUtcNowMs") ?: 0L,
        tzWindowsId = tz?.str("windowsId"),
        tzIanaId = tz?.str("ianaId"),
        tzSupportsDst = tz?.bool("supportsDst") ?: false,
        tzBaseUtcOffsetMin = tz?.int("baseUtcOffsetMin") ?: 0,
        tzCurrentUtcOffsetMin = tz?.int("currentUtcOffsetMin") ?: 0,
        logDir = json.str("logDir"),
        dirExists = json.bool("dirExists") ?: false,
    )
}

/** `tz` object of the state (ARCHITECTURE.md §5.1); null when the companion sent no zone. */
internal fun tzJson(info: CompanionInfo): JsonObject? {
    if (info.tzWindowsId == null && info.tzIanaId == null) return null
    return buildJsonObject {
        put("windowsId", jsonOrNull(info.tzWindowsId))
        put("ianaId", jsonOrNull(info.tzIanaId))
        put("supportsDst", info.tzSupportsDst)
        put("baseUtcOffsetMin", info.tzBaseUtcOffsetMin)
        put("currentUtcOffsetMin", info.tzCurrentUtcOffsetMin)
    }
}

/** `snapshot.files` (PROTOCOL.md §5.6). Entries without a name or file id are dropped. */
internal fun parseSnapshot(json: JsonObject): List<PcFileMeta> {
    val files = json["files"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return files.mapNotNull { element ->
        val f = element as? JsonObject ?: return@mapNotNull null
        val name = f.str("name") ?: return@mapNotNull null
        val fileId = f.str("fileId") ?: return@mapNotNull null
        PcFileMeta(
            name = name,
            fileId = fileId,
            creationTimeUtcTicks = f.long("creationTimeUtcTicks") ?: 0L,
            lastWriteTimeUtcTicks = f.long("lastWriteTimeUtcTicks") ?: 0L,
            length = f.long("length") ?: 0L,
        )
    }
}

/** Human-readable name of a JSON primitive for logs without leaking content. */
internal fun JsonObject.typeOrEmpty(): String = (this["t"] as? JsonPrimitive)?.contentOrNull.orEmpty()
