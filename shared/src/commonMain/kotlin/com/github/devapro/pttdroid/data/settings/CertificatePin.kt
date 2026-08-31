package com.github.devapro.pttdroid.data.settings

/**
 * A SHA-256 certificate fingerprint, as the operator reads it off the relay.
 *
 * The relay serves a self-signed certificate — there is no domain to prove ownership of on a
 * LAN, and no CA will issue for one — so the client cannot verify it by chain. It verifies the
 * exact key instead: this fingerprint, copied once from the server's startup log.
 *
 * People will paste it in whatever form they have it: `keytool` and `openssl` print colons and
 * uppercase, a copy out of a terminal may lose them, and a phone keyboard may add a space.
 * Anything that reduces to the same 32 bytes is the same pin.
 */
object CertificatePin {

    private const val SHA256_HEX_LENGTH = 64
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private val HEX = HEX_DIGITS.toSet()

    /**
     * Strips separators and upper-cases. Returns an empty string for anything that is not a
     * complete SHA-256 fingerprint, so a half-typed value never silently becomes a pin that
     * matches nothing.
     */
    fun normalize(raw: String): String {
        val condensed = raw.filterNot { it == ':' || it == ' ' || it == '-' || it == '\n' || it == '\r' || it == '\t' }
            .uppercase()
        return if (isNormalized(condensed)) condensed else ""
    }

    /** True for a value that is either empty (no pin) or a complete fingerprint. */
    fun isAcceptable(raw: String): Boolean = raw.isBlank() || normalize(raw).isNotEmpty()

    fun matches(pin: String, certificateSha256: ByteArray): Boolean {
        val expected = normalize(pin)
        if (expected.isEmpty()) return false
        val actual = toHex(certificateSha256)
        // Length is fixed and public; the comparison itself is constant-time.
        if (expected.length != actual.length) return false
        var difference = 0
        for (i in expected.indices) difference = difference or (expected[i].code xor actual[i].code)
        return difference == 0
    }

    /** Groups into colon-separated pairs, the way `openssl x509 -fingerprint` prints it. */
    fun format(pin: String): String =
        normalize(pin).chunked(2).joinToString(":")

    // Manual hex encoding: `"%02X".format(byte)` (java.util.Formatter) has no commonMain
    // equivalent, unlike on the JVM-only class this was moved from.
    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value shr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

    private fun isNormalized(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH && value.all { it in HEX }
}
