package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.network.tls.PinnedHostnameVerifier
import com.github.devapro.pttdroid.network.tls.pinnedTls
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

/**
 * OkHttp-backed actual, moved verbatim from the old `KtorPttConnection.clientFor` — see
 * [createPttHttpClient] in commonMain.
 */
internal actual fun createPttHttpClient(endpoint: PttEndpoint): HttpClient {
    // Without a pin the platform's own verification applies unchanged, which is what a relay
    // behind a tunnel or a real certificate should get.
    val pinned = if (endpoint.isSecure) pinnedTls(endpoint.pinnedSha256) else null
    return HttpClient(OkHttp) {
        install(WebSockets)
        engine {
            config {
                if (pinned != null) {
                    sslSocketFactory(pinned.first, pinned.second)
                    hostnameVerifier(PinnedHostnameVerifier)
                }
            }
        }
    }
}

/**
 * Keeps today's exact `CertificateException` behaviour: a failed handshake reaches the user as a
 * banner, so it has to say what to do about it — "Certificate fingerprint does not match" is
 * actionable, the `SSLHandshakeException` that wraps it is not.
 */
internal actual fun describePlatformCause(cause: Throwable): String? =
    if (cause is java.security.cert.CertificateException) cause.message else null
