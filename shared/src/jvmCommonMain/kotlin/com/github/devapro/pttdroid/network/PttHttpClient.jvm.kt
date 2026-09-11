package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.network.tls.PinnedHostnameVerifier
import com.github.devapro.pttdroid.network.tls.pinnedTls
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed actual, lifted from the old `KtorPttConnection.clientFor` — see
 * [createPttHttpClient] in commonMain. The TLS half is that code unchanged; the ping interval
 * came later, with the keepalive.
 */
internal actual fun createPttHttpClient(endpoint: PttEndpoint): HttpClient {
    // Without a pin the platform's own verification applies unchanged, which is what a relay
    // behind a tunnel or a real certificate should get.
    val pinned = if (endpoint.isSecure) pinnedTls(endpoint.pinnedSha256) else null
    return HttpClient(OkHttp) {
        install(WebSockets)
        engine {
            config {
                // WebSocket ping frames, and the only way to get them on this engine: Ktor's
                // own `WebSockets { pingIntervalMillis }` is read by engines that build a
                // session on top of Ktor's frame channel, and OkHttp's is not one of them —
                // it implements DefaultWebSocketSession itself and rejects a Frame.Ping
                // outright (UnsupportedFrameTypeException). OkHttp handles the whole exchange
                // below Ktor instead, and fails the socket once a ping goes unanswered for
                // this long. That failure is what turns "the peer vanished and the read loop
                // will wait forever" into an ordinary disconnect the reconnect loop can act
                // on. Matches the relay's own ping period.
                pingInterval(WS_PING_SECONDS, TimeUnit.SECONDS)
                if (pinned != null) {
                    sslSocketFactory(pinned.first, pinned.second)
                    hostnameVerifier(PinnedHostnameVerifier)
                }
            }
        }
    }
}

/** Matches the relay's default `PTT_PING_SECONDS`, so neither side waits on the other. */
private const val WS_PING_SECONDS = 15L

/**
 * Keeps today's exact `CertificateException` behaviour: a failed handshake reaches the user as a
 * banner, so it has to say what to do about it — "Certificate fingerprint does not match" is
 * actionable, the `SSLHandshakeException` that wraps it is not.
 *
 * A timeout gets the same treatment for the same reason: the unanswered-ping case arrives as a
 * `SocketTimeoutException` whose message names the mechanism ("sent ping but didn't receive pong
 * within 15000ms"), and a connect timeout arrives as the same type. Neither wording says
 * anything a user can act on; both mean the relay is not answering.
 */
internal actual fun describePlatformCause(cause: Throwable): String? = when (cause) {
    is java.security.cert.CertificateException -> cause.message
    is java.net.SocketTimeoutException -> "The relay is not responding"
    else -> null
}
