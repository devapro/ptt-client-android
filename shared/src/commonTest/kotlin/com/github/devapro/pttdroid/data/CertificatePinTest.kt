package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.CertificatePin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A fingerprint is copied by hand from a server log into a phone. Every one of these cases is
 * a shape it plausibly arrives in.
 */
class CertificatePinTest {

    private val digits = "FD0EFB7BD3BB639FA169910467D1C65C3302269A87C899C2F05DE933CB500689"
    private val colons = "FD:0E:FB:7B:D3:BB:63:9F:A1:69:91:04:67:D1:C6:5C:" +
        "33:02:26:9A:87:C8:99:C2:F0:5D:E9:33:CB:50:06:89"

    @Test
    fun `colons are optional`() {
        assertEquals(digits, CertificatePin.normalize(colons))
        assertEquals(digits, CertificatePin.normalize(digits))
    }

    @Test
    fun `case is irrelevant`() {
        assertEquals(digits, CertificatePin.normalize(colons.lowercase()))
    }

    @Test
    fun `spaces, dashes and stray newlines from a paste are ignored`() {
        assertEquals(digits, CertificatePin.normalize("  $colons\n"))
        assertEquals(digits, CertificatePin.normalize(digits.chunked(8).joinToString(" ")))
        assertEquals(digits, CertificatePin.normalize(digits.chunked(8).joinToString("-")))
        assertEquals(digits, CertificatePin.normalize(digits.chunked(16).joinToString("\r\n")))
    }

    @Test
    fun `a partial fingerprint is not a pin`() {
        // Half a fingerprint must not become a pin that silently matches nothing.
        assertEquals("", CertificatePin.normalize(digits.dropLast(2)))
        assertEquals("", CertificatePin.normalize(digits + "AB"))
        assertEquals("", CertificatePin.normalize(""))
    }

    @Test
    fun `non-hex characters make it invalid rather than being stripped`() {
        assertEquals("", CertificatePin.normalize(digits.dropLast(1) + "Z"))
        assertEquals("", CertificatePin.normalize("sha256:$digits"))
    }

    @Test
    fun `empty is acceptable because it means no pin, half a value is not`() {
        assertTrue(CertificatePin.isAcceptable(""))
        assertTrue(CertificatePin.isAcceptable("   "))
        assertTrue(CertificatePin.isAcceptable(colons))
        assertFalse(CertificatePin.isAcceptable("FD:0E"))
    }

    @Test
    fun `formatting round-trips to the shape openssl prints`() {
        assertEquals(colons, CertificatePin.format(digits))
        assertEquals(colons, CertificatePin.format(colons))
        assertEquals("", CertificatePin.format("nonsense"))
    }

    @Test
    fun `matching compares the bytes, not the spelling`() {
        val bytes = ByteArray(32) { index -> digits.substring(index * 2, index * 2 + 2).toInt(16).toByte() }

        assertTrue(CertificatePin.matches(colons, bytes))
        assertTrue(CertificatePin.matches(digits.lowercase(), bytes))
        assertFalse(CertificatePin.matches(digits.dropLast(2) + "00", bytes))
    }

    @Test
    fun `an unset pin never matches anything`() {
        assertFalse(CertificatePin.matches("", ByteArray(32)))
        assertFalse(CertificatePin.matches("not-a-fingerprint", ByteArray(32)))
    }

    @Test
    fun `a digest of the wrong length never matches`() {
        assertFalse(CertificatePin.matches(digits, ByteArray(31)))
        assertFalse(CertificatePin.matches(digits, ByteArray(0)))
    }

    @Test
    fun `hex encoding is upper case and zero padded`() {
        assertEquals("000FFF", CertificatePin.toHex(byteArrayOf(0, 0x0F, 0xFF.toByte())))
    }
}
