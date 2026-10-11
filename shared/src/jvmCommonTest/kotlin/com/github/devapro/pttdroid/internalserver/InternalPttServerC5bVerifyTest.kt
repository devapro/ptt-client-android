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
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Independent C-5b spec verification of the embedded relay's fan-out split against
 * `ptt-server/docs/protocol.md` and `PttSession.kt` (the standalone contract source).
 *
 * Written by the verification pass, NOT the coder's `InternalPttServerFanoutTest`:
 *  - A1: error FIFO cap 8 evicts its OWN oldest (newest 8 survive, arrival order)
 *  - A2: pong = ONE slot; 1000 pending pongs collapse to at most 1 drained frame
 *  - A4: pollControl drain order welcome -> floor -> peers -> pong -> errors (FIFO)
 *  - A5: per-code rate buckets: `frame_too_large` then `not_floor_holder` at t+1 both admitted
 *  - A3: welcome queued inside the join lock hold — wire-race: join while another client
 *        churns floor requests, 50x; first text frame is Welcome, exactly once
 *  - A6: dead floor-holder's session leaves the channel — no ghost peer, floor freed
 */
class InternalPttServerC5bVerifyTest {

    // --- A1/A2/A4/A5: direct unit probes through the internal ServerSession seam ---------------

    private fun session() = InternalPttServer.ServerSession("s-1", "Probe")

    @Test
    fun `A1 error FIFO below the cap keeps everything in arrival order`() {
        val s = session()
        repeat(8) { i -> assertTrue(s.send(ProtocolError("malformed_message", "e$i"))) }
        val texts = generateSequence { s.pollControl() }
            .map { (it as Frame.Text).readText() }
            .toList()
        assertEquals(
            "8 errors is AT the cap — nothing evicted, arrival order kept",
            (0..7).map { "e$it" },
            texts.map { decodeServerMessage(it).let { m -> (m as ProtocolError).message } },
        )
    }

    @Test
    fun `A1 error FIFO at cap 8 evicts its OWN oldest so the newest 8 survive in arrival order`() {
        val s = session()
        // Also hold a welcome, a floor, a peers and a pong pending: none may be evicted.
        s.send(Welcome(clientId = "s-1", channel = 1, peers = 1))
        s.send(Floor(holderId = "h"))
        s.send(Peers(3))
        s.send(Pong)
        repeat(20) { i -> s.send(ProtocolError("malformed_message", "err-$i")) }

        val drained = generateSequence { s.pollControl() }
            .map { decodeServerMessage((it as Frame.Text).readText()) }
            .toList()

        val errors = drained.filterIsInstance<ProtocolError>()
        assertEquals("cap is 8", 8, errors.size)
        assertEquals(
            "evict-OWN-oldest: the NEWEST eight (err-12..err-19) survive, in arrival order",
            (12..19).map { "err-$it" },
            errors.map { it.message },
        )
        assertEquals("welcome survives error capping", 1, drained.count { it is Welcome })
        assertEquals("floor survives error capping", 1, drained.count { it is Floor })
        assertEquals("peers survives error capping", 1, drained.count { it is Peers })
        assertEquals("pong survives error capping", 1, drained.count { it is Pong })
        assertNull("drain is exhausted", s.pollControl())
    }

    @Test
    fun `A2 a thousand pending pongs collapse to one drained frame and bound control memory`() {
        val s = session()
        repeat(1_000) { i ->
            assertTrue(
                "pong offer on an OPEN session reports success",
                s.offerControl(ControlKind.PONG, Frame.Text("""{"type":"pong","i":$i}""")),
            )
        }
        val drained = generateSequence { s.pollControl() }.toList()
        assertEquals("1000 offers leave at most ONE pending pong on the wire path", 1, drained.size)
        val text = (drained.single() as Frame.Text).readText()
        assertEquals(
            "the single surviving pong is the newest offer",
            """{"type":"pong","i":999}""",
            text,
        )
    }

    @Test
    fun `A2 pong collapse never evicts the other kinds`() {
        val s = session()
        s.send(Welcome(clientId = "s-1", channel = 1, peers = 1))
        s.send(Floor(holderId = "h"))
        s.send(Peers(2))
        s.send(ProtocolError(ErrorCodes.FLOOR_BUSY, "busy"))
        repeat(1_000) { s.send(Pong) }
        val kinds = generateSequence { s.pollControl() }
            .map { decodeServerMessage((it as Frame.Text).readText())::class.simpleName }
            .toList()
        assertEquals(listOf("Welcome", "Floor", "Peers", "Pong", "ProtocolError"), kinds)
    }

    @Test
    fun `A4 pollControl drains welcome floor peers pong then errors in FIFO order`() {
        val s = session()
        // Offer in fully scrambled order; every kind pending at once.
        s.offerControl(ControlKind.ERROR, Frame.Text("""{"type":"error","code":"floor_busy","message":"first"}"""))
        s.offerControl(ControlKind.PONG, Frame.Text("""{"type":"pong"}"""))
        s.offerControl(ControlKind.ERROR, Frame.Text("""{"type":"error","code":"malformed_message","message":"second"}"""))
        s.offerControl(ControlKind.PEERS, Frame.Text("""{"type":"peers","count":9}"""))
        s.offerControl(ControlKind.WELCOME, Frame.Text("""{"type":"welcome","clientId":"s-1","channel":1,"peers":1}"""))
        s.offerControl(ControlKind.FLOOR, Frame.Text("""{"type":"floor","holderId":null,"holderName":null,"isSelf":false}"""))

        val drained = generateSequence { s.pollControl() }
            .map { decodeServerMessage((it as Frame.Text).readText()) }
        val byType = drained.associateBy { it::class.simpleName }
        // Order check via index sequence.
        val order = generateSequence { s.pollControl() }.count() // must be 0 — everything drained above
        assertEquals("exhausted after full drain", 0, order)

        // Re-drain from a fresh session to assert exact ORDER (the map loses it).
        val s2 = session()
        s2.offerControl(ControlKind.PONG, Frame.Text("""{"type":"pong"}"""))
        s2.offerControl(ControlKind.ERROR, Frame.Text("""{"type":"error","code":"floor_busy","message":"e1"}"""))
        s2.offerControl(ControlKind.PEERS, Frame.Text("""{"type":"peers","count":1}"""))
        s2.offerControl(ControlKind.FLOOR, Frame.Text("""{"type":"floor","holderId":null,"holderName":null,"isSelf":false}"""))
        s2.offerControl(ControlKind.WELCOME, Frame.Text("""{"type":"welcome","clientId":"x","channel":1,"peers":1}"""))
        s2.offerControl(ControlKind.ERROR, Frame.Text("""{"type":"error","code":"malformed_message","message":"e2"}"""))
        val seq = generateSequence { s2.pollControl() }
            .map { decodeServerMessage((it as Frame.Text).readText()) }
            .toList()
        assertEquals(
            listOf("Welcome", "Floor", "Peers", "Pong", "ProtocolError", "ProtocolError"),
            seq.map { it::class.simpleName },
        )
        assertEquals(
            "errors keep FIFO arrival order inside their bucket",
            listOf("e1", "e2"),
            seq.filterIsInstance<ProtocolError>().map { it.message },
        )
        assertNotNull(byType["Welcome"])
    }

    @Test
    fun `A5 frame_too_large and not_floor_holder rate-limit in independent buckets (masking scenario)`() {
        val s = session()
        val t = 10_000_000L // far from epoch 0 so the pristine 0-buckets admit
        assertTrue("frame_too_large admitted at t", s.shouldReportError(ErrorCodes.FRAME_TOO_LARGE, t))
        assertFalse("frame_too_large throttled inside its own 1s window", s.shouldReportError(ErrorCodes.FRAME_TOO_LARGE, t + 1))
        // The standalone's masking scenario: with ONE shared bucket this second call would be
        // muted by the first. The contract source (PttSession.kt) gives each hot-path code its
        // own bucket, so it MUST be admitted.
        assertTrue(
            "not_floor_holder at t+1 must NOT be muted by frame_too_large at t",
            s.shouldReportError(ErrorCodes.NOT_FLOOR_HOLDER, t + 1),
        )
        assertFalse("not_floor_holder throttles its own repeats", s.shouldReportError(ErrorCodes.NOT_FLOOR_HOLDER, t + 2))
        // Both audio buckets re-arm independently exactly one interval after their own last admit.
        assertTrue(s.shouldReportError(ErrorCodes.FRAME_TOO_LARGE, t + 1_000))
        assertTrue(s.shouldReportError(ErrorCodes.NOT_FLOOR_HOLDER, t + 1_001))
    }

    @Test
    fun `A5 all other codes share one third bucket`() {
        val s = session()
        val t = 10_000_000L
        assertTrue(s.shouldReportError(ErrorCodes.MALFORMED_MESSAGE, t))
        assertFalse("floor_busy shares the bucket malformed just took", s.shouldReportError(ErrorCodes.FLOOR_BUSY, t + 500))
        assertFalse("unsupported_version shares it too", s.shouldReportError(ErrorCodes.UNSUPPORTED_VERSION, t + 600))
        assertTrue("shared bucket re-arms one second after its own last admit", s.shouldReportError(ErrorCodes.FLOOR_BUSY, t + 1_000))
        assertTrue(
            "the audio hot-path codes are independent of the shared bucket: NOT muted by it",
            s.shouldReportError(ErrorCodes.NOT_FLOOR_HOLDER, t + 1_001),
        )
        // Reverse direction: admits on the hot-path bucket must not mute the shared one either.
        assertTrue(s.shouldReportError(ErrorCodes.FRAME_TOO_LARGE, t + 1_002))
        assertFalse("shared bucket still throttles its own kind one sec after its last admit", s.shouldReportError(ErrorCodes.MALFORMED_MESSAGE, t + 1_500))
        assertTrue(s.shouldReportError(ErrorCodes.FLOOR_BUSY, t + 2_000))
    }

    // --- A3/A6: wire-level probes over the real relay ------------------------------------------

    companion object {
        private lateinit var server: InternalPttServer
        private var port: Int = 0
        private val client = HttpClient(CIO) { install(WebSockets) }

        @JvmStatic
        @BeforeClass
        fun startServer() {
            port = ServerSocket(0).use { it.localPort }
            server = InternalPttServer()
            server.start(port)
            Thread.sleep(1_500) // let CIO bind
        }

        @JvmStatic
        @AfterClass
        fun stopServer() {
            server.stop()
            client.close()
        }

        private fun url(channel: Int, name: String) =
            "ws://127.0.0.1:$port/channel/$channel?name=$name&v=1"
    }

    /** Read server->client text frames as decoded messages until [quietMs] of silence. */
    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.readUntilQuiet(
        quietMs: Long,
        maxMs: Long = 10_000,
    ): List<ServerMessage> {
        val out = mutableListOf<ServerMessage>()
        val deadline = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < deadline) {
            val msg = withTimeoutOrNull(quietMs) {
                for (frame in incoming) {
                    if (frame is Frame.Text) return@withTimeoutOrNull decodeServerMessage(frame.readText())
                }
                null
            } ?: break
            out += msg
        }
        return out
    }

    @Test
    fun `A3 first text frame is Welcome exactly once on every join while another client churns the floor`() {
        val joins = 50
        // Dispatchers.IO, not the runBlocking event loop: the churn must run on a real pool of
        // threads so its floor broadcasts genuinely race the joins.
        runBlocking(Dispatchers.IO) {
            val churner = client.webSocketSession(url(1, "Churner"))
            val churn = launch {
                while (isActive) {
                    churner.send(Frame.Text("""{"type":"talk_request"}"""))
                    churner.send(Frame.Text("""{"type":"talk_release"}"""))
                    delay(1)
                }
            }
            try {
                repeat(joins) { i ->
                    val joiner = client.webSocketSession(url(1, "Joiner-$i"))
                    val first = withTimeoutOrNull(5_000) {
                        for (frame in joiner.incoming) {
                            if (frame is Frame.Text) return@withTimeoutOrNull decodeServerMessage(frame.readText())
                        }
                        null
                    }
                    assertNotNull("join #$i received no text frame", first)
                    assertTrue(
                        "join #$i: first text frame must be Welcome, was ${first!!::class.simpleName}",
                        first is Welcome,
                    )
                    // Linger briefly and make sure no second welcome ever arrives.
                    val rest = joiner.readUntilQuiet(quietMs = 60, maxMs = 250)
                    assertEquals(
                        "join #$i must get exactly one welcome ($rest)",
                        0,
                        rest.count { it is Welcome },
                    )
                    joiner.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                }
            } finally {
                churn.cancel()
                churner.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
            }
        }
    }

    /**
     * Minimal spec-conformant raw WS peer: handshake, masked text sends, text-frame reads,
     * and a KILL that sends TCP RST (SO_LINGER 0) so the server's socket dies abruptly.
     */
    private class RawPeer(port: Int, path: String) {
        private val socket = Socket()
        private val input: InputStream
        private val output: OutputStream

        init {
            socket.connect(InetSocketAddress("127.0.0.1", port))
            socket.soTimeout = 10_000
            input = socket.getInputStream()
            output = socket.getOutputStream()
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
            var run = 0
            while (run < 4) {
                val b = input.read()
                check(b != -1) { "handshake dropped" }
                headers.write(b)
                run = when {
                    b == 13 && run % 2 == 0 -> run + 1
                    b == 10 && run % 2 == 1 -> run + 1
                    b == 13 -> 1
                    else -> 0
                }
            }
            check(headers.toString(Charsets.US_ASCII).startsWith("HTTP/1.1 101"))
        }

        fun sendText(text: String) {
            val payload = text.toByteArray(Charsets.UTF_8)
            val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
            val header = ByteArrayOutputStream()
            header.write(0x81)
            header.write(0x80 or payload.size)
            header.write(mask)
            output.write(header.toByteArray())
            output.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
            output.flush()
        }

        /** Next text frame decoded, or null on timeout. */
        fun nextMessage(quietMs: Int = 5_000): ServerMessage? {
            socket.soTimeout = quietMs
            while (true) {
                val b0 = try { input.read() } catch (_: SocketTimeoutException) { return null }
                if (b0 == -1) return null
                val b1 = input.read()
                val opcode = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
                else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or input.read().toLong() }
                }
                val payload = ByteArray(len.toInt())
                var off = 0
                while (off < payload.size) {
                    val r = input.read(payload, off, payload.size - off)
                    check(r >= 0) { "eof mid-frame" }
                    off += r
                }
                if (opcode == 0x1) return decodeServerMessage(payload.toString(Charsets.UTF_8))
            }
        }

        /** Abrupt death: close with linger 0 -> TCP RST to the server. */
        fun kill() {
            socket.setSoLinger(true, 0)
            runCatching { socket.close() }
        }
    }

    @Test
    fun `A6 a hard-killed floor holder leaves the channel - floor freed and no ghost peer`() {
        runBlocking(Dispatchers.IO) {
            val holder = RawPeer(port, "/channel/7?name=Holder&v=1")
            assertNotNull("holder welcome", holder.nextMessage())
            holder.sendText("""{"type":"talk_request"}""")
            val granted = generateSequence { holder.nextMessage(quietMs = 2_000) }
                .filterIsInstance<Floor>()
                .firstOrNull()
            assertNotNull("holder must be granted the floor", granted)
            assertTrue(granted!!.isSelf)

            val bob = client.webSocketSession(url(7, "Bob"))
            val bobWelcome = withTimeoutOrNull(5_000) {
                for (frame in bob.incoming) {
                    if (frame is Frame.Text) {
                        val m = decodeServerMessage(frame.readText())
                        if (m is Welcome) return@withTimeoutOrNull m
                    }
                }
                null
            }
            assertNotNull(bobWelcome)

            // Kill the holder's socket abruptly while it still holds the floor.
            holder.kill()

            // Bob must eventually see the floor freed (release-on-leave), via broadcastFloor.
            val freed = withTimeoutOrNull(20_000) {
                for (frame in bob.incoming) {
                    if (frame !is Frame.Text) continue
                    val m = decodeServerMessage(frame.readText())
                    if (m is Floor && m.holderId == null) return@withTimeoutOrNull true
                }
                null
            }
            assertTrue("dead holder's floor must be released to live peers", freed == true)

            // And a fresh join must not count the dead session: welcome.peers == 2 (Bob + new).
            var daveWelcome: Welcome? = null
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val dave = client.webSocketSession(url(7, "Dave"))
                daveWelcome = withTimeoutOrNull(3_000) {
                    for (frame in dave.incoming) {
                        if (frame is Frame.Text) {
                            val m = decodeServerMessage(frame.readText())
                            if (m is Welcome) return@withTimeoutOrNull m
                        }
                    }
                    null
                }
                dave.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                val w = daveWelcome
                if (w != null && w.peers <= 2) break
                delay(250)
            }
            assertEquals("no ghost: channel holds Bob + Dave only", 2, daveWelcome?.peers)
            bob.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
        }
    }
}
