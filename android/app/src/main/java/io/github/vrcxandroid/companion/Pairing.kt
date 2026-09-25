package io.github.vrcxandroid.companion

import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pairing cryptography of PROTOCOL.md §5.2. */
object PairingCrypto {
    /** Crockford base32 alphabet (no I, L, O, U). */
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val CODE_LENGTH = 10

    private val random = SecureRandom()

    /**
     * Normalizes a typed or scanned pairing code: uppercase, drop `-` and whitespace, `O` → `0`, `I`/`L` → `1`.
     * Returns null unless the result is exactly 10 Crockford base32 characters.
     */
    fun normalizeCode(input: String): String? {
        val sb = StringBuilder(CODE_LENGTH)
        for (ch in input.uppercase(Locale.ROOT)) {
            when {
                ch == '-' || ch.isWhitespace() -> Unit
                ch == 'O' -> sb.append('0')
                ch == 'I' || ch == 'L' -> sb.append('1')
                else -> sb.append(ch)
            }
        }
        if (sb.length != CODE_LENGTH || sb.any { it !in CROCKFORD }) return null
        return sb.toString()
    }

    /** `proof` of the phone's `pair` message. */
    fun clientProof(code: String, fp: String, helloNonce: String, clientNonce: String): String =
        hmac(code, "vrcxc-pair-v1|client|$fp|$helloNonce|$clientNonce")

    /** `proof` of the companion's `paired` message. */
    fun serverProof(code: String, fp: String, helloNonce: String, clientNonce: String): String =
        hmac(code, "vrcxc-pair-v1|server|$fp|$clientNonce|$helloNonce")

    private fun hmac(code: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return base64Url(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    /** base64url (no padding) of SHA-256 over the DER SubjectPublicKeyInfo. */
    fun fingerprint(certificate: X509Certificate): String = fingerprintOfSpki(certificate.publicKey.encoded)

    fun fingerprintOfSpki(spkiDer: ByteArray): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(spkiDer))

    /** base64url of 32 random bytes (client nonces and test tokens). */
    fun randomToken(): String = ByteArray(32).also(random::nextBytes).let(::base64Url)

    fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Compares two proofs or fingerprints without an early exit. */
    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

/** Contents of a `vrcxc://pair?...` QR code (PROTOCOL.md §5.2), hosts already filtered to local addresses. */
data class QrPairing(
    val id: String,
    val name: String,
    val hosts: List<String>,
    val port: Int,
    val fp: String,
    val code: String,
)

object PairingQr {
    private const val PREFIX = "vrcxc://pair?"

    /**
     * Parses `vrcxc://pair?v=1&id=..&n=..&h=a,b&p=..&fp=..&c=..`.
     * @throws PairingException `invalid-qr` for anything else, `version` for another protocol version, `not-local`
     * when no listed address is local.
     */
    fun parse(payload: String): QrPairing {
        val text = payload.trim()
        if (!text.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) {
            throw PairingException(PairingException.INVALID_QR)
        }
        val params = HashMap<String, String>()
        for (part in text.substring(PREFIX.length).split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            val value = if (eq < 0) "" else part.substring(eq + 1)
            val decoded = try {
                URLDecoder.decode(value, "UTF-8")
            } catch (e: IllegalArgumentException) {
                throw PairingException(PairingException.INVALID_QR, e)
            }
            params.putIfAbsent(key, decoded)
        }
        val version = params["v"]?.toIntOrNull() ?: throw PairingException(PairingException.INVALID_QR)
        if (version != CompanionProtocol.VERSION) throw PairingException(PairingException.VERSION)
        val id = params["id"]?.takeIf(CompanionProtocol::isSafeId) ?: throw PairingException(PairingException.INVALID_QR)
        val fp = params["fp"]?.takeIf { it.isNotBlank() } ?: throw PairingException(PairingException.INVALID_QR)
        val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw PairingException(PairingException.INVALID_QR)
        val code = params["c"]?.let(PairingCrypto::normalizeCode) ?: throw PairingException(PairingException.INVALID_QR)
        val listed = params["h"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (listed.isEmpty()) throw PairingException(PairingException.INVALID_QR)
        val hosts = listed.filter(LocalAddressFilter::isLocalLiteral).map { it.removePrefix("[").removeSuffix("]") }
        if (hosts.isEmpty()) throw PairingException(PairingException.NOT_LOCAL)
        return QrPairing(id = id, name = params["n"].orEmpty(), hosts = hosts.distinct(), port = port, fp = fp, code = code)
    }
}
