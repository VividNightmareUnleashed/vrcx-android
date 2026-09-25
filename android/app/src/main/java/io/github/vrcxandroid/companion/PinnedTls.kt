package io.github.vrcxandroid.companion

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/** The server certificate's SPKI SHA-256 differs from the pinned fingerprint. */
class FingerprintMismatchException(val presented: String?) : IOException("certificate fingerprint mismatch")

/** The address is not in the local ranges of PROTOCOL.md §1. */
class NotLocalAddressException(address: String) : IOException("not a local address: $address")

/**
 * Accepts the server only when the SHA-256 of its certificate's SubjectPublicKeyInfo (base64url, no padding) equals
 * [expectedFp]. With [expectedFp] null (manual pairing) any certificate is accepted and its fingerprint is captured in
 * [presentedFp]; the pairing HMAC then binds that fingerprint (PROTOCOL.md §5.2). No chain, validity or hostname
 * checks: the certificate is self-signed and only the key matters.
 */
class PinningTrustManager(private val expectedFp: String?) : X509ExtendedTrustManager() {
    @Volatile
    var presentedFp: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        check(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = rejectClient()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        rejectClient()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        rejectClient()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun rejectClient(): Nothing = throw CertificateException("client certificates are not used")

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val fp = PairingCrypto.fingerprint(leaf)
        presentedFp = fp
        if (expectedFp != null && !PairingCrypto.constantTimeEquals(fp, expectedFp)) {
            throw CertificateException("certificate fingerprint mismatch")
        }
    }
}

/** One TLS connection to a companion. [send] may be called from any thread; reads happen on one thread. */
class TlsConnection internal constructor(
    private val raw: Socket,
    private val ssl: SSLSocket,
    /** SPKI fingerprint of the certificate the companion presented (equals the pin when one was given). */
    val fingerprint: String,
) : Closeable {
    private val input = BufferedInputStream(ssl.inputStream, 64 * 1024)
    private val output = BufferedOutputStream(ssl.outputStream, 16 * 1024)
    private val writeLock = Any()

    @Volatile
    var closed = false
        private set

    /** Monotonic time (ms) of the last successful [send]. */
    @Volatile
    var lastSentAtMs: Long = monotonicMs()
        private set

    val remoteAddress: InetAddress? get() = raw.inetAddress

    fun readFrame(): Frame = FrameCodec.read(input)

    fun send(frame: ByteArray) {
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
            lastSentAtMs = monotonicMs()
        }
    }

    fun sendControl(message: kotlinx.serialization.json.JsonObject) = send(FrameCodec.encodeControl(message))

    /** Closes the TCP socket first so a reader blocked on another thread returns immediately. */
    override fun close() {
        closed = true
        try {
            raw.close()
        } catch (_: IOException) {
        }
        try {
            ssl.close()
        } catch (_: Exception) {
        }
    }
}

internal fun monotonicMs(): Long = System.nanoTime() / 1_000_000

/** Opens pinned TLS connections to local addresses only. */
class TlsConnector(private val connectTimeoutMs: Int, private val readTimeoutMs: Int) {
    /**
     * @param expectedFp the pinned fingerprint, or null to capture the presented one (manual pairing).
     * @throws NotLocalAddressException for a non-local [address]
     * @throws FingerprintMismatchException when the presented key is not the pinned one
     * @throws IOException when the companion cannot be reached or the handshake fails
     */
    fun connect(address: InetAddress, port: Int, expectedFp: String?): TlsConnection {
        if (!LocalAddressFilter.isLocal(address)) throw NotLocalAddressException(address.hostAddress ?: "?")
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(address, port), connectTimeoutMs)
            raw.soTimeout = readTimeoutMs
            val trustManager = PinningTrustManager(expectedFp)
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), SecureRandom())
            // An IP-literal peer name: no SNI, and SSLSocket does no hostname verification unless asked to.
            val ssl = context.socketFactory.createSocket(raw, address.hostAddress, port, true) as SSLSocket
            ssl.useClientMode = true
            val protocols = ssl.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
            if (protocols.isNotEmpty()) ssl.enabledProtocols = protocols.toTypedArray()
            ssl.soTimeout = readTimeoutMs
            try {
                ssl.startHandshake()
            } catch (e: IOException) {
                val presented = trustManager.presentedFp
                if (expectedFp != null && presented != null && !PairingCrypto.constantTimeEquals(presented, expectedFp)) {
                    throw FingerprintMismatchException(presented)
                }
                throw e
            }
            val fp = trustManager.presentedFp ?: throw IOException("no server certificate")
            return TlsConnection(raw, ssl, fp)
        } catch (e: Throwable) {
            try {
                raw.close()
            } catch (_: IOException) {
            }
            throw e
        }
    }
}
