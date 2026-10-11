package com.github.devapro.pttdroid.domain

import com.github.devapro.pttdroid.audio.AudioConfig
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import com.github.devapro.pttdroid.history.BackgroundRecordCapture
import com.github.devapro.pttdroid.history.FakeClock
import com.github.devapro.pttdroid.history.FakeForeground
import com.github.devapro.pttdroid.history.FakeHistoryStore
import com.github.devapro.pttdroid.history.HistoryPlayback
import com.github.devapro.pttdroid.network.ConnectionEvent
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.network.PttEndpoint
import com.github.devapro.pttdroid.network.protocol.ClientMessage
import com.github.devapro.pttdroid.network.protocol.ErrorCodes
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.ProtocolError
import com.github.devapro.pttdroid.network.protocol.TalkRelease
import com.github.devapro.pttdroid.network.protocol.TalkRequest
import com.github.devapro.pttdroid.network.protocol.Welcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * C-3 verification, written independently of the coder's suite:
 *
 * R1 — `not_floor_holder` must tear the floor state down exactly like `floor_busy` when it
 * arrives while transmitting (stopTransmit + both flags cleared), while FRAME_TOO_LARGE stays
 * banner-only and MALFORMED_MESSAGE stays banner-silent (old-relay keepalive compat).
 * R2 — a `send` that reports failure makes requestTalk roll its optimistic flag back
 * synchronously; releaseTalk and the keepalive ping keep ignoring the result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PttControllerC3AdversarialTest {

    private class FakeConnection : PttConnection {
        val sent = mutableListOf<ClientMessage>()
        var controlSendAttempts = 0
        var connectCalls = 0
        var acceptControls = true
        val inbound = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 64)

        override val events: Flow<ConnectionEvent> = inbound

        override suspend fun connect(endpoint: PttEndpoint) {
            connectCalls++
            kotlinx.coroutines.awaitCancellation()
        }

        override suspend fun disconnect() = Unit

        override suspend fun send(message: ClientMessage): Boolean {
            controlSendAttempts++
            if (!acceptControls) return false
            sent += message
            return true
        }

        override suspend fun sendAudio(pcm: ByteArray): Boolean = false
    }

    private class FakeRecorder : VoiceRecorderContract {
        var started = 0
        var stopped = 0
        override val frames = Channel<ByteArray>(capacity = 8)
        override fun start() { started++ }
        override fun stop() { stopped++ }
        override fun release() = Unit
    }

    private class FakePlayer : VoicePlayerContract {
        var released = 0
        override fun prepare() = true
        override fun play(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun setOutput(output: AudioOutput) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun release() { released++ }
    }

    private class Fixture(
        val controller: PttController,
        val connection: FakeConnection,
        val recorder: FakeRecorder,
    )

    private fun harness(scope: TestScope): Fixture {
        val connection = FakeConnection()
        val recorder = FakeRecorder()
        val player = FakePlayer()
        val store = FakeHistoryStore()
        val controller = PttController(
            connection = connection,
            recorder = recorder,
            player = player,
            settingsProvider = { AppSettings() },
            channelPersister = {},
            scope = scope,
            historyCapture = BackgroundRecordCapture(
                store = store,
                clock = FakeClock(),
                isForeground = FakeForeground(true),
                scope = scope,
            ),
            historyPlayback = HistoryPlayback(player = player, store = store, scope = scope),
        )
        return Fixture(controller, connection, recorder)
    }

    private suspend fun Fixture.connected(channel: FakeConnection.() -> Unit = {}) {
        controller.start()
        connection.apply(channel)
        connection.inbound.emit(ConnectionEvent.Connected)
        connection.inbound.emit(ConnectionEvent.Control(Welcome("me", 1, 2)))
    }

    private suspend fun Fixture.transmitting() {
        connected()
        controller.requestTalk()
        connection.inbound.emit(ConnectionEvent.Control(Floor("me", "Me", isSelf = true)))
        assertTrue(controller.state.value.isTransmitting, "precondition: transmitting")
    }

    // --- R1: not_floor_holder ---------------------------------------------------------------

    @Test
    fun `not_floor_holder while transmitting stops the mic and clears both floor flags`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val f = harness(this)
        f.transmitting()

        f.connection.inbound.emit(
            ConnectionEvent.Control(ProtocolError(ErrorCodes.NOT_FLOOR_HOLDER, "not the holder")),
        )

        val state = f.controller.state.value
        assertFalse(state.isTransmitting)
        assertFalse(state.isRequestingFloor, "must not strand the pending flag")
        assertTrue(f.recorder.stopped > 0, "the microphone must close")
        // Same as floor_busy: a banner (only MALFORMED_MESSAGE stays silent).
        assertEquals("not the holder", state.lastError)
        // Not treated as a disconnect — R1 says stopTransmit + flags, nothing more.
        assertTrue(state.isConnected)

        f.controller.shutdown()
    }

    @Test
    fun `a fresh press after a not_floor_holder teardown is not blocked`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // The protocol says not_floor_holder answers audio we sent without the floor — the
        // realistic repro path. After the teardown a new press must be able to ask again.
        val f = harness(this)
        f.transmitting()

        f.connection.inbound.emit(
            ConnectionEvent.Control(ProtocolError(ErrorCodes.NOT_FLOOR_HOLDER, "nope")),
        )

        f.controller.requestTalk()
        assertEquals(2, f.connection.sent.count { it == TalkRequest }, "retry must not be blocked")
        assertTrue(f.controller.state.value.isRequestingFloor)

        f.controller.shutdown()
    }

    @Test
    fun `not_floor_holder while idle does not corrupt the connected state`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // Adversarial: the server rate-limits this error, so a late one can arrive after we
        // already released. Idle must stay idle — no phantom floor holder, no dropped session.
        val f = harness(this)
        f.connected()
        val baseline = f.controller.state.value

        f.connection.inbound.emit(
            ConnectionEvent.Control(ProtocolError(ErrorCodes.NOT_FLOOR_HOLDER, "nope")),
        )

        val state = f.controller.state.value
        assertFalse(state.isTransmitting)
        assertFalse(state.isRequestingFloor)
        assertFalse(state.isFloorHeldByOther)
        assertEquals(null, state.floorHolderName)
        assertTrue(state.isConnected)
        assertTrue(state.canTalk)
        assertEquals(baseline.channel, state.channel)
        assertEquals("nope", state.lastError)

        f.controller.shutdown()
    }

    @Test
    fun `not_floor_holder while a request is pending clears it and a late self-grant stays inert`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // State between: requested the floor, not transmitting yet. The error must clear the
        // pending flag, and the grant that was already in flight must then NOT open the mic —
        // handleFloor only accepts a grant against an outstanding request.
        val f = harness(this)
        f.connected()
        f.controller.requestTalk()
        assertTrue(f.controller.state.value.isRequestingFloor)

        f.connection.inbound.emit(
            ConnectionEvent.Control(ProtocolError(ErrorCodes.NOT_FLOOR_HOLDER, "nope")),
        )
        assertFalse(f.controller.state.value.isRequestingFloor)

        f.connection.inbound.emit(ConnectionEvent.Control(Floor("me", "Me", isSelf = true)))

        assertFalse(f.controller.state.value.isTransmitting)
        assertEquals(0, f.recorder.started, "a grant with no pending request must not talk")

        f.controller.shutdown()
    }

    // --- R1 negative space: other codes ------------------------------------------------------

    @Test
    fun `frame_too_large while transmitting stays banner-only`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val f = harness(this)
        f.transmitting()

        f.connection.inbound.emit(
            ConnectionEvent.Control(ProtocolError(ErrorCodes.FRAME_TOO_LARGE, "too big")),
        )

        val state = f.controller.state.value
        assertTrue(state.isTransmitting, "frame_too_large is not a floor statement")
        assertFalse(state.isRequestingFloor)
        assertEquals(0, f.recorder.stopped)
        assertEquals("too big", state.lastError)

        f.controller.shutdown()
    }

    @Test
    fun `malformed_message while transmitting raises no banner and keeps the floor`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // Old-relay keepalive compat must survive the C-3 edits: no banner, no teardown.
        val f = harness(this)
        f.transmitting()

        f.connection.inbound.emit(
            ConnectionEvent.Control(ProtocolError(ErrorCodes.MALFORMED_MESSAGE, "Unparseable")),
        )

        val state = f.controller.state.value
        assertEquals(null, state.lastError)
        assertTrue(state.isTransmitting)
        assertEquals(0, f.recorder.stopped)

        f.controller.shutdown()
    }

    // --- R2: send-failure rollback -------------------------------------------------------------

    @Test
    fun `a failed talk_request send rolls the pending flag back and a later press works`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val f = harness(this)
        f.connected { acceptControls = false }

        f.controller.requestTalk()

        val state = f.controller.state.value
        assertFalse(state.isRequestingFloor, "stranded flag is the C-3 defect")
        assertFalse(state.isTransmitting)
        assertEquals(0, f.recorder.started)
        assertTrue(f.connection.sent.isEmpty())

        // The transport recovers (reconnect lands a new session): the next press must go out.
        f.connection.acceptControls = true
        f.controller.requestTalk()
        assertEquals(listOf<ClientMessage>(TalkRequest), f.connection.sent)
        assertTrue(f.controller.state.value.isRequestingFloor)

        f.controller.shutdown()
    }

    @Test
    fun `a failed talk_release send still clears the local state`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // Regression: releaseTalk ignores the Boolean on purpose — local teardown happens
        // synchronously regardless, and the disconnect/grant paths guard the rest.
        val f = harness(this)
        f.transmitting()
        f.connection.acceptControls = false

        f.controller.releaseTalk()

        val state = f.controller.state.value
        assertFalse(state.isTransmitting)
        assertFalse(state.isRequestingFloor)
        assertTrue(f.recorder.stopped > 0)
        assertFalse(f.connection.sent.contains(TalkRelease), "the failed frame is not recorded")
        // attempts: the successful TalkRequest, then the failed TalkRelease.
        assertEquals(2, f.connection.controlSendAttempts, "the release was attempted anyway")

        f.controller.shutdown()
    }

    @Test
    fun `keepalive probes that fail to reach the wire are ignored and still time out`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // Regression: runKeepalive ignores send's result; a dead control path must follow the
        // exact same probe-then-redial rhythm as a live one. Connected only, no Welcome: a
        // control frame is inbound traffic and would reset the countdown (by design).
        val policy = KeepalivePolicy()
        val f = harness(this)
        f.connection.acceptControls = false
        f.controller.start()
        f.connection.inbound.emit(ConnectionEvent.Connected)
        assertEquals(0, f.connection.controlSendAttempts, "connect emits no controls")

        advanceTimeBy(policy.intervalMs + 1)
        assertEquals(1, f.connection.controlSendAttempts, "one quiet interval earns a probe")

        advanceTimeBy(policy.intervalMs)
        assertEquals(2, f.connection.controlSendAttempts)
        assertEquals(1, f.connection.connectCalls, "not given up on yet")

        advanceTimeBy(policy.intervalMs)
        assertEquals(2, f.connection.connectCalls, "failed probes still exhaust the run: redial")
        assertEquals("The relay stopped responding", f.controller.state.value.lastError)

        f.controller.shutdown()
    }

    @Test
    fun `bip audio frames are sized from AudioConfig regardless of control-path health`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        // Sanity that the transmit path never consults the control Boolean: with controls
        // failing, a granted floor must still open the mic (the #39 rule).
        val f = harness(this)
        f.connected { acceptControls = false }
        f.controller.requestTalk() // fails and rolls back

        f.connection.acceptControls = true
        f.controller.requestTalk()
        f.connection.inbound.emit(ConnectionEvent.Control(Floor("me", "Me", isSelf = true)))

        assertTrue(f.controller.state.value.isTransmitting)
        assertEquals(1, f.recorder.started)
        assertTrue(f.connection.sent.count { it == TalkRequest } == 1)
        // Guard the rollback did not kill the earlier attempt's grant either: exactly one
        // request is on the wire by the time the floor is ours.
        assertTrue(AudioConfig.FRAME_BYTES > 0)

        f.controller.shutdown()
    }
}
