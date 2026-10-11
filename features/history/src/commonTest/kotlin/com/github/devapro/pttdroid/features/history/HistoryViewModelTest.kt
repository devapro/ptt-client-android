package com.github.devapro.pttdroid.features.history

import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.features.history.reducer.CloseHistoryReducer
import com.github.devapro.pttdroid.features.history.reducer.DeleteRecordReducer
import com.github.devapro.pttdroid.features.history.reducer.OpenHistoryReducer
import com.github.devapro.pttdroid.features.history.reducer.PlayRecordReducer
import com.github.devapro.pttdroid.features.history.reducer.StopRecordPlaybackReducer
import com.github.devapro.pttdroid.history.BackgroundRecordCapture
import com.github.devapro.pttdroid.history.FakeClock
import com.github.devapro.pttdroid.history.FakeForeground
import com.github.devapro.pttdroid.history.FakeHistoryStore
import com.github.devapro.pttdroid.history.HistoryBadge
import com.github.devapro.pttdroid.history.HistoryPlayback
import com.github.devapro.pttdroid.history.VoiceRecord
import com.github.devapro.pttdroid.history.Wav
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import com.github.devapro.pttdroid.network.ConnectionEvent
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.network.PttEndpoint
import com.github.devapro.pttdroid.network.protocol.ClientMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The H14 fix at the level it actually lives: the [HistoryViewModel] merge.
 *
 * `onAction` used to assign `result.state` — built from the dispatch-time snapshot — straight
 * over `_state`, rolling back whatever the `init` collectors mirrored in from the controller
 * and the store *while the action was suspended*. These tests run the real view model, the
 * real reducers and a real [PttController] over stubs on unconfined dispatchers. The
 * [RollbackDeleteReducer] case is the proof: it performs the store/controller mutations after
 * the real delete ran, yields so the collectors consume them (the production DataStore
 * suspensions are what do that for real; the fake store settles synchronously, so the
 * reducer's `delay` stands in), and hands back the untouched stale snapshot — the worst thing
 * the raw assignment could receive. Against the pre-fix `onAction` that test fails with
 * exactly the H14 symptom (the deleted `r1` back in `records`, the playing id lost); with the
 * merge both mirror values survive. The other two cases pin the observable behaviours: the
 * deleted row disappearing through the real reducer graph, and a replay started mid-`PlayRecord`
 * keeping its mirrored id.
 *
 * `viewModelScope` needs `Dispatchers.Main` — the same [runOnMain] backing
 * `SettingsExitObserverTest` gives the settings view model uses it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private class StubConnection : PttConnection {
        override val events: Flow<ConnectionEvent> = MutableSharedFlow()
        override suspend fun connect(endpoint: PttEndpoint) = awaitCancellation()
        override suspend fun disconnect() = Unit
        override suspend fun send(message: ClientMessage): Boolean = true
        override suspend fun sendAudio(pcm: ByteArray): Boolean = true
    }

    private class StubRecorder : VoiceRecorderContract {
        override val frames = Channel<ByteArray>(capacity = 8)
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class StubPlayer : VoicePlayerContract {
        override fun prepare(): Boolean = true
        override fun play(pcm: ByteArray) = Unit
        override fun stop() = Unit
        override fun setOutput(output: AudioOutput) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun release() = Unit
    }

    /**
     * Wraps the real delete: after it runs, the store gains a new record and a replay starts —
     * i.e. both async mirrors move *while the action is in flight* — and then the reducer hands
     * back the untouched dispatch-time snapshot, the worst thing the old raw assignment could
     * have been given. The merge must keep the collectors' values, not the snapshot's.
     */
    private class RollbackDeleteReducer(
        private val delegate: DeleteRecordReducer,
        private val store: FakeHistoryStore,
        private val controller: PttController,
    ) : Reducer<HistoryAction.DeleteRecord, HistoryState, HistoryAction, UiEvent> {
        override val actionClass = HistoryAction.DeleteRecord::class

        override suspend fun reduce(
            action: HistoryAction.DeleteRecord,
            state: HistoryState,
        ): Reducer.Result<HistoryState, HistoryAction, UiEvent?> {
            delegate.reduce(action, state)
            val intruder = VoiceRecord(
                id = "intruder",
                fileName = "intruder.wav",
                startedAtMs = 0,
                endedAtMs = 1,
                durationMs = 800,
                sizeBytes = 25_600,
                senderName = "Bob",
            )
            store.addRecord(intruder, Wav.toWav(ByteArray(2 * 12_800)))
            controller.playHistory(intruder)
            // The production suspensions (DataStore `edit`, the store lookup) are where the
            // collectors get their slot on Main; the fake store settles synchronously, so give
            // them theirs explicitly before handing back the stale snapshot.
            delay(1)
            // Ignore the delegate's (correct) result: return the stale snapshot on purpose.
            return Reducer.Result(state)
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val store = FakeHistoryStore()
        val controller: PttController

        init {
            controller = PttController(
                connection = StubConnection(),
                recorder = StubRecorder(),
                player = StubPlayer(),
                settingsProvider = { AppSettings() },
                channelPersister = {},
                scope = scope,
                historyCapture = BackgroundRecordCapture(
                    store = store,
                    clock = FakeClock(),
                    isForeground = FakeForeground(true),
                    scope = scope,
                ),
                historyPlayback = HistoryPlayback(player = StubPlayer(), store = store, scope = scope),
            )
        }

        /** The production graph; [deleteOverride] swaps in a stand-in for the real delete. */
        fun viewModel(deleteOverride: Reducer<HistoryAction.DeleteRecord, HistoryState, HistoryAction, UiEvent>? = null) =
            HistoryViewModel(
                actionProcessor = HistoryActionProcessor(
                    setOfNotNull(
                        OpenHistoryReducer(store, FakeClock()),
                        CloseHistoryReducer(),
                        PlayRecordReducer(controller, store),
                        StopRecordPlaybackReducer(controller),
                        deleteOverride ?: DeleteRecordReducer(controller, store),
                    ),
                ),
                controller = controller,
                historyStore = store,
                badge = HistoryBadge(store, FakeClock()),
            )

        suspend fun record(id: String): VoiceRecord {
            val record = VoiceRecord(
                id = id,
                fileName = "$id.wav",
                startedAtMs = 0,
                endedAtMs = 1,
                durationMs = 80,
                sizeBytes = 2_560,
                senderName = "Alice",
            )
            store.addRecord(record, Wav.toWav(ByteArray(2 * 1_280)))
            return record
        }
    }

    /**
     * A child scope of the test's backgroundScope (so the controller's infinite collectors end
     * with the test, as in `HistoryReducersTest`) but on an unconfined dispatcher, so the
     * `HistoryPlayback → PttState` mirror hop is synchronous and assertions see settled state
     * without time manipulation anywhere but the deliberate `delay` in [RollbackDeleteReducer].
     */
    private fun TestScope.eagerScope(): CoroutineScope =
        CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
        )

    private fun runOnMain(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a deleted row disappears from the open list and stays gone`() = runOnMain {
        val fixture = Fixture(eagerScope())
        val doomed = fixture.record("r1")
        val viewModel = fixture.viewModel()
        assertEquals(listOf("r1"), viewModel.state.value.records.map { it.id }, "mirrored in")
        assertNull(viewModel.state.value.playingId)

        viewModel.onAction(HistoryAction.DeleteRecord(doomed.id))

        // Whichever value the merge takes — the reducer's local removal or the store's own
        // re-emission — the row is gone the moment the action settles, without leaving.
        assertEquals(emptyList(), viewModel.state.value.records)
        advanceUntilIdle()
        assertEquals(emptyList(), viewModel.state.value.records, "and it stays gone")
    }

    @Test
    fun `a stale dispatch-time snapshot cannot clobber mirrors moved mid-action`() = runOnMain {
        val scope = eagerScope()
        val fixture = Fixture(scope)
        val doomed = fixture.record("r1")
        val viewModel = fixture.viewModel(
            deleteOverride = RollbackDeleteReducer(
                delegate = DeleteRecordReducer(fixture.controller, fixture.store),
                store = fixture.store,
                controller = fixture.controller,
            ),
        )
        assertEquals(listOf("r1"), viewModel.state.value.records.map { it.id }, "mirrored in")

        viewModel.onAction(HistoryAction.DeleteRecord(doomed.id))
        // Advance just past the reducer's 1 virtual ms: the action finishes, the pump (40 ms
        // per frame) is still mid-record — playingId must not have settled back to null.
        advanceTimeBy(2)
        val settled = viewModel.state.value
        // The old raw `_state.value = result.state` put [r1] back and lost the intruder's
        // playingId here; the merge keeps both collectors' fresher values.
        assertEquals(listOf("intruder"), settled.records.map { it.id }, "no rollback, no zombie row")
        assertEquals("intruder", settled.playingId, "the mirror moved mid-action survives")
    }

    @Test
    fun `a playing id mirrored in during PlayRecord survives the action's own assignment`() =
        runOnMain {
            val fixture = Fixture(eagerScope())
            val record = fixture.record("r1")
            val viewModel = fixture.viewModel()

            viewModel.onAction(HistoryAction.PlayRecord(record.id))

            // PlayRecordReducer returns the snapshot untouched; the replay id arrives through the
            // collectors mid-action. The merge must not let the action's own assignment erase it
            // — on `Main.immediate`, where the collectors genuinely run inside the reduce, a raw
            // assignment strands the null forever (a settled StateFlow has no later emission to
            // restore it). This case pins the behaviour; the rollback proof is the
            // clobber-demonstrating test below.
            assertEquals(record.id, viewModel.state.value.playingId)
            assertEquals(record.id, fixture.controller.state.value.historyPlayingId)
        }
}
