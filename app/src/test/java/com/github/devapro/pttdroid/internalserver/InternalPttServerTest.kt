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
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * Exercises the optional on-device relay over a real socket.
 *
 * It has to satisfy the same protocol as `ptt-server`, so the important cases here are the
 * same two the standalone server is tested for: channel isolation and floor control.
 */
class InternalPttServerTest {

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

    private suspend fun DefaultClientWebSocketSession.expectFloor(
        timeoutMs: Long = 5_000,
        predicate: (Floor) -> Boolean,
    ): Floor {
        val found = withTimeoutOrNull(timeoutMs) {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val message = decodeServerMessage(frame.readText())
                if (message is Floor && predicate(message)) return@withTimeoutOrNull message
            }
            null
        }
        assertNotNull("Timed out waiting for a matching floor", found)
        return found!!
    }

    private suspend fun DefaultClientWebSocketSession.nextBinaryOrNull(
        timeoutMs: Long = 1_500,
    ): ByteArray? = withTimeoutOrNull(timeoutMs) {
        for (frame in incoming) if (frame is Frame.Binary) return@withTimeoutOrNull frame.readBytes()
        null
    }

    private fun url(channel: Int, name: String) =
        "ws://127.0.0.1:$port/channel/$channel?name=$name&v=1"

    @Test
    fun `welcome carries the shared audio parameters`() = runBlocking {
        client.webSocket(url(1, "Alice")) {
            val welcome = expect<Welcome>()
            assertEquals(1, welcome.channel)
            assertEquals(16_000, welcome.audio.sampleRate)
            assertEquals(1_280, welcome.audio.frameBytes)
        }
    }

    @Test
    fun `audio is relayed inside a channel and not across channels`() = runBlocking {
        client.webSocket(url(1, "Alice")) {
            val alice = this
            alice.expect<Welcome>()

            client.webSocket(url(1, "Bob")) {
                val bob = this
                bob.expect<Welcome>()

                client.webSocket(url(2, "Eve")) {
                    val eve = this
                    eve.expect<Welcome>()

                    alice.send(Frame.Text("""{"type":"talk_request"}"""))
                    assertTrue(alice.expectFloor { it.isSelf }.isSelf)

                    alice.send(Frame.Binary(true, ByteArray(320) { 0x5A }))

                    val heard = bob.nextBinaryOrNull()
                    assertNotNull("Same-channel peer must receive audio", heard)
                    assertEquals(0x5A.toByte(), heard!![0])

                    assertNull(
                        "Channel 2 must not hear channel 1",
                        eve.nextBinaryOrNull(),
                    )
                }
            }
        }
    }

    @Test
    fun `only one talker holds the floor at a time`() = runBlocking {
        client.webSocket(url(3, "Alice")) {
            val alice = this
            alice.expect<Welcome>()

            client.webSocket(url(3, "Bob")) {
                val bob = this
                bob.expect<Welcome>()

                alice.send(Frame.Text("""{"type":"talk_request"}"""))
                assertTrue(alice.expectFloor { it.isSelf }.isSelf)

                bob.send(Frame.Text("""{"type":"talk_request"}"""))
                assertEquals(ErrorCodes.FLOOR_BUSY, bob.expect<ProtocolError>().code)

                alice.send(Frame.Text("""{"type":"talk_release"}"""))
                // Wait for the release to be broadcast before asking. Alice's release and Bob's
                // request travel on two different sockets, so without this the server is free to
                // see the request first and answer FLOOR_BUSY — the test raced, not the relay.
                bob.expectFloor { it.holderId == null }
                bob.send(Frame.Text("""{"type":"talk_request"}"""))
                assertEquals("Bob", bob.expectFloor { it.isSelf }.holderName)
            }
        }
    }

    @Test
    fun `audio without the floor is rejected`() = runBlocking {
        client.webSocket(url(4, "Alice")) {
            expect<Welcome>()
            send(Frame.Binary(true, ByteArray(320)))
            assertEquals(ErrorCodes.NOT_FLOOR_HOLDER, expect<ProtocolError>().code)
        }
    }

    @Test
    fun `invalid channel is refused`() = runBlocking {
        client.webSocket("ws://127.0.0.1:$port/channel/0?v=1") {
            assertEquals(ErrorCodes.INVALID_CHANNEL, expect<ProtocolError>().code)
        }
    }
}
