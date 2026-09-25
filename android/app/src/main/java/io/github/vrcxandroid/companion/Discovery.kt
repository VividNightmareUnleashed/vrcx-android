package io.github.vrcxandroid.companion

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Held only while a discovery runs (Android: WifiManager.MulticastLock, so broadcast replies are not filtered). */
interface MulticastLockHandle {
    fun acquire(): Boolean
    fun release()

    companion object {
        val NONE: MulticastLockHandle = object : MulticastLockHandle {
            override fun acquire() = false
            override fun release() {}
        }
    }
}

/** One discovery reply (PROTOCOL.md §2). [hosts] lists every local address the same companion answered from. */
data class DiscoveredCompanion(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val fp: String,
    val pairing: Boolean,
    val hosts: List<String> = listOf(host),
) {
    /** `{id, name, host, port, fp, pairing, hosts}` for `AndroidHost.CompanionDiscover`. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("fp", fp)
        put("pairing", pairing)
        put("hosts", buildJsonArray { hosts.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }
}

/**
 * UDP discovery: sends `{"t":"vrcx-discover","v":1}` to 255.255.255.255 and every interface's directed broadcast, then
 * collects replies until the timeout. Replies from non-local sources are ignored.
 */
class CompanionDiscovery(
    private val multicastLock: MulticastLockHandle,
    private val port: Int = CompanionProtocol.DEFAULT_DISCOVERY_PORT,
    private val targets: () -> List<InetAddress> = ::broadcastTargets,
) {
    /**
     * Blocks for up to [timeoutMs]. [stopWhen] ends the wait early (used by the reconnect path that looks for one id).
     * The request is repeated once after a third of the timeout because UDP broadcasts get lost on busy Wi-Fi.
     */
    fun discover(timeoutMs: Long, stopWhen: ((DiscoveredCompanion) -> Boolean)? = null): List<DiscoveredCompanion> {
        val found = LinkedHashMap<String, DiscoveredCompanion>()
        val acquired = multicastLock.acquire()
        try {
            DatagramSocket(null).use { socket ->
                socket.broadcast = true
                socket.bind(InetSocketAddress(0))
                val request = control(CompanionProtocol.T_DISCOVER) { put("v", CompanionProtocol.VERSION) }
                    .toString().toByteArray(Charsets.UTF_8)
                val destinations = targets()
                val start = monotonicMs()
                val deadline = start + timeoutMs.coerceAtLeast(1)
                var resendAt = if (timeoutMs >= 900) start + timeoutMs / 3 else Long.MAX_VALUE
                send(socket, request, destinations)
                val buffer = ByteArray(4096)
                while (true) {
                    val now = monotonicMs()
                    if (now >= deadline) break
                    if (now >= resendAt) {
                        send(socket, request, destinations)
                        resendAt = Long.MAX_VALUE
                    }
                    socket.soTimeout = (minOf(deadline, resendAt) - now).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val reply = parseReply(packet) ?: continue
                    val existing = found[reply.id]
                    found[reply.id] = if (existing == null) reply else
                        existing.copy(hosts = (existing.hosts + reply.host).distinct())
                    if (stopWhen?.invoke(reply) == true) break
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "discovery failed: ${e.message}")
        } finally {
            if (acquired) multicastLock.release()
        }
        return found.values.toList()
    }

    private fun send(socket: DatagramSocket, request: ByteArray, destinations: List<InetAddress>) {
        for (target in destinations) {
            try {
                socket.send(DatagramPacket(request, request.size, target, port))
            } catch (e: IOException) {
                // One unreachable interface must not stop the others.
            } catch (e: SecurityException) {
            }
        }
    }

    private fun parseReply(packet: DatagramPacket): DiscoveredCompanion? {
        val from = packet.address ?: return null
        if (!LocalAddressFilter.isLocal(from)) return null
        val json = try {
            CompanionJson.parseToJsonElement(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (json.str("t") != CompanionProtocol.T_DISCOVER_REPLY) return null
        if (json.int("v") != CompanionProtocol.VERSION) return null
        val id = json.str("id")?.takeIf(CompanionProtocol::isSafeId) ?: return null
        val fp = json.str("fp")?.takeIf { it.isNotBlank() } ?: return null
        val port = json.int("port")?.takeIf { it in 1..65535 } ?: CompanionProtocol.DEFAULT_TCP_PORT
        val host = from.hostAddress ?: return null
        return DiscoveredCompanion(
            id = id,
            name = json.str("name").orEmpty(),
            host = host,
            port = port,
            fp = fp,
            pairing = json.bool("pairing") ?: false,
        )
    }

    companion object {
        private const val TAG = "VRCXCompanion"

        /** 255.255.255.255 plus the directed broadcast of every up, non-loopback IPv4 interface. */
        fun broadcastTargets(): List<InetAddress> {
            val out = LinkedHashSet<InetAddress>()
            out += InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    try {
                        if (!nif.isUp || nif.isLoopback) continue
                    } catch (e: IOException) {
                        continue
                    }
                    for (ia in nif.interfaceAddresses) {
                        val broadcast = ia.broadcast
                        if (ia.address is Inet4Address && broadcast != null) out += broadcast
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot enumerate interfaces: ${e.message}")
            }
            return out.toList()
        }
    }
}
