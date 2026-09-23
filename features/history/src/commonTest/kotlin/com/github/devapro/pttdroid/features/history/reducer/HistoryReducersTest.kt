package com.github.devapro.pttdroid.features.history.reducer

import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.audio.VoiceRecorderContract
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.history.BackgroundRecordCapture
import com.github.devapro.pttdroid.history.FakeClock
import com.github.devapro.pttdroid.history.FakeForeground
import com.github.devapro.pttdroid.history.FakeHistoryStore
import com.github.devapro.pttdroid.history.HistoryPlayback
import com.github.devapro.pttdroid.history.VoiceRecord
import com.github.devapro.pttdroid.history.Wav
import com.github.devapro.pttdroid.network.ConnectionEvent
import com.github.devapro.pttdroid.network.PttConnection
import com.github.devapro.pttdroid.network.PttEndpoint
import com.github.devapro.pttdroid.network.protocol.ClientMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * The history screen's five reducers — all nine cases kept.
 *
 * The two that only move state around ([HistoryAction.Open] / [HistoryAction.Close]) are
 * exercised alone; the three that talk to the session run against a real [PttController] over
 * transport stubs that never dial anything — the point is exactly that the reducers reach the
 * controller (and the store) and nothing more, so the fake end of the wire can be inert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryReducersTest {

    private class StubConnection : PttConnection {
        override val events: Flow<ConnectionEvent> = MutableSharedFlow()
        override suspend fun connect(endpoint: PttEndpoint) = awaitCancellation()
        override suspend fun disconnect() = Unit
        override suspend fun send(message: ClientMessage) = Unit
        override suspend fun sendAudio(pcm: ByteArray): Boolean = true
    }

    private class StubRecorder : VoiceRecorderContract {
        override val frames = Channel<ByteArray>(capacity = 8)
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private class StubPlayer : VoicePlayerContract {
        var prepared = 0
        val played = mutableListOf<ByteArray>()
        override fun prepare(): Boolean {
            prepared++
            return true
        }

        override fun play(pcm: ByteArray) {
            played += pcm
        }

        override fun setOutput(output: AudioOutput) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun release() = Unit
    }

    // The fixtures here never call shutdown(), so they run on the test's backgroundScope — the
    // infinite floor/playback collectors end with the test instead of outliving it.
    private class Fixture(scope: CoroutineScope) {
        val store = FakeHistoryStore()
        val player = StubPlayer()
        val controller: PttController

        init {
            // Foreground, so the capture never fires here; its wiring has its own tests in
            // PttControllerTest. The replay side is what these reducers drive.
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

        /** A two-frame record written to the (fake) store; returns it for the action under test. */
        suspend fun record(id: String = "r1"): VoiceRecord {
            val record = VoiceRecord(
                id = id,
                fileName = "$id.wav",
                startedAtMs = 0,
                endedAtMs = 1,
                durationMs = 80,
                sizeBytes = 2560,
                senderName = "Alice",
            )
            store.addRecord(record, Wav.toWav(ByteArray(2 * 1280)))
            return record
        }
    }

    // --- Open ----------------------------------------------------------------------------------

    @Test
    fun `Open marks everything seen`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeHistoryStore()
        store.addRecord(
            VoiceRecord("r1", "r1.wav", 0, 9_000L, 80, 0, null),
            Wav.toWav(ByteArray(1280)),
        )
        val reducer = OpenHistoryReducer(store, FakeClock(5_000L))
        // The unread flag this test used to assert on is a derivation (HistoryBadge) now, so the
        // read receipt itself — lastViewedAt advancing — is what the reducer can and must do.
        val state = HistoryState(records = store.recordsNow())

        val result = reducer.reduce(HistoryAction.Open, state)

        assertEquals(5_000L, store.lastViewedNow(), "opening the list is the read receipt")
        assertSame(state, result.state, "the reducer owns no state to write — badge derives unread")
    }

    @Test
    fun `Open with a clock behind the last visit does not resurrect seen records`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeHistoryStore()
            store.markAllSeen(10_000L)
            val reducer = OpenHistoryReducer(store, FakeClock(5_000L))

            reducer.reduce(HistoryAction.Open, HistoryState())

            assertEquals(10_000L, store.lastViewedNow())
        }

    // --- Close ---------------------------------------------------------------------------------

    @Test
    fun `Close is an identity branch and leaves playback alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val reducer = CloseHistoryReducer()
            // "screen == Main" is navigation state the host owns now; what remains pinned here
            // is the other half of the old case's name — the session state is left alone:
            // replay survives leaving the list.
            val state = HistoryState(playingId = "r1")

            val result = reducer.reduce(HistoryAction.Close, state)

            assertSame(state, result.state, "Close must not touch the record list or playback")
            assertEquals("r1", result.state.playingId, "replay survives leaving the list")
        }

    // --- PlayRecord ----------------------------------------------------------------------------

    @Test
    fun `PlayRecord looks the record up and starts the replay`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            val record = fixture.record()
            val reducer = PlayRecordReducer(fixture.controller, fixture.store)

            val result = reducer.reduce(HistoryAction.PlayRecord(record.id), HistoryState())

            assertEquals(record.id, fixture.controller.state.value.historyPlayingId)
            assertEquals(1, fixture.player.played.size, "the first frame is already out")
            assertEquals(result.state, HistoryState())
        }

    @Test
    fun `PlayRecord for a record that has vanished from the store does nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            val reducer = PlayRecordReducer(fixture.controller, fixture.store)

            reducer.reduce(HistoryAction.PlayRecord("gone"), HistoryState())

            assertNull(fixture.controller.state.value.historyPlayingId)
            assertEquals(0, fixture.player.prepared)
        }

    // --- StopRecordPlayback --------------------------------------------------------------------

    @Test
    fun `StopRecordPlayback silences a running replay`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            val record = fixture.record()
            PlayRecordReducer(fixture.controller, fixture.store)
                .reduce(HistoryAction.PlayRecord(record.id), HistoryState())

            StopRecordPlaybackReducer(fixture.controller)
                .reduce(HistoryAction.StopRecordPlayback, HistoryState())

            assertNull(fixture.controller.state.value.historyPlayingId)
            val framesAtStop = fixture.player.played.size
            advanceTimeBy(1_000)
            assertEquals(framesAtStop, fixture.player.played.size, "the pump stays dead")
        }

    @Test
    fun `StopRecordPlayback with nothing playing is a no-op`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)

            StopRecordPlaybackReducer(fixture.controller)
                .reduce(HistoryAction.StopRecordPlayback, HistoryState())

            assertEquals(0, fixture.player.prepared)
        }

    // --- DeleteRecord --------------------------------------------------------------------------

    @Test
    fun `DeleteRecord removes the record from the store`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            val record = fixture.record()
            val reducer = DeleteRecordReducer(fixture.controller, fixture.store)

            reducer.reduce(HistoryAction.DeleteRecord(record.id), HistoryState())

            assertTrue(fixture.store.recordsNow().isEmpty())
            assertTrue(fixture.store.files.isEmpty(), "the file goes with the index entry")
        }

    @Test
    fun `deleting the record that is playing stops the replay first`() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(backgroundScope)
            val record = fixture.record()
            PlayRecordReducer(fixture.controller, fixture.store)
                .reduce(HistoryAction.PlayRecord(record.id), HistoryState())
            val reducer = DeleteRecordReducer(fixture.controller, fixture.store)

            // The playing-id mirror HistoryViewModel keeps from the controller — this is the
            // field the old `state.ptt.historyPlayingId` read became.
            val state = HistoryState(playingId = fixture.controller.state.value.historyPlayingId)
            reducer.reduce(HistoryAction.DeleteRecord(record.id), state)

            assertTrue(fixture.store.recordsNow().isEmpty())
            assertNull(fixture.controller.state.value.historyPlayingId)
        }
}
