package com.github.devapro.pttdroid.domain

import com.github.devapro.pttdroid.network.ConnectionEvent
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.network.protocol.ClientMessage
import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.Peers
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.TalkRelease
import com.github.devapro.pttdroid.network.protocol.TalkRequest
import com.github.devapro.pttdroid.network.protocol.Welcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the floor state machine through a fake transport.
 *
 * The important property is that the microphone only opens once the SERVER grants the floor —
 * pressing PTT alone must not start transmitting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttControllerTest {

    private class FakeConnection : PttConnection {
        val sent = mutableListOf<ClientMessage>()
        val audioFrames = mutableListOf<ByteArray>()
        val inbound = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)
        var connectCalls = 0

        override val events: Flow<ConnectionEvent> = inbound

        override suspend fun connect(url: String) {
            connectCalls++
            // Does not return: the real one suspends until the socket closes.
            kotlinx.coroutines.awaitCancellation()
        }

        override suspend fun disconnect() = Unit

        override suspend fun send(message: ClientMessage) {
            sent += message
        }

        override suspend fun sendAudio(pcm: ByteArray): Boolean {
            audioFrames += pcm
            return true
        }
    }

    private class FakeRecorder : com.github.devapro.pttdroid.audio.VoiceRecorderContract {
        var started = 0
        var stopped = 0
        override val frames = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 8)
        override fun start() { started++ }
        override fun stop() { stopped++ }
        override fun release() = Unit
    }

    private class FakePlayer : com.github.devapro.pttdroid.audio.VoicePlayerContract {
        var prepared = 0
        var released = 0
        val played = mutableListOf<ByteArray>()
        override fun prepare() { prepared++ }
        override fun play(pcm: ByteArray) { played += pcm }
        override fun release() { released++ }
    }

    private fun harness(scope: TestScope): Triple<PttController, FakeConnection, Pair<FakeRecorder, FakePlayer>> {
        val connection = FakeConnection()
        val recorder = FakeRecorder()
        val player = FakePlayer()
        val controller = PttController(
            connection = connection,
            recorder = recorder,
            player = player,
            settingsProvider = { com.github.devapro.pttdroid.data.settings.AppSettings() },
            channelPersister = {},
            scope = scope,
        )
        return Triple(controller, connection, recorder to player)
    }

    @Test
    fun `dismissing an error clears it without touching the session`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        connection.inbound.emit(
            ConnectionEvent.Control(ProtocolError(ErrorCodes.FLOOR_BUSY, "busy")),
        )
        assertEquals("busy", controller.state.value.lastError)

        controller.clearError()

        assertEquals(null, controller.state.value.lastError)
        assertTrue("dismissing must not drop the session", controller.state.value.isConnected)

        controller.shutdown()
    }

    @Test
    fun `welcome moves the session to connected and prepares playback`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, fakes) = harness(this)
        controller.start()

        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(
            ConnectionEvent.Control(Welcome(clientId = "me", channel = 5, peers = 2)),
        )

        val state = controller.state.value
        assertTrue(state.isConnected)
        assertEquals(5, state.channel)
        assertEquals(2, state.peers)
        assertEquals(1, fakes.second.prepared)

        controller.shutdown()
    }

    @Test
    fun `pressing ptt requests the floor but does not transmit yet`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))

        controller.requestTalk()

        assertTrue("should have asked for the floor", connection.sent.contains(TalkRequest))
        assertFalse("must not transmit before the grant", controller.state.value.isTransmitting)
        assertEquals("microphone must stay closed", 0, fakes.first.started)

        controller.shutdown()
    }

    @Test
    fun `floor grant starts transmitting`() = runTest(UnconfinedTestDispatcher()) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        controller.requestTalk()

        connection.inbound.emit(
            ConnectionEvent.Control(Floor(holderId = "me", holderName = "Me", isSelf = true)),
        )

        assertTrue(controller.state.value.isTransmitting)
        assertEquals(1, fakes.first.started)

        controller.shutdown()
    }

    @Test
    fun `floor_busy clears the pending request and does not transmit`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        controller.requestTalk()

        connection.inbound.emit(
            ConnectionEvent.Control(ProtocolError(ErrorCodes.FLOOR_BUSY, "busy")),
        )

        val state = controller.state.value
        assertFalse(state.isTransmitting)
        assertFalse(state.isRequestingFloor)
        assertEquals(0, fakes.first.started)

        controller.shutdown()
    }

    @Test
    fun `another user holding the floor blocks talking and marks receiving`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))

        connection.inbound.emit(
            ConnectionEvent.Control(Floor(holderId = "other", holderName = "Bob", isSelf = false)),
        )

        val state = controller.state.value
        assertTrue(state.isFloorHeldByOther)
        assertTrue(state.isReceiving)
        assertFalse("PTT must be disabled while someone else talks", state.canTalk)
        assertEquals("Bob", state.floorHolderName)

        controller.shutdown()
    }

    @Test
    fun `releasing sends talk_release and stops the microphone`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        controller.requestTalk()
        connection.inbound.emit(ConnectionEvent.Control(Floor("me", "Me", true)))

        controller.releaseTalk()

        assertTrue(connection.sent.contains(TalkRelease))
        assertFalse(controller.state.value.isTransmitting)
        assertTrue(fakes.first.stopped > 0)

        controller.shutdown()
    }

    @Test
    fun `incoming audio reaches the player`() = runTest(UnconfinedTestDispatcher()) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))

        connection.inbound.emit(ConnectionEvent.Audio(byteArrayOf(1, 2, 3, 4)))

        assertEquals(1, fakes.second.played.size)

        controller.shutdown()
    }

    @Test
    fun `disconnect resets transmission and floor state`() = runTest(UnconfinedTestDispatcher()) {
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        controller.requestTalk()
        connection.inbound.emit(ConnectionEvent.Control(Floor("me", "Me", true)))
        assertTrue(controller.state.value.isTransmitting)

        connection.inbound.emit(ConnectionEvent.Disconnected("network lost"))

        val state = controller.state.value
        assertFalse(state.isConnected)
        assertFalse(state.isTransmitting)
        assertFalse(state.isFloorHeldByOther)
        assertFalse("PTT must be disabled while offline", state.canTalk)

        controller.shutdown()
    }

    @Test
    fun `peers updates are reflected`() = runTest(UnconfinedTestDispatcher()) {
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 1)))

        connection.inbound.emit(ConnectionEvent.Control(Peers(4)))

        assertEquals(4, controller.state.value.peers)
        controller.shutdown()
    }

    // --- edge cases: things that must NOT happen ------------------------------------------

    @Test
    fun `pressing ptt while disconnected sends nothing`() = runTest(UnconfinedTestDispatcher()) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        // No Connected, no Welcome — the socket is still coming up.

        controller.requestTalk()

        assertTrue("must not talk into a dead socket", connection.sent.isEmpty())
        assertFalse(controller.state.value.isRequestingFloor)
        assertEquals(0, fakes.first.started)

        controller.shutdown()
    }

    @Test
    fun `pressing ptt while someone else holds the floor sends nothing`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        connection.inbound.emit(ConnectionEvent.Control(Floor("other", "Bob", isSelf = false)))

        controller.requestTalk()

        assertFalse("must not talk over the floor holder", connection.sent.contains(TalkRequest))
        assertEquals(0, fakes.first.started)

        controller.shutdown()
    }

    @Test
    fun `a second press while a request is pending does not send a second request`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))

        controller.requestTalk()
        controller.requestTalk()
        controller.requestTalk()

        assertEquals(1, connection.sent.count { it == TalkRequest })
        controller.shutdown()
    }

    @Test
    fun `pressing again while already transmitting does not re-request`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        controller.requestTalk()
        connection.inbound.emit(ConnectionEvent.Control(Floor("me", "Me", isSelf = true)))

        controller.requestTalk()

        assertEquals(1, connection.sent.count { it == TalkRequest })
        assertEquals("the microphone must not be opened twice", 1, fakes.first.started)
        controller.shutdown()
    }

    @Test
    fun `releasing when we never held the floor sends nothing`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))

        controller.releaseTalk()

        assertFalse(connection.sent.contains(TalkRelease))
        controller.shutdown()
    }

    @Test
    fun `the floor going to someone else clears our pending request`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // We pressed, and the answer that came back was somebody else's grant. Without clearing
        // the pending flag the UI would sit on WAIT until the next state change.
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        controller.requestTalk()
        assertTrue(controller.state.value.isRequestingFloor)

        connection.inbound.emit(ConnectionEvent.Control(Floor("other", "Bob", isSelf = false)))

        val state = controller.state.value
        assertFalse(state.isRequestingFloor)
        assertFalse(state.isTransmitting)
        assertTrue(state.isFloorHeldByOther)
        assertEquals(0, fakes.first.started)

        controller.shutdown()
    }

    @Test
    fun `the floor being released by its holder reopens the channel`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        connection.inbound.emit(ConnectionEvent.Control(Floor("other", "Bob", isSelf = false)))

        connection.inbound.emit(ConnectionEvent.Control(Floor(null, null, isSelf = false)))

        val state = controller.state.value
        assertFalse(state.isFloorHeldByOther)
        assertTrue("the channel must become usable again", state.canTalk)
        controller.shutdown()
    }

    @Test
    fun `toggle talk requests then releases`() = runTest(UnconfinedTestDispatcher()) {
        // The notification action and the widget are toggles, not holds — they share this path.
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))

        controller.toggleTalk()
        connection.inbound.emit(ConnectionEvent.Control(Floor("me", "Me", isSelf = true)))
        assertTrue(controller.state.value.isTransmitting)

        controller.toggleTalk()

        assertTrue(connection.sent.contains(TalkRelease))
        assertFalse(controller.state.value.isTransmitting)
        controller.shutdown()
    }

    @Test
    fun `losing the socket mid-transmission closes the microphone`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
        controller.requestTalk()
        connection.inbound.emit(ConnectionEvent.Control(Floor("me", "Me", isSelf = true)))
        assertEquals(1, fakes.first.started)

        connection.inbound.emit(ConnectionEvent.Disconnected("network lost"))

        assertTrue("the recorder must be stopped, not left running", fakes.first.stopped > 0)
        assertFalse(controller.state.value.isTransmitting)
        controller.shutdown()
    }

    @Test
    fun `setChannel clamps out of range values and persists them`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val persisted = mutableListOf<Int>()
        val connection = FakeConnection()
        val recorder = FakeRecorder()
        val controller = PttController(
            connection = connection,
            recorder = recorder,
            player = FakePlayer(),
            settingsProvider = { com.github.devapro.pttdroid.data.settings.AppSettings() },
            channelPersister = { persisted += it },
            scope = this,
        )

        controller.setChannel(0)
        controller.setChannel(500)

        assertEquals(listOf(1, 99), persisted)
        controller.shutdown()
    }

    @Test
    fun `a successful connect clears the error from the previous failure`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val (controller, connection, _) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Disconnected("connection refused"))
        assertEquals("connection refused", controller.state.value.lastError)

        connection.inbound.emit(ConnectionEvent.Connected)

        assertEquals(null, controller.state.value.lastError)
        controller.shutdown()
    }

    @Test
    fun `audio arriving before the welcome is still played`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // The relay can start forwarding before our own welcome is processed; dropping those
        // frames clips the start of the first transmission we ever hear.
        val (controller, connection, fakes) = harness(this)
        controller.start()
        connection.inbound.emit(ConnectionEvent.Connected)

        connection.inbound.emit(ConnectionEvent.Audio(byteArrayOf(9, 9)))

        assertEquals(1, fakes.second.played.size)
        controller.shutdown()
    }
}
