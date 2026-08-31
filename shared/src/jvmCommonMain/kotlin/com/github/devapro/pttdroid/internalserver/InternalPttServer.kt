package com.github.devapro.pttdroid.internalserver

import com.github.devapro.pttdroid.PttLog
import com.github.devapro.pttdroid.network.PttEndpoint.Companion.TOKEN_HEADER
import com.github.devapro.pttdroid.network.protocol.AudioParams
import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.PROTOCOL_VERSION
import com.github.devapro.pttdroid.network.protocol.Peers
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.ServerMessage
import com.github.devapro.pttdroid.network.protocol.TalkRelease
import com.github.devapro.pttdroid.network.protocol.TalkRequest
import com.github.devapro.pttdroid.network.protocol.decodeClientMessage
import com.github.devapro.pttdroid.network.protocol.encode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.security.MessageDigest
import java.util.UUID

/**
 * Optional on-device relay, so a group on the same Wi-Fi can talk with no separate server
 * (the README's "internal server for work without additional web server").
 *
 * Speaks the same protocol v1 as `ptt-server`, including per-channel isolation and floor
 * control, so the hosting phone's own client connects to it exactly like a remote one — point
 * the server host at `127.0.0.1` locally, and other phones at this device's LAN address.
 *
 * This is a compact reimplementation rather than shared code: the two halves live in separate
 * repositories with no published artifact between them.
 */
class InternalPttServer {

    private var engine: EmbeddedServer<*, *>? = null
    private val channels = HashMap<Int, ServerChannel>()
    private val lock = Mutex()

    /** What the running instance was started with, so a settings change can restart it. */
    @Volatile
    var runningConfig: Config? = null
        private set

    val isRunning: Boolean get() = engine != null

    /** The port and shared secret one run of the embedded relay was started with. */
    data class Config(val port: Int, val accessToken: String)

    fun start(port: Int, accessToken: String = "") {
        if (engine != null) return
        val config = Config(port, accessToken)
        runningConfig = config
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriodMillis = 15_000
                timeoutMillis = 15_000
                maxFrameSize = 16_384
                masking = false
            }
            routing {
                webSocket("/channel/{channelId}") { handleSession(config) }
            }
        }
        engine = server
        server.start(wait = false)
        PttLog.i { "Embedded PTT relay listening on 0.0.0.0:$port" }
    }

    fun stop() {
        val server = engine ?: return
        engine = null
        runningConfig = null
        runCatching { server.stop(gracePeriodMillis = 300, timeoutMillis = 1_000) }
            .onFailure { PttLog.d { "Embedded relay stop failed: $it" } }
        PttLog.i { "Embedded PTT relay stopped" }
    }

    // --- session handling -----------------------------------------------------------------

    private class ServerSession(val id: String, val name: String) {
        val outbound = Channel<Frame>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        private var lastErrorAt = 0L

        fun send(message: ServerMessage) {
            outbound.trySend(Frame.Text(message.encode()))
        }

        fun shouldReportError(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastErrorAt < 1_000) return false
            lastErrorAt = now
            return true
        }
    }

    /** One channel's members and talk floor. Mirrors `PttChannel` in the server repo. */
    private class ServerChannel {
        val sessions = LinkedHashMap<String, ServerSession>()
        var floorHolderId: String? = null

        fun floorFor(recipientId: String): Floor {
            val holder = floorHolderId
            return Floor(
                holderId = holder,
                holderName = holder?.let { sessions[it]?.name },
                isSelf = holder != null && holder == recipientId,
            )
        }

        fun broadcastFloor() {
            for ((id, session) in sessions) session.send(floorFor(id))
        }

        /**
         * [exceptId] is for the join case: `welcome` has to be the first message a client
         * sees, and the joiner's own count is already in it. Mirrors `PttChannel` in the
         * server repo.
         */
        fun broadcastPeers(exceptId: String? = null) {
            val message = Peers(sessions.size)
            for ((id, session) in sessions) {
                if (id != exceptId) session.send(message)
            }
        }
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleSession(
        config: Config,
    ) {
        // Same gate as the standalone relay: when a token is configured, no token means no
        // channel. Hosting on a phone does not make the Wi-Fi it is on trustworthy.
        if (config.accessToken.isNotEmpty() && !hasValidToken(config.accessToken)) {
            outgoing.send(
                Frame.Text(
                    ProtocolError(ErrorCodes.UNAUTHORIZED, "An access token is required").encode(),
                ),
            )
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }

        val version = call.request.queryParameters["v"]?.toIntOrNull()
        if (version != null && version != PROTOCOL_VERSION) {
            outgoing.send(
                Frame.Text(
                    ProtocolError(ErrorCodes.UNSUPPORTED_VERSION, "Server speaks v$PROTOCOL_VERSION")
                        .encode(),
                ),
            )
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unsupported version"))
            return
        }

        val channelId = call.parameters["channelId"]?.toIntOrNull()
        if (channelId == null || channelId !in 1..99) {
            outgoing.send(
                Frame.Text(
                    ProtocolError(ErrorCodes.INVALID_CHANNEL, "Channel must be 1..99").encode(),
                ),
            )
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid channel"))
            return
        }

        val session = ServerSession(
            id = UUID.randomUUID().toString(),
            name = call.request.queryParameters["name"]
                ?.trim()?.takeIf { it.isNotEmpty() }?.take(32) ?: "Anon",
        )

        // One writer per peer, so a slow client cannot stall the others.
        val writer = launch {
            try {
                for (frame in session.outbound) outgoing.send(frame)
            } catch (_: ClosedReceiveChannelException) {
                // Session ended.
            } catch (e: Exception) {
                PttLog.d { "Embedded writer stopped: $e" }
            }
        }

        val peers = lock.withLock {
            val channel = channels.getOrPut(channelId) { ServerChannel() }
            channel.sessions[session.id] = session
            channel.broadcastPeers(exceptId = session.id)
            channel.sessions.size
        }
        session.send(
            com.github.devapro.pttdroid.network.protocol.Welcome(
                clientId = session.id,
                channel = channelId,
                peers = peers,
                audio = AudioParams(),
            ),
        )

        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Binary -> relay(channelId, session, frame)
                    is Frame.Text -> control(channelId, session, frame.readText())
                    else -> Unit
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Client closed.
        } catch (_: CancellationException) {
            throw CancellationException("embedded session cancelled")
        } catch (e: Exception) {
            PttLog.w { "Embedded session failed: $e" }
        } finally {
            lock.withLock {
                val channel = channels[channelId]
                if (channel != null) {
                    channel.sessions.remove(session.id)
                    if (channel.floorHolderId == session.id) {
                        channel.floorHolderId = null
                        channel.broadcastFloor()
                    }
                    channel.broadcastPeers()
                    if (channel.sessions.isEmpty()) channels.remove(channelId)
                }
            }
            session.outbound.close()
            writer.cancel()
        }
    }

    /** Constant-time, for the same reason the standalone relay's check is. */
    private fun io.ktor.server.websocket.DefaultWebSocketServerSession.hasValidToken(
        expected: String,
    ): Boolean {
        val presented = call.request.headers[TOKEN_HEADER] ?: return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun relay(channelId: Int, from: ServerSession, frame: Frame.Binary) {
        val payload = frame.data
        if (payload.size > 8_192 || payload.size % 2 != 0) {
            if (from.shouldReportError()) {
                from.send(ProtocolError(ErrorCodes.FRAME_TOO_LARGE, "Bad audio frame"))
            }
            return
        }
        lock.withLock {
            val channel = channels[channelId] ?: return@withLock
            if (channel.floorHolderId != from.id) {
                if (from.shouldReportError()) {
                    from.send(ProtocolError(ErrorCodes.NOT_FLOOR_HOLDER, "You do not hold the floor"))
                }
                return@withLock
            }
            for ((id, peer) in channel.sessions) {
                if (id != from.id) peer.outbound.trySend(frame)
            }
        }
    }

    private suspend fun control(channelId: Int, from: ServerSession, text: String) {
        val message = try {
            decodeClientMessage(text)
        } catch (e: SerializationException) {
            if (from.shouldReportError()) {
                from.send(ProtocolError(ErrorCodes.MALFORMED_MESSAGE, "Unparseable message"))
            }
            return
        }

        lock.withLock {
            val channel = channels[channelId] ?: return@withLock
            when (message) {
                TalkRequest -> when (channel.floorHolderId) {
                    null -> {
                        channel.floorHolderId = from.id
                        channel.broadcastFloor()
                    }
                    from.id -> from.send(channel.floorFor(from.id))
                    else -> from.send(ProtocolError(ErrorCodes.FLOOR_BUSY, "Channel is busy"))
                }

                TalkRelease -> if (channel.floorHolderId == from.id) {
                    channel.floorHolderId = null
                    channel.broadcastFloor()
                }
            }
        }
    }
}
