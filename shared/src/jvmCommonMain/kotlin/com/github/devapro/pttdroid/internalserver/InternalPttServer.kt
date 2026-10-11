package com.github.devapro.pttdroid.internalserver

import com.github.devapro.pttdroid.PttLog
import com.github.devapro.pttdroid.network.PttEndpoint.Companion.TOKEN_HEADER
import com.github.devapro.pttdroid.network.protocol.AudioParams
import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.PROTOCOL_VERSION
import com.github.devapro.pttdroid.network.protocol.Peers
import com.github.devapro.pttdroid.network.protocol.Ping
import com.github.devapro.pttdroid.network.protocol.Pong
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.ServerMessage
import com.github.devapro.pttdroid.network.protocol.TalkRelease
import com.github.devapro.pttdroid.network.protocol.TalkRequest
import com.github.devapro.pttdroid.network.protocol.Welcome
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
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The kinds of server->client control (text) frames, one slot family each. Mirrors
 * `ControlKind` in `ptt-server`'s `PttSession` — the two relays implement one contract
 * (`ptt-server/docs/protocol.md`, Fan-out).
 */
internal enum class ControlKind { WELCOME, FLOOR, PEERS, PONG, ERROR }

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

    // `@Volatile` + the lifecycle monitor below: start/stop/isRunning are called from the
    // settings/UI thread (and the foreground service's collector), so a plain field read
    // through `isRunning` could show a stale null and let a second start race past the guard.
    @Volatile
    private var engine: EmbeddedServer<*, *>? = null
    private val lifecycleLock = Any()
    private val channels = HashMap<Int, ServerChannel>()
    private val lock = Mutex()

    /** What the running instance was started with, so a settings change can restart it. */
    @Volatile
    var runningConfig: Config? = null
        private set

    val isRunning: Boolean get() = engine != null

    /** The port, shared secret and per-channel cap one run of the embedded relay was started with. */
    data class Config(
        val port: Int,
        val accessToken: String,
        val maxSessionsPerChannel: Int = DEFAULT_MAX_SESSIONS_PER_CHANNEL,
    ) {
        companion object {
            /**
             * Mirrors `PTT_MAX_SESSIONS_PER_CHANNEL` in `ptt-server` (docs/configuration.md),
             * so a client that fills an embedded channel is turned away at the same depth it
             * would hit on the standalone relay.
             */
            const val DEFAULT_MAX_SESSIONS_PER_CHANNEL: Int = 32
        }
    }

    fun start(
        port: Int,
        accessToken: String = "",
        maxSessionsPerChannel: Int = Config.DEFAULT_MAX_SESSIONS_PER_CHANNEL,
    ) {
        synchronized(lifecycleLock) {
            if (engine != null) return // double-start guard
            val config = Config(port, accessToken, maxSessionsPerChannel)
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
    }

    fun stop() {
        synchronized(lifecycleLock) {
            val server = engine ?: return
            engine = null
            runningConfig = null
            runCatching { server.stop(gracePeriodMillis = 300, timeoutMillis = 1_000) }
                .onFailure { PttLog.d { "Embedded relay stop failed: $it" } }
            PttLog.i { "Embedded PTT relay stopped" }
        }
    }

    // --- session handling -----------------------------------------------------------------

    /**
     * Audio frames the relay has shed for the current members of channel [channelId] —
     * queued to a full outbound audio queue and dropped oldest-first. The protocol carries
     * no stats frame, so this internal accessor is how tests and diagnostics prove the audio
     * path backpressures (and counts it) while the control path survives the backlog.
     */
    internal suspend fun droppedAudioFrames(channelId: Int): Long = lock.withLock {
        channels[channelId]?.sessions?.values?.sumOf { it.dropped } ?: 0L
    }

    /**
     * One connected client, with its outbound traffic split in two paths (mirroring
     * `ptt-server`'s `PttSession`, per `ptt-server/docs/protocol.md` "Fan-out mechanism"):
     *
     * - **AUDIO** — the existing bounded [Channel] with `DROP_OLDEST`: a client that cannot
     *   keep up sheds stale voice frames and nothing else is affected. Every drop is counted
     *   ([dropped]), because a silent shed is indistinguishable from a working link.
     * - **CONTROL** — `floor` / `peers` / `welcome` / `error` / `pong`, which must survive any
     *   audio backlog. Each kind owns ONE pending slot, so one kind can never evict another:
     *   `welcome`, `floor`, `peers` and `pong` are latest-wins (a pending, not-yet-written
     *   frame of the same kind is replaced in place — by design for the state broadcasts, and
     *   harmless for `pong`, which is always an identical `pong`), and `error` is a small FIFO
     *   (cap [MAX_PENDING_CONTROL_ERRORS]) whose OLDEST entry is evicted to admit the newest,
     *   so distinct concurrent codes cannot mute each other within the window. Control memory
     *   is bounded by the slot design itself; every offer is non-suspending, so nothing here
     *   can ever block the relay's mutex holder.
     *
     * (`internal`, not `private`: `jvmCommonTest` is a friend compilation, so the split's
     * collapse/eviction semantics are unit-testable directly — no reflection seam needed.)
     *
     * Every entry point is non-suspending, so the relay's `Mutex`-held broadcast sections can
     * fan out to N peers without ever blocking on one peer's socket.
     */
    internal class ServerSession(val id: String, val name: String) {
        private val audio = Channel<Frame>(
            capacity = AUDIO_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /** What the one writer coroutine per session drains: audio frames, oldest first. */
        val audioFrames: ReceiveChannel<Frame> get() = audio

        private val droppedAudio = AtomicLong(0)

        /** Audio frames shed for this peer because its queue was full. */
        val dropped: Long get() = droppedAudio.get()

        // Occupancy of the audio queue, tracked outside it: a `DROP_OLDEST` channel's
        // `trySend` always reports success — the eviction is invisible from the API — so the
        // only way to count sheds is to know when the queue was at capacity. Sends only ever
        // happen from the relay's broadcast sections, under one shared `Mutex`, and only the
        // one writer per session dequeues, so the counter is exact except for the narrow
        // window between the writer taking a frame and acknowledging it.
        private val pendingAudio = AtomicInteger(0)

        /** Non-suspending audio enqueue; counts a shed when the queue was already full. */
        fun offerAudio(frame: Frame) {
            if (pendingAudio.getAndIncrement() >= AUDIO_QUEUE_CAPACITY) {
                pendingAudio.decrementAndGet() // occupancy stays: oldest out, new in
                droppedAudio.incrementAndGet()
            }
            audio.trySend(frame)
        }

        /** Writer hook: one frame has left the audio queue. */
        fun noteAudioDequeued() {
            pendingAudio.decrementAndGet()
        }

        // --- control path: one pending slot per kind, latest-wins on each --------------------

        private val pendingWelcome = AtomicReference<Frame?>()
        private val pendingFloor = AtomicReference<Frame?>()
        private val pendingPeers = AtomicReference<Frame?>()
        private val pendingPong = AtomicReference<Frame?>()

        /** Errors are a small FIFO rather than a single slot: concurrent codes carry distinct payloads. */
        private val pendingErrors = ArrayDeque<Frame>()

        /**
         * Conflated wake signal for the writer: at most one pending token. Because the writer
         * drains the *slots* (not the signal) on every wake, coalesced offers can neither lose
         * a wake-up nor queue duplicate ones.
         */
        private val controlSignal = Channel<Unit>(capacity = Channel.CONFLATED)

        /** A wake-up meaning "look at the control mailbox again"; the mailbox coalesces. */
        val controlNotices: ReceiveChannel<Unit> get() = controlSignal

        /**
         * Non-suspending control enqueue into [kind]'s pending slot. Never drops anything of a
         * *different* kind: each kind owns its slot. Same-kind behaviour is latest-wins
         * replacement, except `error`, which evicts its OWN oldest beyond the FIFO cap.
         *
         * Returns false only once the session is ending: after [closeOutbound] the wake path
         * is closed, so no writer can be roused. The slot write still happens — a wake token
         * buffered before the close lets the writer poll the slots one final time, so a frame
         * offered around the close may still be drained and written — but the offer can no
         * longer *guarantee* delivery, and the return value says so honestly. Mirrors
         * `PttSession.offerControl`'s return contract in the standalone (protocol.md's
         * fan-out: "An offer made after closeQueue() reports false").
         */
        fun offerControl(kind: ControlKind, frame: Frame): Boolean {
            when (kind) {
                ControlKind.WELCOME -> pendingWelcome.set(frame)
                ControlKind.FLOOR -> pendingFloor.set(frame)
                ControlKind.PEERS -> pendingPeers.set(frame)
                ControlKind.PONG -> pendingPong.set(frame)
                ControlKind.ERROR -> synchronized(pendingErrors) {
                    while (pendingErrors.size >= MAX_PENDING_CONTROL_ERRORS) {
                        pendingErrors.removeFirst() // evict-oldest-admit-newest (protocol.md table)
                    }
                    pendingErrors.addLast(frame)
                }
            }
            // On an OPEN conflated channel trySend never fails (a not-yet-consumed token
            // already guarantees the writer's next slot drain sees this frame), so failure
            // means closed.
            return controlSignal.trySend(Unit).isSuccess
        }

        /** Encode [message] into a fresh [Frame.Text] and offer it on its kind's slot. */
        fun send(message: ServerMessage): Boolean = offerControl(
            when (message) {
                is Welcome -> ControlKind.WELCOME
                is Floor -> ControlKind.FLOOR
                is Peers -> ControlKind.PEERS
                is ProtocolError -> ControlKind.ERROR
                is Pong -> ControlKind.PONG
            },
            Frame.Text(message.encode()),
        )

        /**
         * Take the highest-priority pending control frame, or null when none is pending. Drain
         * order (same as `ptt-server`): welcome first — it must be the first frame on the
         * socket — then the floor state, peer count, pong, and finally errors in arrival order.
         */
        fun pollControl(): Frame? {
            pendingWelcome.getAndSet(null)?.let { return it }
            pendingFloor.getAndSet(null)?.let { return it }
            pendingPeers.getAndSet(null)?.let { return it }
            pendingPong.getAndSet(null)?.let { return it }
            return synchronized(pendingErrors) { pendingErrors.removeFirstOrNull() }
        }

        // Separate rate-limit timestamps: one for each of the two audio hot-path codes the
        // spec scopes the limiter to, plus one shared by everything else. With a single
        // shared bucket, a client spamming oversized frames could mute "you do not hold the
        // floor" (and vice versa), hiding an error the spec promises.
        private val notFloorHolderErrorAtMs = AtomicLong(0)
        private val frameTooLargeErrorAtMs = AtomicLong(0)
        private val otherErrorAtMs = AtomicLong(0)

        /**
         * Rate limiter for protocol errors on hot paths: a client streaming audio without the
         * floor would otherwise earn one error per 40 ms frame. [code] selects the bucket, so
         * `not_floor_holder` and `frame_too_large` throttle independently of each other; all
         * other codes share one bucket.
         */
        fun shouldReportError(code: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            val bucket = when (code) {
                ErrorCodes.NOT_FLOOR_HOLDER -> notFloorHolderErrorAtMs
                ErrorCodes.FRAME_TOO_LARGE -> frameTooLargeErrorAtMs
                else -> otherErrorAtMs
            }
            val previous = bucket.get()
            if (nowMs - previous < ERROR_INTERVAL_MS) return false
            return bucket.compareAndSet(previous, nowMs)
        }

        /** Closing both paths: what lets the writer coroutine finish on its own. */
        fun closeOutbound() {
            audio.close()
            controlSignal.close()
        }

        private companion object {
            const val AUDIO_QUEUE_CAPACITY: Int = 64
            const val MAX_PENDING_CONTROL_ERRORS: Int = 8
            const val ERROR_INTERVAL_MS = 1_000L
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

        // Present-but-unparseable is as unsupported as a wrong number: `?v=abc` must not slip
        // through toIntOrNull()'s null. Semantically identical to the standalone relay's gate.
        val rawVersion = call.request.queryParameters["v"]
        if (rawVersion != null && rawVersion.toIntOrNull() != PROTOCOL_VERSION) {
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

        // One writer coroutine per peer, so a slow client cannot stall the others. It drains
        // the control slots to exhaustion with priority over the audio queue — an audio
        // backlog, even one that fills the queue and starts shedding, must never delay a
        // `floor`, `peers`, `welcome`, `error` or `pong` — then waits on either path, with the
        // control clause registered first so a wake that races incoming audio is still
        // serviced before the next frame. Same shape as `ptt-server`'s `ChannelRoutes` writer.
        // A failed write is contained here but not survivable: the socket can never speak
        // again, so the writer ends the session deliberately (the read loop would otherwise
        // keep the corpse counted as a peer — and possibly the floor holder — until the engine
        // timeout noticed).
        val sessionJob = coroutineContext.job
        val writer = launch {
            try {
                while (isActive) {
                    var control = session.pollControl()
                    while (control != null) {
                        outgoing.send(control)
                        control = session.pollControl()
                    }
                    select<Unit> {
                        session.controlNotices.onReceive {
                            // Just a wake; the loop above drains the slots.
                        }
                        session.audioFrames.onReceive { frame ->
                            session.noteAudioDequeued()
                            outgoing.send(frame)
                        }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // Session ended; normal.
            } catch (_: CancellationException) {
                throw CancellationException("embedded writer cancelled")
            } catch (e: Exception) {
                PttLog.d { "Embedded writer failed, ending session ${session.id}: $e" }
                session.closeOutbound()
                sessionJob.cancel(CancellationException("writer for session ${session.id} failed", e))
            }
        }

        // Checked under the same lock that adds the session, so a burst of simultaneous joins
        // cannot all read "not full yet" — mirrors `ChannelRegistry.tryJoinChannel` upstream.
        // The `welcome` is queued inside that same lock hold, BEFORE the peers broadcast:
        // `ServerSession.send` is non-suspending, so the join itself puts welcome into the
        // joiner's dedicated first-drained slot while no other frame can interleave against
        // it — mirroring `PttChannel.tryJoin` and making "welcome is the first frame on the
        // socket" structural, not merely likely (it used to be queued after the lock
        // released, where a racing floor broadcast could precede it).
        val joined = lock.withLock {
            val existing = channels[channelId]
            if (existing != null && existing.sessions.size >= config.maxSessionsPerChannel) {
                false
            } else {
                val channel = channels.getOrPut(channelId) { ServerChannel() }
                channel.sessions[session.id] = session
                session.send(
                    Welcome(
                        clientId = session.id,
                        channel = channelId,
                        peers = channel.sessions.size,
                        audio = AudioParams(),
                    ),
                )
                channel.broadcastPeers(exceptId = session.id)
                true
            }
        }
        if (!joined) {
            // The joiner was never added, so it consumes no slot and the finally-block cleanup
            // below (which would broadcast a phony peer-count change) must not run for it.
            session.closeOutbound()
            writer.cancel()
            outgoing.send(
                Frame.Text(
                    ProtocolError(
                        ErrorCodes.CHANNEL_FULL,
                        "Channel $channelId already has ${config.maxSessionsPerChannel} listeners",
                    ).encode(),
                ),
            )
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "channel full"))
            return
        }

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
            // The job running this may already be cancelled — the writer's deliberate
            // teardown above, or the engine ending the socket — and `lock.withLock` suspends,
            // which under a cancelled job throws CancellationException the instant it tries to
            // acquire. Then the session would never leave its channel: a ghost peer forever,
            // and if it died holding the floor, nobody can ever take it. The whole cleanup
            // therefore runs NonCancellable (mirroring `ChannelRoutes`' finally). Closing an
            // already-closed session and re-cancelling an already-cancelled writer are no-ops,
            // so this body and the writer's teardown cannot double-run into anything worse
            // than idempotence.
            withContext(NonCancellable) {
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
                session.closeOutbound()
                writer.cancel()
            }
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

    /**
     * Relays an audio frame to every other session on the channel.
     *
     * Each peer gets its OWN [Frame.Binary] instance. Ktor's JVM [Frame] wraps its payload in a
     * `buffer: ByteBuffer` field created once per instance (`ByteBuffer.wrap(data)`), and that
     * field carries mutable read state (position/limit). Handing the SAME `Frame.Binary` to
     * every peer's outbound queue means every peer's writer coroutine — each running
     * concurrently, one per session — reads that one shared `ByteBuffer` while serializing to
     * its own socket, corrupting or truncating whichever write loses the race. This only shows
     * up at 3+ participants (2+ other peers racing), which is why a 2-user channel always
     * looked fine. Mirrors the identical fix in `ptt-server`'s `PttChannel.relayAudio`.
     *
     * No array copy is needed: [frame]'s `data` is a fresh `ByteArray` produced once per
     * inbound frame by Ktor's `WebSocketReader`/`SimpleFrameCollector` (never pooled or reused
     * across frames), and constructing a new `Frame.Binary` per peer gives each one its own
     * `ByteBuffer.wrap(data)` with an independent position/limit — reading through a
     * `ByteBuffer` never mutates the backing array. Sharing the payload array across the N new
     * instances is therefore safe and keeps this per-audio-frame path allocation-light (one
     * `Frame.Binary` per peer, no `ByteArray` copies).
     */
    private suspend fun relay(channelId: Int, from: ServerSession, frame: Frame.Binary) {
        val payload = frame.data
        if (payload.size > 8_192 || payload.size % 2 != 0) {
            if (from.shouldReportError(ErrorCodes.FRAME_TOO_LARGE)) {
                from.send(ProtocolError(ErrorCodes.FRAME_TOO_LARGE, "Bad audio frame"))
            }
            return
        }
        lock.withLock {
            val channel = channels[channelId] ?: return@withLock
            if (channel.floorHolderId != from.id) {
                if (from.shouldReportError(ErrorCodes.NOT_FLOOR_HOLDER)) {
                    from.send(ProtocolError(ErrorCodes.NOT_FLOOR_HOLDER, "You do not hold the floor"))
                }
                return@withLock
            }
            for ((id, peer) in channel.sessions) {
                if (id != from.id) peer.offerAudio(Frame.Binary(frame.fin, payload))
            }
        }
    }

    private suspend fun control(channelId: Int, from: ServerSession, text: String) {
        val message = try {
            decodeClientMessage(text)
        } catch (e: SerializationException) {
            if (from.shouldReportError(ErrorCodes.MALFORMED_MESSAGE)) {
                from.send(ProtocolError(ErrorCodes.MALFORMED_MESSAGE, "Unparseable message"))
            }
            return
        }

        // Answered before the channel lookup and outside the lock: a liveness probe depends on
        // no channel state, and must not queue behind a floor change.
        if (message is Ping) {
            from.send(Pong)
            return
        }

        lock.withLock {
            val channel = channels[channelId] ?: return@withLock
            when (message) {
                Ping -> Unit // answered above

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
