package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.PttLog
import com.github.devapro.pttdroid.network.protocol.ClientMessage
import com.github.devapro.pttdroid.network.protocol.decodeServerMessage
import com.github.devapro.pttdroid.network.protocol.encode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException

/**
 * Ktor-based transport. Replaces the old `org.java-websocket` client, which the README had
 * flagged as a TODO and which also drove audio playback directly from its callbacks.
 *
 * Lives in commonMain: the actual HTTP client construction — engine, TLS pinning — sits behind
 * [createPttHttpClient], an `expect` whose only `actual` today is jvmCommonMain's OkHttp-backed
 * one (see `PttHttpClient.jvm.kt`).
 */
class KtorPttConnection : PttConnection {

    private val _events = MutableSharedFlow<ConnectionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ConnectionEvent> = _events.asSharedFlow()

    private val sessionLock = Mutex()
    private var session: DefaultClientWebSocketSession? = null

    /**
     * The client is rebuilt only when the trust settings change, not per connection.
     *
     * The TLS stack is baked into the engine, so a pin change needs a new client — but a
     * reconnect loop that discarded the connection pool on every attempt would also discard
     * every keep-alive and retry from scratch.
     */
    private val clientLock = Mutex()
    private var client: HttpClient? = null
    private var clientProfile: String? = null

    override suspend fun connect(endpoint: PttEndpoint) {
        val http = try {
            clientFor(endpoint)
        } catch (e: Exception) {
            PttLog.w(e) { "Could not build the TLS client" }
            _events.emit(ConnectionEvent.Disconnected(reason = e.message, cause = e))
            return
        }

        try {
            http.webSocket(
                urlString = endpoint.url,
                request = {
                    if (endpoint.accessToken.isNotEmpty()) {
                        header(PttEndpoint.TOKEN_HEADER, endpoint.accessToken)
                    }
                },
            ) {
                sessionLock.withLock { session = this }
                _events.emit(ConnectionEvent.Connected)
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Binary -> _events.emit(ConnectionEvent.Audio(frame.readBytes()))
                            is Frame.Text -> emitControl(frame.readText())
                            else -> Unit
                        }
                    }
                } finally {
                    sessionLock.withLock { session = null }
                }
            }
            _events.emit(ConnectionEvent.Disconnected(reason = "closed"))
        } catch (e: CancellationException) {
            sessionLock.withLock { session = null }
            throw e
        } catch (e: Exception) {
            // The old client logged errors and did nothing — the reconnect call was commented
            // out — so a refused connection never recovered. Surface it instead.
            sessionLock.withLock { session = null }
            PttLog.w(e) { "WebSocket connect failed" }
            _events.emit(ConnectionEvent.Disconnected(reason = describe(e), cause = e))
        }
    }

    /**
     * A failed handshake reaches the user as a banner, so it has to say what to do about it.
     * "Certificate fingerprint does not match" is actionable; the SSLHandshakeException that
     * wraps it is not. [describePlatformCause] recognizes the platform's own exception types
     * (`CertificateException` on the JVM); this walk of the cause chain is itself
     * platform-independent.
     */
    private fun describe(e: Throwable): String? {
        var cause: Throwable? = e
        while (cause != null) {
            val platform = describePlatformCause(cause)
            if (platform != null) return platform
            cause = cause.cause.takeIf { it !== cause }
        }
        return e.message
    }

    private suspend fun clientFor(endpoint: PttEndpoint): HttpClient = clientLock.withLock {
        val existing = client
        if (existing != null && clientProfile == endpoint.trustProfile) return@withLock existing

        existing?.close()
        val created = createPttHttpClient(endpoint)
        client = created
        clientProfile = endpoint.trustProfile
        created
    }

    private suspend fun emitControl(text: String) {
        val message = try {
            decodeServerMessage(text)
        } catch (e: SerializationException) {
            PttLog.w(e) { "Unparseable control message: ${text.take(200)}" }
            return
        }
        _events.emit(ConnectionEvent.Control(message))
    }

    override suspend fun disconnect() {
        val active = sessionLock.withLock { session.also { session = null } }
        runCatching { active?.close() }
            .onFailure { PttLog.d { "Close failed: $it" } }
    }

    override suspend fun send(message: ClientMessage) {
        val active = sessionLock.withLock { session } ?: return
        runCatching { active.send(Frame.Text(message.encode())) }
            .onFailure { PttLog.d { "Control send failed: $it" } }
    }

    override suspend fun sendAudio(pcm: ByteArray): Boolean {
        val active = sessionLock.withLock { session } ?: return false
        return runCatching { active.send(Frame.Binary(true, pcm)); true }
            .getOrElse {
                if (it !is ClosedReceiveChannelException) {
                    PttLog.d { "Audio send failed: $it" }
                }
                false
            }
    }

    fun shutdown() {
        client?.close()
        client = null
        clientProfile = null
    }
}
