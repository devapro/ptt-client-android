package com.github.devapro.pttdroid.features.main.reducer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.domain.PttSessionLauncher
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.history.BackgroundRecordCapture
import com.github.devapro.pttdroid.history.HistoryPlayback
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.network.ConnectionEvent
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.network.PttEndpoint
import com.github.devapro.pttdroid.network.protocol.ClientMessage
import com.github.devapro.pttdroid.network.protocol.Floor
import com.github.devapro.pttdroid.network.protocol.TalkRelease
import com.github.devapro.pttdroid.network.protocol.TalkRequest
import com.github.devapro.pttdroid.features.main.FakeClock
import com.github.devapro.pttdroid.features.main.FakeForeground
import com.github.devapro.pttdroid.features.main.FakeHistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Main's nine reducers — these have never had unit tests of their own (a gap this file closes).
 *
 * Same philosophy as `:features:history`'s `HistoryReducersTest`: the session-touching reducers
 * run against a **real** [PttController] over transport stubs that never dial anything, because
 * the point is exactly that the reducers reach the controller and nothing more — so the fake end
 * of the wire can be inert and the controller's own invariants (the mic-opens-only-on-floor-grant
 * debounce inside `requestTalk`) stay exercised by the real class rather than re-implemented
 * here. The fake `DataStore` follows `:features:settings`'s precedent: [SettingsRepository]
 * takes the interface, so the persist-side assertions need no platform backing store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainReducersTest {

    private class RecordingLauncher : PttSessionLauncher {
        var starts = 0
        var stops = 0
        override fun start() {
            starts++
        }
        override fun stop() {
            stops++
        }
    }

    private class StubConnection : PttConnection {
        val eventsFlow = MutableSharedFlow<ConnectionEvent>()
        override val events: Flow<ConnectionEvent> = eventsFlow
        val sent = mutableListOf<ClientMessage>()

        override suspend fun connect(endpoint: PttEndpoint) = awaitCancellation()
        override suspend fun disconnect() = Unit
        override suspend fun send(message: ClientMessage): Boolean {
            sent += message
            return true
        }
        override suspend fun sendAudio(pcm: ByteArray): Boolean = true

        fun count(message: ClientMessage): Int = sent.count { it == message }
    }

    private class StubRecorder : VoiceRecorderContract {
        var starts = 0
        var stops = 0
        override val frames = Channel<ByteArray>(capacity = 8)
        override fun start() {
            starts++
        }
        override fun stop() {
            stops++
        }
        override fun release() = Unit
    }

    /** Appends to [order] so the apply-before-persist tests can see the player side. */
    private class StubPlayer(private val order: MutableList<String>? = null) : VoicePlayerContract {
        override fun prepare(): Boolean = true
        override fun play(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun setOutput(output: AudioOutput) {
            order?.add("apply")
        }
        override fun setVolume(volume: Float) {
            order?.add("apply")
        }
        override fun release() = Unit
    }

    /**
     * In-memory `DataStore<Preferences>` counting writes and appending to the same [order] the
     * player stub uses — one list, both halves of an apply-then-persist action in call order.
     */
    private class FakeDataStore(private val order: MutableList<String>? = null) :
        DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        var writes = 0
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences {
            writes++
            order?.add("persist")
            return transform(state.value).also { state.value = it }
        }
    }

    // The fixtures never call shutdown(), so they run on the test's backgroundScope — the session
    // and keepalive loops end with the test instead of outliving it. settingsProvider mirrors a
    // real repository: the channel the persister writes is what the next connect reads back.
    private class Fixture(scope: CoroutineScope, order: MutableList<String>? = null) {
        val store = FakeHistoryStore()
        val connection = StubConnection()
        val recorder = StubRecorder()
        val player = StubPlayer(order)
        val settingsRepository = SettingsRepository(FakeDataStore(order))
        val channelWrites = mutableListOf<Int>()
        var settings = AppSettings()
        val controller: PttController

        init {
            controller = PttController(
                connection = connection,
                recorder = recorder,
                player = player,
                settingsProvider = { settings },
                channelPersister = { channel ->
                    channelWrites += channel
                    settings = settings.copy(channel = channel)
                },
                scope = scope,
                // Foreground, so the capture never fires here; the replay side is inert too.
                historyCapture = BackgroundRecordCapture(
                    store = store,
                    clock = FakeClock(),
                    isForeground = FakeForeground(true),
                    scope = scope,
                ),
                historyPlayback = HistoryPlayback(player = player, store = store, scope = scope),
            )
        }

        /** Brings the session up to the controller's own "connected" — the state the UI mirrors. */
        suspend fun connect() {
            controller.start()
            connection.eventsFlow.emit(ConnectionEvent.Connected)
        }

        fun mainState(micPermissionGranted: Boolean = true) =
            MainState(ptt = controller.state.value, micPermissionGranted = micPermissionGranted)
    }

    // --- InitConnection ------------------------------------------------------------------------

    @Test
    fun `InitConnection without the mic grant asks for it and never starts the launcher`() =
        runTest(UnconfinedTestDispatcher()) {
            val launcher = RecordingLauncher()
            val reducer = InitConnectionReducer(launcher)

            val result = reducer.reduce(MainAction.InitConnection, MainState())

            assertEquals(MainState(), result.state, "the gate must not touch state")
            assertEquals(UiEvent.RequestMicPermission, result.event)
            assertEquals(0, launcher.starts, "no service start before the microphone is granted")
        }

    @Test
    fun `InitConnection with the mic grant starts the launcher and emits nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val launcher = RecordingLauncher()
            val reducer = InitConnectionReducer(launcher)
            val state = MainState(micPermissionGranted = true)

            val result = reducer.reduce(MainAction.InitConnection, state)

            assertEquals(1, launcher.starts)
            assertEquals(state, result.state)
            assertNull(result.event)
        }

    // --- Disconnect ----------------------------------------------------------------------------

    @Test
    fun `Disconnect stops the launcher`() = runTest(UnconfinedTestDispatcher()) {
        val launcher = RecordingLauncher()
        val reducer = DisconnectReducer(launcher)

        val result = reducer.reduce(MainAction.Disconnect, MainState())

        assertEquals(1, launcher.stops)
        assertEquals(MainState(), result.state)
        assertNull(result.event)
    }

    // --- Speak / StopSpeak: the never-open-the-mic-on-press invariant ----------------------------

    @Test
    fun `Speak without the mic grant asks for it and reaches neither launcher nor controller`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)

            val result = StartSpeakReducer(fixture.controller)
                .reduce(MainAction.Speak, MainState(micPermissionGranted = false))

            assertEquals(UiEvent.RequestMicPermission, result.event)
            assertTrue(fixture.connection.sent.isEmpty(), "no talk_request without the microphone")
        }

    @Test
    fun `Speak when the channel is not free does not touch the controller`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            // The never-started controller's own state: Disconnected, so canTalk is false — and
            // with it, the reducer's guard and the controller's internal one agree.
            val state = fixture.mainState()

            val result = StartSpeakReducer(fixture.controller)
                .reduce(MainAction.Speak, state)

            assertTrue(fixture.connection.sent.isEmpty())
            assertEquals(false, fixture.controller.state.value.isRequestingFloor)
            assertEquals(state, result.state)
            assertNull(result.event)
        }

    @Test
    fun `Speak requests the floor but never opens the microphone`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            fixture.connect()

            StartSpeakReducer(fixture.controller)
                .reduce(MainAction.Speak, fixture.mainState())

            // The one thing this repo guards hardest: a press sends talk_request, and ONLY the
            // server's floor grant inside PttController may start recorder.start().
            assertEquals(1, fixture.connection.count(TalkRequest))
            assertEquals(true, fixture.controller.state.value.isRequestingFloor)
            assertEquals(0, fixture.recorder.starts, "pressing PTT must never open the microphone")
        }

    @Test
    fun `a second Speak while holding the floor does not re-request or reopen anything`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            fixture.connect()
            StartSpeakReducer(fixture.controller)
                .reduce(MainAction.Speak, fixture.mainState())
            // The grant: transmission (and the mic) start here, inside the controller.
            fixture.connection.eventsFlow.emit(
                ConnectionEvent.Control(Floor(holderId = "me", holderName = "Me", isSelf = true)),
            )
            assertEquals(1, fixture.recorder.starts)
            assertEquals(true, fixture.controller.state.value.isTransmitting)

            // canTalk is still true (we hold the floor, nobody else does) — the debounce against
            // a second request lives inside PttController.requestTalk, and this reducer
            // deliberately delegates to it rather than duplicating the check.
            StartSpeakReducer(fixture.controller)
                .reduce(MainAction.Speak, fixture.mainState())

            assertEquals(1, fixture.connection.count(TalkRequest), "no second talk_request")
            assertEquals(1, fixture.recorder.starts, "the microphone is not reopened")
        }

    @Test
    fun `StopSpeak releases the floor - the release half of a held press`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            fixture.connect()
            StartSpeakReducer(fixture.controller)
                .reduce(MainAction.Speak, fixture.mainState())
            fixture.connection.eventsFlow.emit(
                ConnectionEvent.Control(Floor(holderId = "me", holderName = "Me", isSelf = true)),
            )

            StopSpeakReducer(fixture.controller)
                .reduce(MainAction.StopSpeak, fixture.mainState())

            assertEquals(1, fixture.connection.count(TalkRelease))
            assertEquals(1, fixture.recorder.stops)
            assertEquals(false, fixture.controller.state.value.isTransmitting)
        }

    // --- SetChannel ----------------------------------------------------------------------------

    @Test
    fun `SetChannel persists the new channel and reconnects onto it`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            fixture.connect()

            SetChannelReducer(fixture.controller)
                .reduce(MainAction.SetChannel(5), fixture.mainState())

            assertEquals(listOf(5), fixture.channelWrites)
            assertEquals(5, fixture.controller.state.value.channel)
        }

    @Test
    fun `SetChannel clamps and skips a channel the session already has`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            // Default state: channel 1. 0 (and negative) clamp to 1, so this must be a no-op —
            // known-issues #12 (clamp) plus the no-redial-on-nothing-changed guard.
            SetChannelReducer(fixture.controller)
                .reduce(MainAction.SetChannel(0), fixture.mainState())
            assertEquals(emptyList(), fixture.channelWrites)

            SetChannelReducer(fixture.controller)
                .reduce(MainAction.SetChannel(100), fixture.mainState())
            assertEquals(listOf(99), fixture.channelWrites, "clamped to the channel ceiling")
        }

    // --- DismissError --------------------------------------------------------------------------

    @Test
    fun `DismissError clears the error where it lives - on the controller`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            fixture.connect()
            fixture.connection.eventsFlow.emit(ConnectionEvent.Disconnected(reason = "boom"))
            assertEquals("boom", fixture.controller.state.value.lastError)

            val state = fixture.mainState()
            val result = DismissErrorReducer(fixture.controller)
                .reduce(MainAction.DismissError, state)

            assertNull(fixture.controller.state.value.lastError)
            assertEquals(state, result.state, "the reducer owns no copy of the error to clear")
        }

    // --- Audio output and volume ----------------------------------------------------------------

    @Test
    fun `SetAudioOutput applies to the speaker before it writes to storage`() =
        runTest(UnconfinedTestDispatcher()) {
            val order = mutableListOf<String>()
            val fixture = Fixture(backgroundScope, order)

            val result = SetAudioOutputReducer(fixture.controller, fixture.settingsRepository)
                .reduce(MainAction.SetAudioOutput(AudioOutput.EARPIECE), MainState())

            assertEquals(listOf("apply", "persist"), order, "instant tap, deferred write")
            assertEquals(MainState(), result.state)
            assertEquals(
                AudioOutput.EARPIECE,
                fixture.settingsRepository.settings.first().audioOutput,
                "the write itself landed",
            )
        }

    @Test
    fun `SetPlaybackVolume reaches the speaker and writes nothing - the drag half`() =
        runTest(UnconfinedTestDispatcher()) {
            val order = mutableListOf<String>()
            val fixture = Fixture(backgroundScope, order)
            val reducer = SetPlaybackVolumeReducer(fixture.controller)

            // A whole drag's worth of values: every one audible, not one persisted (known-issues
            // #34's one-write-per-drag rule).
            repeat(100) { i ->
                reducer.reduce(MainAction.SetPlaybackVolume(i / 100f), MainState())
            }

            assertEquals(100, order.count { it == "apply" })
            assertEquals(0, order.count { it == "persist" }, "a drag must not touch DataStore")
            assertEquals(
                AppSettings.DEFAULT_PLAYBACK_VOLUME,
                fixture.settingsRepository.settings.first().playbackVolume,
            )
        }

    @Test
    fun `SavePlaybackVolume applies and writes exactly once for the whole drag`() =
        runTest(UnconfinedTestDispatcher()) {
            val order = mutableListOf<String>()
            val fixture = Fixture(backgroundScope, order)

            // Three live drag values, then the release that commits.
            val reducer = SetPlaybackVolumeReducer(fixture.controller)
            reducer.reduce(MainAction.SetPlaybackVolume(0.4f), MainState())
            reducer.reduce(MainAction.SetPlaybackVolume(0.6f), MainState())
            reducer.reduce(MainAction.SetPlaybackVolume(0.5f), MainState())
            SavePlaybackVolumeReducer(fixture.controller, fixture.settingsRepository)
                .reduce(MainAction.SavePlaybackVolume(0.5f), MainState())

            assertEquals(4, order.count { it == "apply" }, "the commit re-applies too")
            assertEquals(1, order.count { it == "persist" }, "one write per whole drag")
            assertEquals(
                listOf("apply", "apply", "apply", "apply", "persist"),
                order,
                "the commit still applies before it writes — a direct tap must not be silent",
            )
            assertEquals(0.5f, fixture.settingsRepository.settings.first().playbackVolume)
        }
}
