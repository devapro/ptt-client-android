package com.github.devapro.pttdroid.features.history

import com.github.devapro.pttdroid.audio.AudioConfig
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * Independent adversarial tests of the H14 merge in `HistoryViewModel.onAction`, written by
 * spec-verification (separate from the coder's `HistoryViewModelTest`). They target the
 * merge's per-field rules and the delete-side regressions the coder's cases do not pin:
 *
 * 1. the two mirror fields are decided *independently* — a reducer move of one survives while
 *    the other takes the collector's fresher value (this also proves unrelated reducer-made
 *    state changes are applied);
 * 2. a store re-emission that lands between dispatch and assignment neither resurrects the
 *    deleted row nor is clobbered by the delete's local filter when both sides moved (a
 *    "reducer wins records on conflict" merge would strand the mid-action row here, because
 *    the store has no newer emission left to fix it);
 * 3. deleting a NON-playing row leaves the running replay untouched through the whole VM
 *    graph, not just the reducer;
 * 4. deleting the playing row stops it exactly once without ever tearing the device down —
 *    the same player instance keeps accepting frames afterwards (live-audible regression).
 *
 * Same harness as `HistoryViewModelTest`: real view model, real reducers, real PttController
 * over inert stubs, everything on unconfined dispatchers so mirrors settle without sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelMergeAdversarialTest {

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

    /** Counts every lifecycle call so "stopped, never released" is directly observable. */
    private class CountingPlayer : VoicePlayerContract {
        var prepares = 0
        var stops = 0
        var releases = 0
        val played = mutableListOf<ByteArray>()
        override fun prepare(): Boolean {
            prepares++
            return true
        }

        override fun play(pcm: ByteArray) {
            played += pcm
        }

        override fun stop() {
            stops++
        }

        override fun setOutput(output: AudioOutput) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun release() {
            releases++
        }
    }

    /**
     * Claims the DeleteRecord slot but performs no real stop/delete: it adds a record to the
     * store mid-action (so the records collector genuinely moves the mirror while the action is
     * in flight — the intruder is missing from `records` at dispatch time) and then returns a
     * result that moves ONLY `playingId` (to null), keeping the dispatch-time `records` object.
     * A merge that decided both fields together — either direction — gets one of them wrong.
     */
    private class FieldIndependenceReducer(
        private val store: FakeHistoryStore,
    ) : Reducer<HistoryAction.DeleteRecord, HistoryState, HistoryAction, UiEvent> {
        override val actionClass = HistoryAction.DeleteRecord::class

        override suspend fun reduce(
            action: HistoryAction.DeleteRecord,
            state: HistoryState,
        ): Reducer.Result<HistoryState, HistoryAction, UiEvent?> {
            val intruder = VoiceRecord(
                id = "intruder",
                fileName = "intruder.wav",
                startedAtMs = 0,
                endedAtMs = 1,
                durationMs = 800,
                sizeBytes = 2 * AudioConfig.FRAME_BYTES.toLong(),
                senderName = "Bob",
            )
            store.addRecord(intruder, Wav.toWav(ByteArray(2 * AudioConfig.FRAME_BYTES)))
            delay(1) // let the records collector consume the emission, as the real store would
            // Move playingId only; records deliberately stays the untouched snapshot object.
            return Reducer.Result(state.copy(playingId = null))
        }
    }

    /**
     * The real delete, plus an intruder added *after* it and a yield so the collector mirrors
     * the post-delete, post-intruder store value before the assignment: both sides moved
     * `records` during the action. The merge must take the collector's `[intruder]` — not the
     * reducer's local filter `[]`, which would erase a row the store still holds with no later
     * emission to restore it.
     */
    private class BothMovedDeleteReducer(
        private val delegate: DeleteRecordReducer,
        private val store: FakeHistoryStore,
    ) : Reducer<HistoryAction.DeleteRecord, HistoryState, HistoryAction, UiEvent> {
        override val actionClass = HistoryAction.DeleteRecord::class

        override suspend fun reduce(
            action: HistoryAction.DeleteRecord,
            state: HistoryState,
        ): Reducer.Result<HistoryState, HistoryAction, UiEvent?> {
            val result = delegate.reduce(action, state)
            val intruder = VoiceRecord(
                id = "intruder",
                fileName = "intruder.wav",
                startedAtMs = 0,
                endedAtMs = 1,
                durationMs = 800,
                sizeBytes = 2 * AudioConfig.FRAME_BYTES.toLong(),
                senderName = "Bob",
            )
            store.addRecord(intruder, Wav.toWav(ByteArray(2 * AudioConfig.FRAME_BYTES)))
            delay(1) // the collector mirrors [intruder] (post-delete, post-add) now
            return result
        }
    }

    private class Fixture(private val scope: CoroutineScope) {
        val store = FakeHistoryStore()
        val player = CountingPlayer()
        val controller: PttController

        init {
            controller = PttController(
                connection = StubConnection(),
                recorder = StubRecorder(),
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
        }

        fun viewModel(
            deleteOverride: Reducer<HistoryAction.DeleteRecord, HistoryState, HistoryAction, UiEvent>? = null,
        ) = HistoryViewModel(
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

        /** A record of [frames] whole wire frames on the store (pump lasts ~frames*40 ms). */
        suspend fun record(id: String, frames: Int = 10): VoiceRecord {
            val record = VoiceRecord(
                id = id,
                fileName = "$id.wav",
                startedAtMs = 0,
                endedAtMs = 1,
                durationMs = frames * 40L,
                sizeBytes = (frames * AudioConfig.FRAME_BYTES).toLong(),
                senderName = "Alice",
            )
            store.addRecord(record, Wav.toWav(ByteArray(frames * AudioConfig.FRAME_BYTES)))
            return record
        }
    }

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
    fun `the two mirror fields are merged independently - a reducer-cleared playingId survives a mid-action store emission`() =
        runOnMain {
            val fixture = Fixture(eagerScope())
            val playing = fixture.record("r1", frames = 10)
            val viewModel = fixture.viewModel(deleteOverride = FieldIndependenceReducer(fixture.store))
            assertEquals(listOf("r1"), viewModel.state.value.records.map { it.id })
            assertNull(viewModel.state.value.playingId)

            // Start a real replay through the real graph so the snapshot carries playingId = r1.
            viewModel.onAction(HistoryAction.PlayRecord(playing.id))
            advanceTimeBy(2)
            assertEquals("r1", viewModel.state.value.playingId, "mirrored in before the delete")

            viewModel.onAction(HistoryAction.DeleteRecord("r1"))
            advanceTimeBy(2)

            val settled = viewModel.state.value
            // records: collector moved (reducer kept the snapshot object) -> collector's value.
            assertEquals(listOf("intruder", "r1"), settled.records.map { it.id }, "collector's records kept")
            // playingId: reducer moved it, collector did not -> the reducer's change IS applied.
            // An all-or-nothing merge would have to give up exactly one of the two.
            assertNull(settled.playingId, "the reducer's own field change must not be dropped")
            // And nothing was actually stopped by this synthetic reducer — the replay goes on:
            assertEquals("r1", fixture.controller.state.value.historyPlayingId)
        }

    @Test
    fun `when both sides move records during a delete the store emission wins and the deleted row stays gone`() =
        runOnMain {
            val fixture = Fixture(eagerScope())
            val doomed = fixture.record("r1", frames = 1)
            val viewModel = fixture.viewModel(
                deleteOverride = BothMovedDeleteReducer(
                    delegate = DeleteRecordReducer(fixture.controller, fixture.store),
                    store = fixture.store,
                ),
            )
            assertEquals(listOf("r1"), viewModel.state.value.records.map { it.id })

            viewModel.onAction(HistoryAction.DeleteRecord(doomed.id))
            advanceTimeBy(2)

            // The merge takes the collectors' [intruder]: the reducer's local filter alone would
            // show [] while the store holds [intruder] — and, StateFlow-deduped, there is no
            // later emission coming to fix that.
            assertEquals(listOf("intruder"), viewModel.state.value.records.map { it.id })
            advanceUntilIdle()
            assertEquals(listOf("intruder"), viewModel.state.value.records.map { it.id }, "and it settles there")
        }

    @Test
    fun `deleting a non-playing row through the view model leaves the running replay untouched`() =
        runOnMain {
            val fixture = Fixture(eagerScope())
            val playing = fixture.record("r1", frames = 10)
            val other = fixture.record("r2", frames = 1)
            val viewModel = fixture.viewModel()

            viewModel.onAction(HistoryAction.PlayRecord(playing.id))
            advanceTimeBy(2)
            assertEquals("r1", viewModel.state.value.playingId)
            val framesBefore = fixture.player.played.size
            assertTrue(framesBefore >= 1)

            viewModel.onAction(HistoryAction.DeleteRecord(other.id))
            advanceTimeBy(80)

            val settled = viewModel.state.value
            assertEquals("r1", settled.playingId, "the mirrored id survives the unrelated delete")
            assertEquals("r1", fixture.controller.state.value.historyPlayingId)
            assertEquals(listOf("r1"), settled.records.map { it.id }, "only r2's row went")
            assertEquals(0, fixture.player.stops, "no stop, no flush of the running replay")
            assertEquals(0, fixture.player.releases)
            assertTrue(
                fixture.player.played.size > framesBefore,
                "the pump kept writing frames across the delete",
            )
        }

    @Test
    fun `deleting the playing row stops the player exactly once without releasing it and the same player keeps working`() =
        runOnMain {
            val fixture = Fixture(eagerScope())
            val doomed = fixture.record("r1", frames = 10)
            val viewModel = fixture.viewModel()

            viewModel.onAction(HistoryAction.PlayRecord(doomed.id))
            advanceTimeBy(2)
            assertEquals("r1", viewModel.state.value.playingId)

            viewModel.onAction(HistoryAction.DeleteRecord(doomed.id))
            advanceTimeBy(2)

            assertNull(viewModel.state.value.playingId)
            assertNull(fixture.controller.state.value.historyPlayingId)
            assertEquals(listOf(), viewModel.state.value.records.map { it.id })
            assertEquals(1, fixture.player.stops, "one flush — pump cancel alone would be zero")
            assertEquals(0, fixture.player.releases, "release would tear the device down under live audio")

            // The same player instance must still carry audio afterwards (the live-transmission
            // regression at the contract level): a new replay writes real frames through it.
            val next = fixture.record("r2", frames = 2)
            val playedBefore = fixture.player.played.size
            fixture.controller.playHistory(next)
            advanceTimeBy(2)
            assertTrue(
                fixture.player.played.size > playedBefore,
                "frames flow through the same player after a delete-stop",
            )
            assertEquals(1, fixture.player.stops, "starting playback after a stop needs no new stop")
            assertEquals(0, fixture.player.releases)
        }
    }
