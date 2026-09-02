package com.github.devapro.pttdroid.viewmodel

import com.github.devapro.pttdroid.MainActionProcessor
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.domain.ConnectionStatus
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import com.github.devapro.pttdroid.network.ConnectionEvent
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.network.PttEndpoint
import com.github.devapro.pttdroid.network.protocol.ClientMessage
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Guards the state merge in [MainActivityViewModel.onAction].
 *
 * `Reducer.reduce(action, state)` receives its snapshot **by value**, taken before it runs, and
 * `onAction` launches one coroutine per action. So while a reducer is suspended, two writers that
 * are not part of the reducer pipeline can update the state: the `controller.state` mirror in the
 * ViewModel's `init`, and `onMicPermissionResult`. Assigning the reducer's result wholesale
 * discarded whichever of them landed mid-suspend.
 *
 * Today only `SaveSettingsReducer` suspends (on the DataStore write), and a settings save is
 * immediately followed by `Reconnect` — exactly when the controller is emitting — so the symptom
 * was a briefly stale talk-floor readout after saving settings. These tests fail if the merge in
 * `onAction` is reverted to a plain assignment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityViewModelTest {

    /** Suspends until [gate] completes, then returns a copy of the state it was handed. */
    private class GatedReducer(
        private val gate: CompletableDeferred<Unit>,
    ) : Reducer<MainAction.SaveSettings, ScreenState, MainAction, MainEvent> {

        override val actionClass: KClass<MainAction.SaveSettings> = MainAction.SaveSettings::class

        override suspend fun reduce(
            action: MainAction.SaveSettings,
            state: ScreenState,
        ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
            gate.await()
            return Reducer.Result(state.copy(screen = ScreenState.Screen.Main))
        }
    }

    /** Registered only so `onMicPermissionResult(true)`'s follow-up dispatch resolves. */
    private class NoOpInitReducer :
        Reducer<MainAction.InitConnection, ScreenState, MainAction, MainEvent> {

        override val actionClass: KClass<MainAction.InitConnection> =
            MainAction.InitConnection::class

        override suspend fun reduce(
            action: MainAction.InitConnection,
            state: ScreenState,
        ): Reducer.Result<ScreenState, MainAction, MainEvent?> = Reducer.Result(state)
    }

    private class FakeConnection : PttConnection {
        val inbound = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)
        override val events: Flow<ConnectionEvent> = inbound

        // The real one suspends until the socket closes.
        override suspend fun connect(endpoint: PttEndpoint) = awaitCancellation()
        override suspend fun disconnect() = Unit
        override suspend fun send(message: ClientMessage) = Unit
        override suspend fun sendAudio(pcm: ByteArray) = true
    }

    private class FakeRecorder : VoiceRecorderContract {
        override val frames: ReceiveChannel<ByteArray> = Channel()
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class FakePlayer : VoicePlayerContract {
        override fun prepare() = Unit
        override fun play(pcm: ByteArray) = Unit
        override fun release() = Unit
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a controller update during a suspended reducer survives the reducer's result`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val connection = FakeConnection()
            val controller = PttController(
                connection = connection,
                recorder = FakeRecorder(),
                player = FakePlayer(),
                settingsProvider = { AppSettings() },
                channelPersister = { },
                scope = backgroundScope,
            )
            val viewModel = MainActivityViewModel(
                actionProcessor = MainActionProcessor(setOf(GatedReducer(gate))),
                controller = controller,
            )
            // start() moves the session loop to Connecting before it dials.
            controller.start()
            runCurrent()
            assertEquals(ConnectionStatus.Connecting, viewModel.state.value.ptt.status)

            // The reducer starts and suspends on the gate, holding the Connecting snapshot.
            viewModel.onAction(MainAction.SaveSettings(AppSettings()))
            runCurrent()

            // The controller connects while the reducer is still suspended.
            connection.inbound.emit(ConnectionEvent.Connected)
            runCurrent()
            assertEquals(ConnectionStatus.Connected, viewModel.state.value.ptt.status)

            // The reducer resumes and returns its pre-suspend snapshot.
            gate.complete(Unit)
            runCurrent()

            assertEquals(
                ConnectionStatus.Connected,
                viewModel.state.value.ptt.status,
                "the reducer's stale snapshot reverted the connection state to Connecting",
            )
            // The reducer's own field still took effect.
            assertEquals(ScreenState.Screen.Main, viewModel.state.value.screen)

        }

    @Test
    fun `a permission grant during a suspended reducer survives the reducer's result`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val controller = PttController(
                connection = FakeConnection(),
                recorder = FakeRecorder(),
                player = FakePlayer(),
                settingsProvider = { AppSettings() },
                channelPersister = { },
                scope = backgroundScope,
            )
            val viewModel = MainActivityViewModel(
                actionProcessor = MainActionProcessor(
                    setOf(GatedReducer(gate), NoOpInitReducer()),
                ),
                controller = controller,
            )
            viewModel.onAction(MainAction.SaveSettings(AppSettings()))
            runCurrent()

            viewModel.onMicPermissionResult(granted = true)
            runCurrent()
            assertTrue(viewModel.state.value.micPermissionGranted)

            gate.complete(Unit)
            runCurrent()

            assertTrue(
                viewModel.state.value.micPermissionGranted,
                "the reducer's stale snapshot reverted the microphone permission grant",
            )
        }

    @Test
    fun `a reducer's own state change is still applied`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        val controller = PttController(
            connection = FakeConnection(),
            recorder = FakeRecorder(),
            player = FakePlayer(),
            settingsProvider = { AppSettings() },
            channelPersister = { },
            scope = backgroundScope,
        )
        val viewModel = MainActivityViewModel(
            actionProcessor = MainActionProcessor(setOf(GatedReducer(gate))),
            controller = controller,
        )
        viewModel.onAction(MainAction.SaveSettings(AppSettings()))
        runCurrent()

        assertEquals(ScreenState.Screen.Main, viewModel.state.value.screen)
    }
}
