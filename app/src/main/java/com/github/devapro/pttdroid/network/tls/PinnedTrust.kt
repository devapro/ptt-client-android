package com.github.devapro.pttdroid.network.tls

import android.annotation.SuppressLint
import com.github.devapro.pttdroid.data.settings.CertificatePin
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trusts exactly one certificate, identified by its SHA-256 fingerprint.
 *
 * A self-hosted relay has no certificate authority behind it, so the usual question — "did
 * someone I trust vouch for this certificate?" — has no answer. This asks the only question
 * that can be answered on a LAN: "is this the exact certificate the operator told me about?"
 *
 * That is a stronger guarantee than a CA chain, not a weaker one: it admits one key and
 * nothing else. What it gives up is *rotation* — replacing the relay's keypair means every
 * client re-pairs — which is why the server keeps its keystore on disk rather than generating
 * a fresh one each boot.
 */
// Lint flags any hand-written trust manager, and rightly: the usual one accepts everything.
// This one accepts a single certificate and throws for every other case, which is the whole
// reason it exists — a self-hosted relay has no certificate authority to appeal to.
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
class PinnedTrustManager(
    private val pin: String,
    /** Injectable so the validity window can be tested without waiting for one to close. */
    private val now: () -> Date = ::Date,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val presented = chain?.firstOrNull()
            ?: throw CertificateException("The relay presented no certificate")

        // An expired pinned certificate is still a refusal: the pin says which key, the
        // validity window says for how long, and silently ignoring the second half would let a
        // long-forgotten key stay valid forever.
        try {
            presented.checkValidity(now())
        } catch (e: java.security.cert.CertificateExpiredException) {
            throw CertificateException(
                "The relay's certificate expired on ${presented.notAfter}. Regenerate it on the " +
                    "server and re-copy the fingerprint.",
                e,
            )
        } catch (e: java.security.cert.CertificateNotYetValidException) {
            throw CertificateException(
                "The relay's certificate is not valid until ${presented.notBefore} — check the " +
                    "clock on the server.",
                e,
            )
        }

        val actual = MessageDigest.getInstance("SHA-256").digest(presented.encoded)
        if (!CertificatePin.matches(pin, actual)) {
            throw CertificateException(
                "Certificate fingerprint does not match. Expected ${CertificatePin.format(pin)}, " +
                    "got ${CertificatePin.format(CertificatePin.toHex(actual))}.",
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // This is a client. Nothing ever asks it to vouch for a peer.
        throw CertificateException("Client certificates are not supported")
    }

    /**
     * Deliberately empty.
     *
     * Returning issuers here would put this trust manager on OkHttp's chain-cleaning path,
     * which wants to build a chain up to a known root — there is no root, and the fingerprint
     * check above has already decided the question.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Accepts any hostname.
 *
 * Only ever installed alongside [PinnedTrustManager], where the pin already identifies the
 * peer far more precisely than a name would: a relay is reached by whatever address it happens
 * to have — a LAN IP today, a different one tomorrow — and a self-signed certificate cannot
 * enumerate them in advance. Without a pin the client uses the platform's verifier untouched.
 */
@SuppressLint("BadHostnameVerifier", "AllowAllHostnameVerifier")
internal object PinnedHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean = true
}

/** The socket factory and trust manager OkHttp needs, or null when nothing is pinned. */
fun pinnedTls(pin: String): Pair<SSLSocketFactory, X509TrustManager>? {
    if (CertificatePin.normalize(pin).isEmpty()) return null
    val trustManager = PinnedTrustManager(pin)
    val context = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), null)
    }
    return context.socketFactory to trustManager
}
