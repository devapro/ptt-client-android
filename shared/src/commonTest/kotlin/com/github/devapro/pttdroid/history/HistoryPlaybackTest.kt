package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.audio.AudioConfig
import com.github.devapro.pttdroid.audio.VoicePlayerContract
import com.github.devapro.pttdroid.data.settings.AudioOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

/**
 * The replay pump: frames in wire order at wire rate, an id that reports what is audible, and a
 * stop that is immediate. Pacing is asserted against virtual time — the real 40 ms `delay`s are
 * what keep a two-minute record from being dumped into a non-blocking AudioTrack at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryPlaybackTest {

    private class RecordingPlayer : VoicePlayerContract {
        var prepared = 0
        var prepareSucceeds = true
        var stops = 0
        val played = mutableListOf<ByteArray>()
        override fun prepare(): Boolean {
            prepared++
            return prepareSucceeds
        }

        override fun play(pcm: ByteArray) {
            played += pcm
        }

        override fun stop() {
            stops++
        }

        override fun setOutput(output: AudioOutput) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun release() = Unit
    }

    /** A record of [frames] whole frames, frame *k* filled with byte *k*, on the store. */
    private suspend fun storeWith(frames: Int): Pair<FakeHistoryStore, VoiceRecord> {
        val pcm = ByteArray(frames * AudioConfig.FRAME_BYTES) { (it / AudioConfig.FRAME_BYTES).toByte() }
        val record = VoiceRecord(
            id = "r1",
            fileName = "r1.wav",
            startedAtMs = 0,
            endedAtMs = 1,
            durationMs = pcm.size * 1000L / 32_000L,
            sizeBytes = pcm.size.toLong(),
            senderName = null,
        )
        val store = FakeHistoryStore()
        store.addRecord(record, Wav.toWav(pcm))
        return store to record
    }

    @Test
    fun `a record plays out frame by frame at the wire rate`() {
        runTest(UnconfinedTestDispatcher()) {
            val (store, record) = storeWith(frames = 3)
            val player = RecordingPlayer()
            val playback = HistoryPlayback(player, store, this)

            playback.play(record)
            // The unconfined scope runs the pump up to its first delay, so the first frame is
            // already out; the rest arrive on schedule, one 40 ms frame-step at a time.
            // (advanceTimeBy stops just short of the moment it reaches, hence the runCurrent.)
            assertEquals(1, player.played.size)
            assertEquals("r1", playback.playingId.value)

            advanceTimeBy(40)
            testScheduler.runCurrent()
            assertEquals(2, player.played.size)

            advanceTimeBy(40)
            testScheduler.runCurrent()
            assertEquals(3, player.played.size)
            assertEquals(null, playback.playingId.value, "a finished pump reports nothing playing")
            assertFalse(playback.isPlaying)

            assertEquals(
                listOf(0, 1, 2),
                player.played.map { it.first().toInt() },
                "frames must reach the speaker in order",
            )
            assertTrue(player.played.all { it.size == AudioConfig.FRAME_BYTES })
            assertEquals(1, player.prepared, "one prepare per playback, not one per frame")
        }
    }

    @Test
    fun `stop falls silent on the next frame tick and clears the id`() {
        runTest(UnconfinedTestDispatcher()) {
            val (store, record) = storeWith(frames = 5)
            val player = RecordingPlayer()
            val playback = HistoryPlayback(player, store, this)

            playback.play(record)
            playback.stop()

            assertEquals(1, player.played.size, "the frame already handed over cannot be recalled")
            assertEquals(
                1,
                player.stops,
                "stop must flush the player too — the buffered tail is what the user would still hear",
            )
            assertEquals(null, playback.playingId.value)
            assertFalse(playback.isPlaying)

            advanceTimeBy(1_000)
            assertEquals(1, player.played.size, "a stopped pump must not resume")
        }
    }

    @Test
    fun `starting a second record cuts over from the first`() {
        runTest(UnconfinedTestDispatcher()) {
            val (store, first) = storeWith(frames = 4)
            val second = first.copy(id = "r2", fileName = "r2.wav")
            store.addRecord(second, Wav.toWav(ByteArray(2 * AudioConfig.FRAME_BYTES) { 9 }))
            val player = RecordingPlayer()
            val playback = HistoryPlayback(player, store, this)

            playback.play(first)
            playback.play(second)

            assertEquals("r2", playback.playingId.value)
            advanceTimeBy(1_000)
            assertEquals(2 + 1, player.played.size, "one frame of r1 before the cut, then r2's two")
            assertEquals(9.toByte(), player.played.last().first())
            assertEquals(null, playback.playingId.value)
        }
    }

    @Test
    fun `a record whose file is gone fails quietly`() {
        runTest(UnconfinedTestDispatcher()) {
            val (store, record) = storeWith(frames = 1)
            store.files.clear()
            val player = RecordingPlayer()
            val playback = HistoryPlayback(player, store, this)

            playback.play(record)

            assertEquals(null, playback.playingId.value)
            assertEquals(0, player.prepared)
            assertFalse(playback.isPlaying)
        }
    }

    @Test
    fun `a player that cannot be set up aborts the replay`() {
        runTest(UnconfinedTestDispatcher()) {
            val (store, record) = storeWith(frames = 2)
            val player = RecordingPlayer().apply { prepareSucceeds = false }
            val playback = HistoryPlayback(player, store, this)

            playback.play(record)

            assertEquals(1, player.prepared)
            assertTrue(player.played.isEmpty())
            assertEquals(null, playback.playingId.value)
        }
    }
}
