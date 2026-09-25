package io.github.vrcxandroid.companion

import io.github.vrcxandroid.bridge.errorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class PairingTest {
    @Test
    fun protocolTestVectors() {
        // docs/PROTOCOL.md §7
        assertEquals(
            "lminVMWbovCkRt0N33ED9SLaV7-Nqw-Tcinojo8YL3M",
            PairingCrypto.clientProof("ABCDE12345", "fp-test", "hn", "cn"),
        )
        assertEquals(
            "Y1SCIkysnwsJGNruwoktsQix-sFYpCeiexDg-x56VvI",
            PairingCrypto.serverProof("ABCDE12345", "fp-test", "hn", "cn"),
        )
    }

    @Test
    fun codeNormalization() {
        assertEquals("ABCDE12345", PairingCrypto.normalizeCode("abcde-12345"))
        assertEquals("ABCDE12345", PairingCrypto.normalizeCode(" ABCDE 12345 "))
        assertEquals("00111ABCDE", PairingCrypto.normalizeCode("oOiIl-abcde"))
        assertEquals("0123456789", PairingCrypto.normalizeCode("01234-56789"))
        assertEquals("VWXYZ0KMNP", PairingCrypto.normalizeCode("vwxyz-okmnp"))
        assertNull(PairingCrypto.normalizeCode("ABCDE1234"))
        assertNull(PairingCrypto.normalizeCode("ABCDE123456"))
        assertNull(PairingCrypto.normalizeCode("ABCDU12345")) // U is not Crockford
        assertNull(PairingCrypto.normalizeCode("ABCD!12345"))
        assertNull(PairingCrypto.normalizeCode(""))
    }

    @Test
    fun fingerprintIsBase64UrlSha256OfSpki() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0", PairingCrypto.fingerprintOfSpki("abc".toByteArray()))
        val cert = TestKeys.certificate("companion")
        val expected = PairingCrypto.base64Url(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))
        assertEquals(expected, PairingCrypto.fingerprint(cert))
        assertEquals(43, expected.length)
        assertFalse(expected.contains('=') || expected.contains('+') || expected.contains('/'))
        assertFalse(PairingCrypto.fingerprint(cert) == TestKeys.fingerprint("impostor"))
    }

    @Test
    fun randomTokensAre32Bytes() {
        val a = PairingCrypto.randomToken()
        assertEquals(43, a.length)
        assertFalse(a == PairingCrypto.randomToken())
    }

    @Test
    fun constantTimeEquals() {
        assertTrue(PairingCrypto.constantTimeEquals("abc", "abc"))
        assertFalse(PairingCrypto.constantTimeEquals("abc", "abd"))
        assertFalse(PairingCrypto.constantTimeEquals("abc", "abcd"))
    }

    @Test
    fun pairingExceptionTextForThePage() {
        assertEquals("PairingException: expired", errorText(PairingException(PairingException.EXPIRED)))
        assertEquals("code", PairingException.fromPairFail("weird").code)
        assertEquals("closed", PairingException.fromPairFail("closed").code)
    }

    private fun qrCode(block: () -> Unit): String {
        try {
            block()
        } catch (e: PairingException) {
            return e.code
        }
        fail("expected PairingException")
        return ""
    }

    @Test
    fun qrPayloadParsing() {
        val qr = PairingQr.parse(
            "vrcxc://pair?v=1&id=3f2b8c1e-5d6a-4f70-9e1b-00000000c0de&n=MY%20PC%20%C3%A4" +
                "&h=192.168.1.20,fe80::1%25eth0,8.8.8.8,[fd00::5]&p=49460&fp=AbC-_d&c=abcde-12345",
        )
        assertEquals("3f2b8c1e-5d6a-4f70-9e1b-00000000c0de", qr.id)
        assertEquals("MY PC ä", qr.name)
        assertEquals(listOf("192.168.1.20", "fe80::1%eth0", "fd00::5"), qr.hosts)
        assertEquals(49460, qr.port)
        assertEquals("AbC-_d", qr.fp)
        assertEquals("ABCDE12345", qr.code)

        val upper = PairingQr.parse("VRCXC://pair?v=1&id=abc&h=10.0.0.2&p=1&fp=x&c=ABCDE12345")
        assertEquals(listOf("10.0.0.2"), upper.hosts)
        assertEquals("", upper.name)
    }

    @Test
    fun qrPayloadRejections() {
        val ok = "v=1&id=abc&n=pc&h=192.168.0.2&p=49460&fp=x&c=ABCDE12345"
        assertEquals(PairingException.INVALID_QR, qrCode { PairingQr.parse("https://example.com/?$ok") })
        assertEquals(PairingException.INVALID_QR, qrCode { PairingQr.parse("hello") })
        assertEquals(PairingException.VERSION, qrCode { PairingQr.parse("vrcxc://pair?" + ok.replace("v=1", "v=2")) })
        assertEquals(PairingException.INVALID_QR, qrCode { PairingQr.parse("vrcxc://pair?" + ok.replace("fp=x", "fp=")) })
        assertEquals(PairingException.INVALID_QR, qrCode { PairingQr.parse("vrcxc://pair?" + ok.replace("p=49460", "p=0")) })
        assertEquals(PairingException.INVALID_QR, qrCode { PairingQr.parse("vrcxc://pair?" + ok.replace("c=ABCDE12345", "c=ABC")) })
        assertEquals(PairingException.INVALID_QR, qrCode { PairingQr.parse("vrcxc://pair?" + ok.replace("id=abc", "id=../x")) })
        assertEquals(PairingException.INVALID_QR, qrCode { PairingQr.parse("vrcxc://pair?" + ok.replace("h=192.168.0.2", "h=")) })
        assertEquals(
            PairingException.NOT_LOCAL,
            qrCode { PairingQr.parse("vrcxc://pair?" + ok.replace("h=192.168.0.2", "h=8.8.8.8,2001:db8::1")) },
        )
        // Host names are never resolved from a QR code.
        assertEquals(
            PairingException.NOT_LOCAL,
            qrCode { PairingQr.parse("vrcxc://pair?" + ok.replace("h=192.168.0.2", "h=mypc.local")) },
        )
    }

    @Test
    fun pairedRecordsRoundTripThroughTheStore() {
        val store = InMemorySecureStore()
        val repo = PairingRepository(store)
        val first = repo.load()
        assertTrue(first.records.isEmpty())
        assertNull(first.activeId)
        assertEquals(first.deviceId, repo.load().deviceId)

        val rec = PairedCompanion("abc", "PC", "fp", "secret", listOf("192.168.0.2"), 49460, 1, 2, "PC", null)
        repo.save(listOf(rec), "abc")
        val loaded = repo.load()
        assertEquals(listOf(rec), loaded.records)
        assertEquals("abc", loaded.activeId)
        assertFalse(rec.toPublicJson().containsKey("token"))
        assertTrue(store.get(PairingRepository.KEY_PAIRED)!!.contains("secret"))

        repo.save(emptyList(), null)
        assertNull(repo.load().activeId)
    }
}
