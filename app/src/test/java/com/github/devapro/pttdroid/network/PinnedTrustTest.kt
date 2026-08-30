package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.data.settings.CertificatePin
import com.github.devapro.pttdroid.network.tls.PinnedTrustManager
import com.github.devapro.pttdroid.network.tls.pinnedTls
import io.ktor.network.tls.certificates.KeyType
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * The pinned trust manager against real certificates.
 *
 * This is the one place where getting it wrong is silent and dangerous: a trust manager that
 * accepts everything looks exactly like a working one until someone is on the same Wi-Fi.
 */
class PinnedTrustTest {

    private fun certificate(name: String = "relay.test", daysValid: Long = 365): X509Certificate {
        val store = buildKeyStore {
            certificate("ptt") {
                password = "test"
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.RSA
                keySizeInBits = 1024
                keyType = KeyType.Server
                this.daysValid = daysValid
                domains = listOf(name)
            }
        }
        return store.getCertificate("ptt") as X509Certificate
    }

    private fun fingerprintOf(certificate: X509Certificate): String =
        CertificatePin.toHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))

    @Test
    fun `the pinned certificate is accepted`() {
        val certificate = certificate()

        PinnedTrustManager(fingerprintOf(certificate))
            .checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test
    fun `a colon-separated pin pasted from openssl is accepted`() {
        val certificate = certificate()
        val pasted = CertificatePin.format(fingerprintOf(certificate))

        PinnedTrustManager(pasted).checkServerTrusted(arrayOf(certificate), "RSA")
    }

    @Test
    fun `a different certificate is rejected`() {
        val ours = certificate("relay.test")
        val theirs = certificate("relay.test")

        val failure = runCatching {
            PinnedTrustManager(fingerprintOf(ours)).checkServerTrusted(arrayOf(theirs), "RSA")
        }.exceptionOrNull()

        assertTrue(failure is CertificateException)
        assertTrue(failure!!.message!!.contains("fingerprint does not match"))
    }

    @Test
    fun `the leaf is what gets checked, not something further up a forged chain`() {
        val ours = certificate("relay.test")
        val theirs = certificate("relay.test")

        // A peer that presents its own certificate first and ours behind it must still fail.
        val failure = runCatching {
            PinnedTrustManager(fingerprintOf(ours))
                .checkServerTrusted(arrayOf(theirs, ours), "RSA")
        }.exceptionOrNull()

        assertTrue(failure is CertificateException)
    }

    @Test
    fun `an expired certificate is rejected even when the fingerprint matches`() {
        // The pin says which key; the validity window says for how long. A pin alone would let
        // a long-abandoned key keep working forever.
        val certificate = certificate()
        val afterExpiry = { java.util.Date(certificate.notAfter.time + 1_000) }

        val failure = runCatching {
            PinnedTrustManager(fingerprintOf(certificate), now = afterExpiry)
                .checkServerTrusted(arrayOf(certificate), "RSA")
        }.exceptionOrNull()

        assertTrue("expected a rejection, got $failure", failure is CertificateException)
        assertTrue(failure!!.message!!.contains("expired"))
    }

    @Test
    fun `a certificate from the future is rejected, and says to check the clock`() {
        val certificate = certificate()
        val beforeIssue = { java.util.Date(certificate.notBefore.time - 86_400_000) }

        val failure = runCatching {
            PinnedTrustManager(fingerprintOf(certificate), now = beforeIssue)
                .checkServerTrusted(arrayOf(certificate), "RSA")
        }.exceptionOrNull()

        assertTrue(failure is CertificateException)
        assertTrue(failure!!.message!!.contains("clock"))
    }

    @Test
    fun `an empty chain is rejected`() {
        val failure = runCatching {
            PinnedTrustManager(fingerprintOf(certificate())).checkServerTrusted(emptyArray(), "RSA")
        }.exceptionOrNull()

        assertTrue(failure is CertificateException)
    }

    @Test
    fun `a null chain is rejected`() {
        val failure = runCatching {
            PinnedTrustManager(fingerprintOf(certificate())).checkServerTrusted(null, "RSA")
        }.exceptionOrNull()

        assertTrue(failure is CertificateException)
    }

    @Test
    fun `an empty pin accepts nothing`() {
        // pinnedTls refuses to build one, but the class itself must not be permissive either.
        val certificate = certificate()

        val failure = runCatching {
            PinnedTrustManager("").checkServerTrusted(arrayOf(certificate), "RSA")
        }.exceptionOrNull()

        assertTrue(failure is CertificateException)
    }

    @Test
    fun `it never vouches for a client`() {
        val failure = runCatching {
            PinnedTrustManager(fingerprintOf(certificate()))
                .checkClientTrusted(arrayOf(certificate()), "RSA")
        }.exceptionOrNull()

        assertTrue(failure is CertificateException)
    }

    @Test
    fun `it advertises no issuers, so it stays off the chain-cleaning path`() {
        assertEquals(0, PinnedTrustManager("").acceptedIssuers.size)
    }

    @Test
    fun `no TLS stack is built without a usable pin`() {
        assertNull(pinnedTls(""))
        assertNull(pinnedTls("   "))
        assertNull(pinnedTls("FD:0E"))
        assertNotNull(pinnedTls(fingerprintOf(certificate())))
    }

    @Test
    fun `the built socket factory and trust manager agree`() {
        val certificate = certificate()
        val (factory, trustManager) = pinnedTls(fingerprintOf(certificate))!!

        assertNotNull(factory)
        trustManager.checkServerTrusted(arrayOf(certificate), "RSA")
        assertFalse(trustManager.acceptedIssuers.isNotEmpty())
    }
}
