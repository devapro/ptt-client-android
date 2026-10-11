package com.github.devapro.pttdroid.internalserver

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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R3: the start/stop lifecycle of the embedded relay.
 *
 * `engine` is `@Volatile` and start/stop run under one lifecycle lock, so a double start —
 * sequential or from racing threads — must produce exactly one bound server, never a second
 * engine that nothing can ever stop (the orphan would hold the port after `stop()`).
 */
class InternalPttServerLifecycleTest {

    private val servers = mutableListOf<InternalPttServer>()
    private val client = HttpClient(CIO) { install(WebSockets) }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        servers.clear()
        client.close()
    }

    private fun newServer(): InternalPttServer =
        InternalPttServer().also { servers += it }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

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

    /** True only if a client handshake completes against `port` — i.e. something is listening. */
    private suspend fun serves(port: Int, token: String? = null): Boolean =
        runCatching {
            client.webSocket(
                "ws://127.0.0.1:$port/channel/1?name=Probe&v=1",
                request = {
                    if (token != null) {
                        headers.append(
                            com.github.devapro.pttdroid.network.PttEndpoint.TOKEN_HEADER,
                            token,
                        )
                    }
                },
            ) {
                expect<Welcome>(timeoutMs = 2_000)
            }
        }.isSuccess

    private suspend fun refusesConnection(port: Int): Boolean =
        runCatching {
            client.webSocket("ws://127.0.0.1:$port/channel/1?name=Probe&v=1") {
                expect<ServerMessage>(timeoutMs = 2_000)
            }
        }.isFailure

    @Test
    fun `a second start on a live server is a no-op that binds nothing new`() = runBlocking {
        val port = freePort()
        val secondPort = freePort()
        val server = newServer()
        server.start(port, maxSessionsPerChannel = 4)
        Thread.sleep(1_500)
        assertTrue(server.isRunning)

        // Double start must not throw, must not replace the config, and must not open the
        // second port.
        server.start(secondPort, accessToken = "would-be-orphan")
        assertEquals(InternalPttServer.Config(port, "", 4), server.runningConfig)
        assertTrue("first port must still serve", serves(port))
        assertFalse("the rejected second start must not have bound its port", serves(secondPort))
        // The no-op must not have installed the second call's token either.
        assertTrue("no token is enforced after the ignored start", serves(port))

        server.stop()
        assertFalse(server.isRunning)
        assertNull(server.runningConfig)
    }

    @Test
    fun `eight threads racing start produce exactly one listener and one stoppable engine`() =
        runBlocking {
            val port = freePort()
            val server = newServer()
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val startGate = CountDownLatch(1)
            val done = CountDownLatch(8)
            repeat(8) {
                Thread {
                    try {
                        startGate.await(10, TimeUnit.SECONDS)
                        server.start(port)
                    } catch (e: Throwable) {
                        errors += e
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            startGate.countDown()
            assertTrue("threads did not finish", done.await(30, TimeUnit.SECONDS))
            assertTrue("start threw: $errors", errors.isEmpty())
            assertTrue(server.isRunning)
            Thread.sleep(1_500)
            assertTrue("the raced server must serve", serves(port))

            server.stop()
            // If two engines had been created, one would be orphaned behind the single `engine`
            // field and this port would still answer after stop().
            assertFalse("no orphaned engine may keep the port after stop", serves(port))
            assertFalse(server.isRunning)
        }

    @Test
    fun `stop without start and repeated stop are no-ops`() {
        val server = newServer()
        server.stop() // never started
        assertFalse(server.isRunning)
        assertNull(server.runningConfig)

        val port = freePort()
        server.start(port)
        Thread.sleep(1_500)
        server.stop()
        server.stop() // twice
        assertFalse(server.isRunning)
    }

    @Test
    fun `a restart after stop takes the new config`() = runBlocking {
        val firstPort = freePort()
        val server = newServer()
        server.start(firstPort)
        Thread.sleep(1_500)
        server.stop()

        val secondPort = freePort()
        server.start(secondPort, accessToken = "tok", maxSessionsPerChannel = 5)
        Thread.sleep(1_500)
        assertEquals(InternalPttServer.Config(secondPort, "tok", 5), server.runningConfig)

        // The new token is live: a bare join is refused with unauthorized.
        client.webSocket("ws://127.0.0.1:$secondPort/channel/1?name=Zed&v=1") {
            assertEquals(ErrorCodes.UNAUTHORIZED, expect<ProtocolError>().code)
        }
        assertTrue("a tokened join must get in", serves(secondPort, token = "tok"))
    }
}
