package com.github.devapro.pttdroid.internalserver

import com.github.devapro.pttdroid.network.PttEndpoint
import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.ServerMessage
import com.github.devapro.pttdroid.network.protocol.Welcome
import com.github.devapro.pttdroid.network.protocol.decodeServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * The embedded relay's handshake gate.
 *
 * It exists so the "host a relay on this device" setting is not a way to accidentally run an
 * open relay: the same token that protects `ptt-server` has to protect this one, or a user who
 * set a token would reasonably believe they were covered when they were not.
 */
class InternalPttServerAuthTest {

    private val token = "correct-horse-battery-staple"

    private lateinit var server: InternalPttServer
    private var port: Int = 0

    private val client = HttpClient(CIO) { install(WebSockets) }

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = InternalPttServer()
        server.start(port, token)
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

    @Test
    fun `a client with no token is refused`() = runBlocking {
        client.webSocket("ws://127.0.0.1:$port/channel/1") {
            assertEquals(ErrorCodes.UNAUTHORIZED, expect<ProtocolError>().code)
        }
    }

    @Test
    fun `a client with the wrong token is refused`() = runBlocking {
        client.webSocket(
            "ws://127.0.0.1:$port/channel/1",
            // One character short. (A token differing only by surrounding whitespace would
            // still get in: HTTP strips it from header values, which is why the settings layer
            // trims before storing.)
            request = { header(PttEndpoint.TOKEN_HEADER, token.dropLast(1)) },
        ) {
            assertEquals(ErrorCodes.UNAUTHORIZED, expect<ProtocolError>().code)
        }
    }

    @Test
    fun `a client with the right token joins`() = runBlocking {
        client.webSocket(
            "ws://127.0.0.1:$port/channel/1",
            request = { header(PttEndpoint.TOKEN_HEADER, token) },
        ) {
            assertEquals(1, expect<Welcome>().channel)
        }
    }

    @Test
    fun `the running configuration is visible so a settings change can restart it`() {
        assertEquals(InternalPttServer.Config(port, token), server.runningConfig)

        server.stop()

        assertEquals(null, server.runningConfig)
    }
}
