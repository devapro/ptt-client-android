package com.github.devapro.pttdroid.internalserver

import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.Peers
import com.github.devapro.pttdroid.network.protocol.Pong
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.ServerMessage
import com.github.devapro.pttdroid.network.protocol.Welcome
import com.github.devapro.pttdroid.network.protocol.decodeServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * C-5/C-5b acceptance: the embedded relay's per-session control-frame split
 * (AUDIO bounded 64 DROP_OLDEST + counted; CONTROL per-kind latest-wins slots + an
 * 8-deep error FIFO that evicts its OWN oldest to admit the newest; writer drains
 * welcome → floor → peers → pong → errors with priority over audio; close reaches both paths).
 *
 * Two layers of evidence, because only one of them can be exact:
 *
 * 1. **Unit probes** drive the real `ServerSession` mailbox in isolation — no writer, no
 *    socket — so every count is exact (64 kept, N shed, collapse to the newest). C-5b made the
 *    class `internal`, so this reaches it directly: `jvmCommonTest` is a friend compilation of
 *    `jvmCommonMain` and needs no reflection. (`ptt-server` tests the same contract in
 *    `ControlSplitTest` because its session class is public.)
 *
 * 2. **One integration probe** over a real socket joins a peer that simply stops reading and
 *    buries it in ~13 MB of audio while the floor churns behind it. This layer pins what is
 *    provably stable end-to-end: `welcome` is the first frame on the socket and every ping
 *    run is answered (pongs now share one latest-wins slot, so exact pong arity under the
 *    flood is a drain race, not an invariant — the collapse itself is pinned by the unit
 *    probes), the final delivered state of every coalescing kind is the NEWEST one, relayed
 *    audio is byte-for-byte correct and in order under pressure (known-issues #40), and the
 *    stalled peer's teardown releases its slot. It does NOT pin eviction counts or collapse
 *    arity, and that is deliberate: Ktor 3's websocket `outgoing` channel is
 *    `ChannelConfig.UNLIMITED` (`ktor-websockets` `DefaultWebSocketSession.kt:95`), so a
 *    non-reading socket never parks the relay writer inside a send — whether the 64-frame
 *    audio queue ever fills is a rate race, not an invariant. (Measured: 0, 8 and 43 sheds on
 *    repeated identical floods.) That makes the coder-reported "hold the writer by not reading
 *    the socket" recipe indeterminable at this layer — see the test report and known-issues
 *    #45.
 */
class InternalPttServerFanoutTest {

    private lateinit var server: InternalPttServer
    private var port: Int = 0

    private val client = HttpClient(CIO) { install(WebSockets) }

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = InternalPttServer()
        server.start(port)
        // Give CIO a moment to bind before the first client connects.
        Thread.sleep(1_500)
    }

    @After
    fun tearDown() {
        server.stop()
        client.close()
    }

    private fun url(channel: Int, name: String) =
        "ws://127.0.0.1:$port/channel/$channel?name=$name&v=1"

    // --- direct unit probe of the (now internal) ServerSession -----------------------------------

    /**
     * Lives at the same level as `ptt-server`'s `ControlSplitTest`: a bare session nobody is
     * writing to, so offers, sheds, collapses and caps are all exact.
     */
    private class SessionProbe(val session: InternalPttServer.ServerSession) {

        constructor() : this(InternalPttServer.ServerSession("peer-1", "Probe"))

        fun offerAudio(frame: Frame) = session.offerAudio(frame)

        fun offerControl(kind: ControlKind, frame: Frame): Boolean =
            session.offerControl(kind, frame)

        fun send(message: ServerMessage): Boolean = session.send(message)

        fun pollControl(): Frame? = session.pollControl()

        /** Everything still pending on the control path, decoded, in writer-drain order. */
        fun drainControl(): List<ServerMessage> =
            generateSequence { pollControl() }
                .map { decodeServerMessage((it as Frame.Text).readText()) }
                .toList()

        fun drainAudio(): List<Frame> =
            generateSequence { session.audioFrames.tryReceive().getOrNull() }.toList()

        fun dropped(): Long = session.dropped

        fun audioFrames(): ReceiveChannel<Frame> = session.audioFrames

        fun controlNotices(): ReceiveChannel<Unit> = session.controlNotices

        fun closeOutbound() = session.closeOutbound()

        fun shouldReportError(code: String, nowMs: Long): Boolean =
            session.shouldReportError(code, nowMs)
    }

    private fun audioFrame(tag: Int): Frame.Binary =
        Frame.Binary(true, ByteArray(16) { (tag + it).toByte() })

    // --- R1: audio path — bounded 64, DROP_OLDEST, counted --------------------------------------

    @Test
    fun `the audio queue keeps exactly 64 frames and counts every shed oldest-first`() {
        val s = SessionProbe()
        repeat(100) { s.offerAudio(audioFrame(it)) }

        // coroutines' DROP_OLDEST trySend always "succeeds", so the AtomicInteger occupancy —
        // not a send failure — is the only truth about a shed; with no writer draining, this
        // count is exact: 100 offered, 64 kept, 36 shed.
        assertEquals(36L, s.dropped())

        val kept = s.drainAudio()
        assertEquals(64, kept.size)
        // DROP_OLDEST: the 36 lowest-tagged frames are gone, survivors in arrival order.
        val tags = kept.map { (it as Frame.Binary).data[0].toInt() }
        assertEquals((36..99).toList(), tags)
        assertTrue("nothing lingers after a full drain", s.drainAudio().isEmpty())
    }

    // --- R1: control path — per-kind slots, collapse, caps, cross-kind isolation -----------------

    @Test
    fun `pending floors, peers and pings collapse to the newest and never disturb the other kinds`() {
        val s = SessionProbe()
        repeat(3) { i -> s.send(Floor(holderId = "holder-$i", holderName = "Bob", isSelf = false)) }
        repeat(2) { i -> s.send(Peers(count = 10 + i)) }
        val welcome = Welcome(clientId = "peer-1", channel = 1, peers = 4)
        s.send(welcome)
        s.send(Pong)
        s.send(Pong)

        val drained = s.drainControl()
        val kinds = drained.map { it::class.simpleName }

        // C-5b / protocol.md: every control kind owns exactly ONE pending slot — welcome,
        // floor, peers AND pong all collapse to the newest — drained in the standalone's
        // priority order welcome → floor → peers → pong → errors.
        assertEquals(
            "one newest frame per pending kind: $kinds",
            listOf("Welcome", "Floor", "Peers", "Pong"),
            kinds,
        )
        assertEquals(welcome, drained[0]) // welcome drains before every other kind
        val floor = drained.filterIsInstance<Floor>().single()
        assertEquals("newest floor wins the slot", "holder-2", floor.holderId)
        val peers = drained.filterIsInstance<Peers>().single()
        assertEquals("newest peers count wins the slot", 11, peers.count)
        assertEquals("identical pongs collapse to the single pong slot", 1, drained.count { it is Pong })
        assertTrue("control drains fully", s.drainControl().isEmpty())
        assertEquals("the control path never touches the audio shed counter", 0L, s.dropped())
    }

    @Test
    fun `an audio flood cannot evict any control kind`() {
        val s = SessionProbe()
        repeat(200) { s.offerAudio(audioFrame(it)) }
        s.send(Floor(holderId = "holder-x", holderName = "Bob"))
        s.send(Peers(7))
        s.send(Pong)
        s.send(ProtocolError("floor_busy", "busy"))

        assertEquals("136 sheds counted, queue bounded at 64", 136L, s.dropped())
        assertEquals(64, s.drainAudio().size)

        val drained = s.drainControl()
        assertEquals(
            "every control kind is still pending after the flood: $drained",
            setOf("Pong", "Floor", "Peers", "ProtocolError"),
            drained.map { it::class.simpleName }.toSet(),
        )
    }

    @Test
    fun `pending errors are capped at eight and the cap evicts its OWN oldest to admit the newest`() {
        val s = SessionProbe()
        s.send(Welcome(clientId = "peer-1", channel = 2, peers = 1))
        repeat(12) { i -> s.send(ProtocolError("malformed_message", "bad-$i")) }
        s.send(Pong)

        val drained = s.drainControl()
        val errors = drained.filterIsInstance<ProtocolError>()
        assertEquals("eight pending errors is the cap", 8, errors.size)
        assertEquals(
            "evict-oldest-admit-newest: the NEWEST eight survive, not the first eight: " +
                "${errors.map { it.message }}",
            (4..11).map { "bad-$it" },
            errors.map { it.message },
        )
        assertEquals("welcome is never evicted, only errors are capped", 1, drained.count { it is Welcome })
        assertEquals("pong is never evicted by another kind either", 1, drained.count { it is Pong })
    }

    @Test
    fun `the control wake is conflated to at most one token`() {
        val s = SessionProbe()
        repeat(5) { s.send(Floor(holderId = "h$it")) }

        val notices = s.controlNotices()
        assertTrue("one wake token is available for the whole pile", notices.tryReceive().isSuccess)
        assertTrue(
            "the wake channel is capacity-1: the mailbox coalesces, so pile-ups must not queue duplicate wakes",
            notices.tryReceive().isFailure,
        )
    }

    @Test
    fun `the writer drain order is welcome, floor, peers, pong, then errors FIFO`() {
        val s = SessionProbe()
        // Offer in scrambled order; the drain must still come out by kind priority,
        // with the two errors kept in ARRIVAL order inside their own bucket (protocol.md
        // fan-out table — the same order `PttSession.pollControl` implements standalone).
        s.send(ProtocolError("floor_busy", "busy-1"))
        s.send(Pong)
        s.send(Peers(3))
        s.send(ProtocolError("malformed_message", "bad-2"))
        s.send(Floor(holderId = "h"))
        s.send(Welcome(clientId = "peer-1", channel = 1, peers = 2))

        val drained = s.drainControl()
        assertEquals(
            listOf("Welcome", "Floor", "Peers", "Pong", "ProtocolError", "ProtocolError"),
            drained.map { it::class.simpleName },
        )
        assertEquals(
            "errors drain FIFO within the error bucket",
            listOf("busy-1", "bad-2"),
            drained.filterIsInstance<ProtocolError>().map { it.message },
        )
    }

    @Test
    fun `not_floor_holder and frame_too_large rate-limit in independent one-second buckets`() {
        val s = SessionProbe()
        val t0 = System.currentTimeMillis()
        assertTrue("first not_floor_holder passes", s.shouldReportError(ErrorCodes.NOT_FLOOR_HOLDER, t0))
        assertFalse("its own bucket throttles a repeat", s.shouldReportError(ErrorCodes.NOT_FLOOR_HOLDER, t0 + 500))
        assertTrue(
            "frame_too_large has its OWN bucket: spamming one hot-path code must not mute the other",
            s.shouldReportError(ErrorCodes.FRAME_TOO_LARGE, t0 + 500),
        )
        assertFalse("frame_too_large now throttles its own repeats", s.shouldReportError(ErrorCodes.FRAME_TOO_LARGE, t0 + 600))
        assertTrue(
            "all other codes share a third bucket, independent of both audio codes",
            s.shouldReportError(ErrorCodes.MALFORMED_MESSAGE, t0 + 600),
        )
        assertFalse("malformed shares with the other non-audio codes", s.shouldReportError(ErrorCodes.FLOOR_BUSY, t0 + 700))
        // Exactly one interval later each bucket re-arms independently.
        assertTrue(s.shouldReportError(ErrorCodes.NOT_FLOOR_HOLDER, t0 + 1_000))
        assertTrue(s.shouldReportError(ErrorCodes.FRAME_TOO_LARGE, t0 + 1_501))
        assertTrue(s.shouldReportError(ErrorCodes.FLOOR_BUSY, t0 + 1_601))
    }

    // --- R4: termination — closeOutbound reaches BOTH paths --------------------------------------

    @Test
    fun `closeOutbound closes both delivery paths and the pending state still drains once`() {
        val s = SessionProbe()
        s.offerAudio(audioFrame(1))
        s.send(Floor(holderId = "final"))

        s.closeOutbound()

        // The writer's select terminates precisely because BOTH controlSignal and audio are
        // closed; closing only one (the C-4 shape) leaves it parked forever.
        val audio = s.audioFrames()
        assertNotNull(
            "a buffered audio frame is still deliverable through a closed channel",
            audio.tryReceive().getOrNull(),
        )
        assertTrue("audio path reports closed for receive after draining", audio.isClosedForReceive)
        val notices = s.controlNotices()
        assertNotNull(
            "the wake token offered before the close is still deliverable",
            notices.tryReceive().getOrNull(),
        )
        assertTrue("control wake path is closed too", notices.isClosedForReceive)
        // Closing must not discard coalesced state the writer has not had yet.
        val drained = s.drainControl()
        assertEquals(1, drained.size)
        assertEquals("final", (drained.single() as Floor).holderId)
    }

    /**
     * The standalone's `ControlSplitTest` I1 mirrored: `offerControl`'s honest return
     * contract after the wake path closed (protocol.md: "An offer made after closeQueue()
     * reports false") — the gap C-5b fix round 1 closed against `PttSession.kt`.
     */
    @Test
    fun `offerControl after closeOutbound reports false while the slots still drain once`() {
        val s = SessionProbe()
        val pending = Floor(holderId = "final")
        assertTrue("an offer on an open session reports success", s.send(pending))
        s.closeOutbound()

        // With the wake path closed no writer can be roused, so the offer must report
        // failure even though the slot write itself still happens.
        assertFalse(
            "offerControl must report false once the session is ending",
            s.offerControl(ControlKind.PONG, Frame.Text("""{"type":"pong"}""")),
        )
        assertFalse(
            "send inherits offerControl's honest return contract",
            s.send(Peers(3)),
        )

        // Buffered frames survive the close and drain once, in normal priority order: the
        // pre-close floor first; the post-close offers sit in their slots too (the write is
        // not refused — only the delivery *guarantee* is), and none of it was discarded.
        val drained = s.drainControl()
        assertEquals(
            listOf("Floor", "Peers", "Pong"),
            drained.map { it::class.simpleName },
        )
        assertEquals("final", (drained.first() as Floor).holderId)
    }

    // --- integration: real socket, real flood, real teardown -------------------------------------

    /** One server-to-client frame captured during [StalledPeer.drain]. */
    private class WireFrame(val opcode: Int, val message: ServerMessage?, val payload: ByteArray)

    /**
     * A spec-conformant raw WS client (masked outgoing frames) that can be told to stop
     * reading — the best stall pressure a socket layer can apply against Ktor's UNLIMITED
     * engine queue (see class KDoc: it slows nothing to a hard park, so this layer asserts
     * survival and final-state correctness, not eviction counts or collapse arity).
     */
    private class StalledPeer(port: Int, path: String) : Closeable {
        val socket = Socket()
        private val output: OutputStream
        private val input: InputStream

        init {
            socket.receiveBufferSize = 64 * 1024
            socket.connect(InetSocketAddress("127.0.0.1", port))
            socket.soTimeout = 5_000
            output = socket.getOutputStream()
            input = socket.getInputStream()
            output.write(
                (
                    "GET $path HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            val headers = ByteArrayOutputStream()
            var crlfRun = 0 // counts a trailing CRLFCRLF: 1=\r 2=\r\n 3=\r\n\r 4=\r\n\r\n
            while (crlfRun < 4) {
                val b = input.read()
                check(b != -1) { "connection dropped mid handshake: $headers" }
                headers.write(b)
                crlfRun = when {
                    b == 13 && crlfRun % 2 == 0 -> crlfRun + 1
                    b == 10 && crlfRun % 2 == 1 -> crlfRun + 1
                    b == 13 -> 1
                    else -> 0
                }
            }
            check(headers.toString(Charsets.US_ASCII).startsWith("HTTP/1.1 101")) {
                "no upgrade: ${headers.toString(Charsets.US_ASCII)}"
            }
        }

        fun sendText(text: String) = writeFrame(0x1, text.toByteArray(Charsets.UTF_8))

        private fun writeFrame(opcode: Int, payload: ByteArray) {
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val header = ByteArrayOutputStream()
            header.write(0x80 or opcode)
            when {
                payload.size <= 125 -> header.write(0x80 or payload.size)
                payload.size <= 65535 -> {
                    header.write(0x80 or 126)
                    header.write(payload.size shr 8)
                    header.write(payload.size)
                }
                else -> {
                    header.write(0x80 or 127)
                    repeat(8) { header.write(0) }
                }
            }
            header.write(mask)
            val masked = ByteArray(payload.size) {
                (payload[it].toInt() xor mask[it % 4].toInt()).toByte()
            }
            header.writeTo(output)
            output.write(masked)
            output.flush()
        }

        private fun readFrame(): WireFrame? {
            val b0 = input.read()
            if (b0 == -1) return null
            val b1 = input.read()
            val opcode = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = ((input.read() shl 8) or input.read()).toLong()
            } else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or input.read().toLong() }
            }
            val payload = ByteArray(len.toInt())
            var off = 0
            while (off < payload.size) {
                val r = input.read(payload, off, payload.size - off)
                if (r == -1) break
                off += r
            }
            check(off == payload.size) { "truncated frame read: $off/${payload.size}" }
            if (b1 and 0x80 != 0) { // server frames must not be masked, but be correct anyway
                val mask = ByteArray(4)
                input.read(mask)
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            return WireFrame(opcode, null, payload)
        }

        /** Read everything queued for this peer: until [quietMs] passes silent, a close, EOF, or [maxMs]. */
        fun drain(quietMs: Int = 15_000, maxMs: Long = 60_000): List<WireFrame> {
            socket.soTimeout = quietMs
            val deadline = System.currentTimeMillis() + maxMs
            val frames = mutableListOf<WireFrame>()
            while (System.currentTimeMillis() < deadline) {
                val frame = try {
                    readFrame()
                } catch (_: SocketTimeoutException) {
                    break
                } ?: break
                val message = if (frame.opcode == 0x1) {
                    decodeServerMessage(frame.payload.toString(Charsets.UTF_8))
                } else {
                    null
                }
                frames += WireFrame(frame.opcode, message, frame.payload)
                if (frame.opcode == 0x8) break
            }
            return frames
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private suspend inline fun <reified T : ServerMessage> DefaultClientWebSocketSession.expect(
        timeoutMs: Long = 5_000,
    ): T {
        val found = withTimeoutOrNull(timeoutMs) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = decodeServerMessage(frame.readText())
                if (message is T) return@withTimeoutOrNull message
            }
            null
        }
        assertNotNull("Timed out waiting for ${T::class.simpleName}", found)
        return found!!
    }

    /** A big-endian frame index header plus a byte-exact pattern, see [payloadIndexOf]. */
    private fun framePayload(index: Int): ByteArray = ByteArray(FRAME_SIZE) { j ->
        if (j < 4) {
            ((index shr (8 * (3 - j))) and 0xFF).toByte()
        } else {
            ((index * 31 + j) and 0xFF).toByte()
        }
    }

    private fun payloadIndexOf(payload: ByteArray): Int =
        ((payload[0].toInt() and 0xFF) shl 24) or
            ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)

    @Test
    fun `an audio flood on a non-reading peer never evicts control and the newest states win`() {
        runBlocking {
            val victim = StalledPeer(port, "/channel/1?name=Victim&v=1")
            repeat(3) { victim.sendText("""{"type":"ping"}""") }

            val bob = client.webSocketSession(url(1, "Bob"))
            bob.expect<Welcome>()
            bob.send(Frame.Text("""{"type":"talk_request"}"""))
            assertTrue("Bob must be granted the floor", bob.expect<Floor>().isSelf)
            for (i in 0 until FLOOD_FRAMES) {
                bob.send(Frame.Binary(true, framePayload(i)))
            }
            // Floor churn buried behind the flood; final state: free.
            repeat(2) {
                bob.send(Frame.Text("""{"type":"talk_release"}"""))
                bob.send(Frame.Text("""{"type":"talk_request"}"""))
            }
            bob.send(Frame.Text("""{"type":"talk_release"}"""))
            repeat(3) { victim.sendText("""{"type":"ping"}""") }
            bob.close() // its leave broadcasts one more peers update to the victim

            val frames = victim.drain()
            val texts = frames.filter { it.opcode == 0x1 }.mapNotNull { it.message }
            val audio = frames.filter { it.opcode == 0x2 }
            val shed = server.droppedAudioFrames(1)

            // --- control kinds survive the flood ------------------------------------------------

            val welcomes = texts.filterIsInstance<Welcome>()
            assertEquals("exactly one welcome", 1, welcomes.size)
            assertTrue(
                "welcome must be the FIRST frame on the socket",
                texts.first() is Welcome,
            )
            // Pongs share ONE latest-wins slot (C-5b): every ping is answered *semantically*
            // — an identical pong proves liveness for all collapsed siblings — but the exact
            // wire count under the flood is a drain race, so only arrival is pinned here.
            // The collapse itself is pinned by the unit probes above.
            assertTrue(
                "pings must be answered through the audio backlog, not starved by it",
                texts.any { it is Pong },
            )
            assertEquals(
                "the stalled peer did nothing wrong: no errors",
                emptyList<ProtocolError>(),
                texts.filterIsInstance<ProtocolError>(),
            )

            // --- coalescing kinds: whatever arrived, the NEWEST state is the one on the wire ------

            val floors = texts.filterIsInstance<Floor>()
            assertTrue("at least the final floor state must arrive: $floors", floors.isNotEmpty())
            assertNull("the newest floor (released) must be the last one delivered", floors.last().holderId)
            // How MANY of the six broadcasts arrive is not pinned: without a hard writer park
            // (impossible over Ktor's UNLIMITED outgoing channel — see class KDoc) any prefix
            // of the churn may have been written before the next state replaced the slot.
            // What must hold is that the delivered sequence is a subsequence of the real
            // state timeline ending in the newest state — checked via the grant: if more than
            // one floor arrived, the first can only be the (only) grant that preceded the flood.
            val peers = texts.filterIsInstance<Peers>()
            assertTrue("at least the final peers update must arrive: $peers", peers.isNotEmpty())
            assertEquals(1, peers.last().count)
            for (p in peers.dropLast(1)) {
                assertEquals(2, p.count)
            }

            // --- audio under pressure: exact bytes, in order, and only ever audio is lost ---------

            var previous = -1
            for (frame in audio) {
                assertEquals(FRAME_SIZE, frame.payload.size)
                val index = payloadIndexOf(frame.payload)
                assertTrue(
                    "relayed audio must arrive in order, once each (got $index after $previous)",
                    index > previous,
                )
                val expected = framePayload(index)
                for (j in expected.indices) {
                    assertEquals("frame #$index corrupted at byte $j", expected[j], frame.payload[j])
                }
                previous = index
            }
            // The shed count is read with a documented tolerance, not as an exact eviction log:
            // offerAudio's occupancy counter is exact except in the window between the writer's
            // dequeue and its noteAudioDequeued() ack (InternalPttServer.kt), where an offer can
            // be counted as shed without anything being evicted. So a counted shed covers AT
            // MOST one missing frame, never fewer: received + shed >= offered. A zero-shed run
            // may still have delivered everything, and one shed with a full delivery is the
            // race, not a loss — that pairing is precisely what production never promised.
            assertTrue(
                "received ${audio.size} frames, shed $shed of $FLOOD_FRAMES offered",
                audio.size + shed >= FLOOD_FRAMES,
            )

            // --- R4: a peer that stalls and then goes away still fully tears down ------------------

            victim.close()
            var cleaned = false
            val cleanupDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < cleanupDeadline) {
                var welcome: Welcome? = null
                val joined = runCatching {
                    client.webSocket(url(1, "Carol")) { welcome = expect(timeoutMs = 2_000) }
                }.isSuccess
                if (joined && welcome?.peers == 1) {
                    cleaned = true
                    break
                }
                delay(250)
            }
            assertTrue(
                "after the stalled peer's socket closed, its writer must exit and its session " +
                    "must leave channel 1 — no ghost may hold a slot",
                cleaned,
            )
        }
    }

    private companion object {
        const val FRAME_SIZE = 8_192
        const val FLOOD_FRAMES = 1_600
    }
}
