package com.github.devapro.pttdroid.network

import com.github.devapro.pttdroid.audio.AudioConfig
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import com.github.devapro.pttdroid.data.settings.ServerMode
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.internalserver.InternalPttServer
import com.github.devapro.pttdroid.network.protocol.Ping
import com.github.devapro.pttdroid.network.protocol.Pong
import com.github.devapro.pttdroid.network.protocol.ServerMessage
import com.github.devapro.pttdroid.network.protocol.Welcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * The OkHttp-backed transport against a real socket, with the embedded relay as its peer.
 *
 * Everything else about the keepalive is tested through a fake connection, which is the right
 * place for the state machine but proves nothing about the engine. This covers the two things
 * only a real socket can show: that `createPttHttpClient`'s WebSocket `pingInterval` does not
 * disturb the handshake — OkHttp runs that exchange below Ktor, and getting it wrong would
 * break every connection rather than only a dying one — and that a `ping` written by
 * [KtorPttConnection.send] comes back as a [Pong] through the same [PttConnection.events] the
 * controller watches.
 */
class KtorPttConnectionTest {

    private lateinit var server: InternalPttServer
    private var port: Int = 0

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
    }

    private fun endpoint() = PttEndpoint(url = "ws://127.0.0.1:$port/channel/1?name=Alice&v=1")

    private class SilentRecorder : VoiceRecorderContract {
        override val frames = Channel<ByteArray>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class SilentPlayer : VoicePlayerContract {
        override fun prepare() = Unit
        override fun play(pcm: ByteArray) = Unit
        override fun setOutput(output: AudioOutput) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun release() = Unit
    }

    @Test
    fun `the real transport carries a keepalive probe end to end`() = runBlocking {
        val connection = KtorPttConnection()
        val control = Channel<ServerMessage>(Channel.UNLIMITED)

        // Subscribed before connecting: `events` has no replay, so anything emitted during the
        // handshake is gone by the time a late collector arrives.
        val collector = launch(Dispatchers.Default) {
            connection.events.collect { event ->
                if (event is ConnectionEvent.Control) control.send(event.message)
            }
        }
        val session = launch(Dispatchers.Default) { connection.connect(endpoint()) }

        val welcome = withTimeoutOrNull(10_000) { control.receive() }
        assertTrue("Expected welcome first, got $welcome", welcome is Welcome)

        connection.send(Ping)

        assertEquals(Pong, withTimeoutOrNull(10_000) { control.receive() })

        connection.disconnect()
        session.cancel()
        collector.cancel()
        connection.shutdown()
    }

    @Test
    fun `a receiver gets the controller bip bytes before microphone audio`() = runBlocking {
        val senderConnection = KtorPttConnection()
        val receiverConnection = KtorPttConnection()
        val receivedAudio = Channel<ByteArray>(Channel.UNLIMITED)
        val receiverControl = Channel<ServerMessage>(Channel.UNLIMITED)
        val receiverCollector = launch(Dispatchers.Default) {
            receiverConnection.events.collect { event ->
                when (event) {
                    is ConnectionEvent.Audio -> receivedAudio.send(event.pcm)
                    is ConnectionEvent.Control -> receiverControl.send(event.message)
                    else -> Unit
                }
            }
        }
        val receiverSession = launch(Dispatchers.Default) {
            receiverConnection.connect(
                PttEndpoint(url = "ws://127.0.0.1:$port/channel/1?name=Receiver&v=1"),
            )
        }
        val sender = PttController(
            connection = senderConnection,
            recorder = SilentRecorder(),
            player = SilentPlayer(),
            settingsProvider = {
                AppSettings(
                    serverMode = ServerMode.CUSTOM,
                    customHost = "127.0.0.1",
                    customPort = port,
                    useTls = false,
                )
            },
            channelPersister = {},
            scope = this,
        )

        try {
            assertTrue(withTimeout(10_000) { receiverControl.receive() } is Welcome)

            sender.start()
            withTimeout(10_000) { sender.state.first { it.isConnected } }
            sender.requestTalk()

            val expected = AudioConfig.broadcastStartBipFrames()
            val received = List(expected.size) {
                withTimeout(10_000) { receivedAudio.receive() }
            }
            assertEquals(expected.size, received.size)
            expected.zip(received).forEach { (emitted, heard) ->
                assertTrue("Relay must preserve each bip frame byte-for-byte", emitted.contentEquals(heard))
            }
        } finally {
            sender.shutdown()
            senderConnection.shutdown()
            receiverConnection.disconnect()
            receiverSession.cancel()
            receiverCollector.cancel()
            receiverConnection.shutdown()
        }
    }
}
