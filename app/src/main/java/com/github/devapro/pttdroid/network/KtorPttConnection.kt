package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.network.protocol.ClientMessage
import com.github.devapro.pttdroid.network.protocol.decodeServerMessage
import com.github.devapro.pttdroid.network.protocol.encode
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
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
import timber.log.Timber

/**
 * Ktor-based transport. Replaces the old `org.java-websocket` client, which the README had
 * flagged as a TODO and which also drove audio playback directly from its callbacks.
 */
class KtorPttConnection : PttConnection {

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private val _events = MutableSharedFlow<ConnectionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<ConnectionEvent> = _events.asSharedFlow()

    private val sessionLock = Mutex()
    private var session: DefaultClientWebSocketSession? = null

    override suspend fun connect(url: String) {
        try {
            client.webSocket(urlString = url) {
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
            Timber.w(e, "WebSocket connect failed")
            _events.emit(ConnectionEvent.Disconnected(reason = e.message, cause = e))
        }
    }

    private suspend fun emitControl(text: String) {
        val message = try {
            decodeServerMessage(text)
        } catch (e: SerializationException) {
            Timber.w(e, "Unparseable control message: %s", text.take(200))
            return
        }
        _events.emit(ConnectionEvent.Control(message))
    }

    override suspend fun disconnect() {
        val active = sessionLock.withLock { session.also { session = null } }
        runCatching { active?.close() }
            .onFailure { Timber.d("Close failed: %s", it.toString()) }
    }

    override suspend fun send(message: ClientMessage) {
        val active = sessionLock.withLock { session } ?: return
        runCatching { active.send(Frame.Text(message.encode())) }
            .onFailure { Timber.d("Control send failed: %s", it.toString()) }
    }

    override suspend fun sendAudio(pcm: ByteArray): Boolean {
        val active = sessionLock.withLock { session } ?: return false
        return runCatching { active.send(Frame.Binary(true, pcm)); true }
            .getOrElse {
                if (it !is ClosedReceiveChannelException) {
                    Timber.d("Audio send failed: %s", it.toString())
                }
                false
            }
    }

    fun shutdown() {
        client.close()
    }
}
