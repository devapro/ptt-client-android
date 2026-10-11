package com.github.devapro.pttdroid.internalserver

import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.ServerMessage
import com.github.devapro.pttdroid.network.protocol.Welcome
import com.github.devapro.pttdroid.network.protocol.decodeServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * C-4 acceptance: the connect-URL gate of the embedded relay.
 *
 * Two behaviours, both mirroring `ptt-server`'s `ChannelRoutes`:
 * - a join beyond the configured per-channel cap is turned away with `error{channel_full}`
 *   and a `VIOLATED_POLICY` close, WITHOUT the rejectee ever taking a slot;
 * - `v` is checked strictly: present-but-unparseable is rejected as `unsupported_version`,
 *   absent `v` is accepted.
 *
 * Rejection assertions (the single error frame and the close CODE) go over a raw socket,
 * because Ktor's client engine absorbs the peer's close frame and never surfaces it on
 * `incoming` — a ktor-client harness can check the error code but not the close that must
 * follow it. The raw reader speaks just enough RFC 6455 for that. Assertions are on error
 * CODES and close CODES, never message text (the text is the embedded server's own wording,
 * not protocol).
 */
class InternalPttServerConnectUrlTest {

    private lateinit var server: InternalPttServer
    private var port: Int = 0

    private val client = HttpClient(CIO) { install(WebSockets) }

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = InternalPttServer()
        server.start(port, maxSessionsPerChannel = 1)
        // Give CIO a moment to bind before the first client connects.
        Thread.sleep(1_500)
    }

    @After
    fun tearDown() {
        server.stop()
        client.close()
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

    private fun url(channel: Int, name: String) =
        "ws://127.0.0.1:$port/channel/$channel?name=$name&v=1"

    private fun readWsFrame(input: InputStream): Pair<Int, ByteArray>? {
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
        if (b1 and 0x80 != 0) { // server frames must not be masked, but be correct anyway
            val mask = ByteArray(4)
            input.read(mask)
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return opcode to payload
    }

    /** Everything one raw handshake put on the wire: the server frames, in order, and the close. */
    private class RawExchange(val messages: List<ServerMessage>, val closeCode: Int?) {
        val errors: List<ProtocolError> get() = messages.filterIsInstance<ProtocolError>()
    }

    /** Plain HTTP upgrade + minimal RFC 6455 reader; returns when the server closes or EOFs. */
    private fun rawConnect(path: String): RawExchange {
        Socket().use { socket ->
            socket.soTimeout = 5_000
            socket.connect(InetSocketAddress("127.0.0.1", port))
            val output = socket.getOutputStream()
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

            val input = socket.getInputStream()
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

            val messages = mutableListOf<ServerMessage>()
            while (true) {
                val frame = readWsFrame(input) ?: break
                when (frame.first) {
                    0x1 -> messages += decodeServerMessage(frame.second.toString(Charsets.UTF_8))
                    0x8 -> {
                        val code =
                            if (frame.second.size >= 2) {
                                ((frame.second[0].toInt() and 0xFF) shl 8) or
                                    (frame.second[1].toInt() and 0xFF)
                            } else {
                                null
                            }
                        return RawExchange(messages, code)
                    }
                    // 0x9/0xA ping/pong etc. carry nothing this test needs.
                    else -> Unit
                }
            }
            return RawExchange(messages, null)
        }
    }

    /**
     * Keeps rejoining until a `welcome` arrives. The retry absorbs the client-close ->
     * server-cleanup handover of a previous holder; it cannot absorb a leaked slot, because
     * a slot consumed by a rejected joiner is never freed by anyone leaving.
     */
    private suspend fun awaitWelcome(channel: Int, name: String, deadlineMs: Long = 10_000): Welcome {
        val deadline = System.currentTimeMillis() + deadlineMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val attempt = runCatching {
                var welcome: Welcome? = null
                client.webSocket(url(channel, name)) { welcome = expect(timeoutMs = 2_000) }
                welcome!!
            }
            val gained = attempt.getOrNull()
            if (gained != null) return gained
            last = attempt.exceptionOrNull()
            delay(250)
        }
        throw AssertionError("never got a welcome before the deadline", last)
    }

    // --- R1: per-channel cap -------------------------------------------------------------------

    @Test
    fun `join past the cap gets exactly one error frame then a violated-policy close`() =
        runBlocking {
            client.webSocket(url(1, "Alice")) {
                expect<Welcome>()
                val bob = rawConnect("/channel/1?name=Bob&v=1")
                assertEquals(1, bob.errors.size)
                assertEquals(ErrorCodes.CHANNEL_FULL, bob.errors.single().code)
                assertEquals(
                    "the rejectee gets one error text frame, nothing else, then the close",
                    1,
                    bob.messages.size,
                )
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code.toInt(), bob.closeCode)
            }
        }

    @Test
    fun `a rejected joiner consumes no slot and a fresh join after the holder leaves succeeds`() =
        runBlocking {
            client.webSocket(url(1, "Alice")) {
                expect<Welcome>()
                val bob = rawConnect("/channel/1?name=Bob&v=1")
                assertEquals(ErrorCodes.CHANNEL_FULL, bob.errors.single().code)
            }
            // Alice has left. If Bob's rejection had added him to the channel (or otherwise
            // consumed the only slot), Carol would be turned away forever. welcome.peers == 1
            // also proves the channel holds exactly Carol, no ghost.
            val carol = awaitWelcome(1, "Carol")
            assertEquals(1, carol.peers)
        }

    @Test
    fun `the cap is per channel so a full channel does not block another one`() = runBlocking {
        client.webSocket(url(1, "Alice")) {
            expect<Welcome>()
            val bob = rawConnect("/channel/1?name=Bob&v=1")
            assertEquals(ErrorCodes.CHANNEL_FULL, bob.errors.single().code)

            client.webSocket(url(2, "Carol")) {
                val welcome = expect<Welcome>()
                assertEquals(2, welcome.channel)
                assertEquals(1, welcome.peers)
            }
        }
    }

    // --- R1 default / regression: Config equality with the new defaulted field -------------------

    @Test
    fun `a default start carries the 32-session cap and equality ignores the defaulted field`() {
        // Regression: PttForegroundService compares `runningConfig != wanted` where `wanted`
        // is built as Config(port, accessToken) without the new field. If the defaults did not
        // line up, every settings tick would restart the relay.
        val extraPort = ServerSocket(0).use { it.localPort }
        val extra = InternalPttServer()
        extra.start(extraPort)
        try {
            assertEquals(32, InternalPttServer.Config.DEFAULT_MAX_SESSIONS_PER_CHANNEL)
            assertEquals(32, extra.runningConfig?.maxSessionsPerChannel)
            assertEquals(InternalPttServer.Config(extraPort, ""), extra.runningConfig)
            // ...and a genuinely different cap is still unequal, so the comparison keeps
            // catching real config changes.
            assertTrue(InternalPttServer.Config(extraPort, "", 31) != extra.runningConfig)
        } finally {
            extra.stop()
        }
    }

    // --- R2: strict `v` ------------------------------------------------------------------------

    @Test
    fun `a present but unparseable version is refused with unsupported_version and a policy close`() {
        val hand = rawConnect("/channel/1?name=Zed&v=abc")
        assertEquals(ErrorCodes.UNSUPPORTED_VERSION, hand.errors.single().code)
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code.toInt(), hand.closeCode)
    }

    @Test
    fun `a wrong numeric version is refused with unsupported_version`() {
        val hand = rawConnect("/channel/1?name=Zed&v=2")
        assertEquals(ErrorCodes.UNSUPPORTED_VERSION, hand.errors.single().code)
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code.toInt(), hand.closeCode)
    }

    @Test
    fun `a present but empty version is refused, not treated as absent`() {
        // `toIntOrNull()` on "" is null; "absent" must mean an ABSENT parameter only —
        // same rule the standalone relay states for `?v=`.
        val hand = rawConnect("/channel/1?name=Zed&v=")
        assertNull(hand.messages.filterIsInstance<Welcome>().firstOrNull())
        assertEquals(ErrorCodes.UNSUPPORTED_VERSION, hand.errors.single().code)
        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code.toInt(), hand.closeCode)
    }

    @Test
    fun `an omitted version is accepted`() = runBlocking {
        client.webSocket("ws://127.0.0.1:$port/channel/7?name=Dan") {
            assertEquals(7, expect<Welcome>().channel)
        }
    }

    @Test
    fun `a refused handshake consumes no slot either`() = runBlocking {
        // Three version-refusals pile onto channel 1 while its only slot is held; after the
        // holder leaves, a fresh join must still get in.
        client.webSocket(url(1, "Alice")) {
            expect<Welcome>()
            repeat(3) {
                val hand = rawConnect("/channel/1?name=Zed$it&v=x")
                assertEquals(ErrorCodes.UNSUPPORTED_VERSION, hand.errors.single().code)
            }
        }
        val carol = awaitWelcome(1, "Carol")
        assertEquals(1, carol.peers)
    }

    @Test
    fun `an over-cap reject never disturbs the incumbent holder`() = runBlocking {
        client.webSocket(url(1, "Alice")) {
            expect<Welcome>()
            rawConnect("/channel/1?name=Bob&v=1")

            // Alice still owns the channel: the floor still works for her.
            send(Frame.Text("""{"type":"talk_request"}"""))
            val floor = expect<Floor>()
            assertTrue("floor must be granted to the incumbent", floor.isSelf)
        }
    }
}
