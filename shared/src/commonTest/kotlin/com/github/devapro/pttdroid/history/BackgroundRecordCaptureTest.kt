package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.audio.AudioConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Exercises the record boundary against the double of `PttController`'s floor event stream:
 * floor to a remote holder starts a record when backgrounded, release/disconnect closes it,
 * and answering inside the window deletes what was just left.
 *
 * Every test runs on an unconfined dispatcher and hands its own scope to the capture, so the
 * saves and answer-purges that [finish][BackgroundRecordCapture] launches land before the
 * next assertion — the production scope is the long-lived service scope, where they would not.
 */
class BackgroundRecordCaptureTest {

    private fun frame(bytes: Byte) = ByteArray(AudioConfig.FRAME_BYTES, { bytes })

    private fun capture(
        testScope: TestScope,
        store: FakeHistoryStore,
        foreground: FakeForeground,
        clock: FakeClock,
        maxFrames: Int = BackgroundRecordCapture.DEFAULT_MAX_FRAMES,
    ) = BackgroundRecordCapture(
        store = store,
        clock = clock,
        isForeground = foreground,
        scope = CoroutineScope(UnconfinedTestDispatcher(testScope.testScheduler)),
        maxFrames = maxFrames,
    )

    @Test
    fun `a backgrounded transmission becomes one record bounded by the floor`() = runTest(
        UnconfinedTestDispatcher(),
    ) {
        val store = FakeHistoryStore()
        val foreground = FakeForeground(false)
        val clock = FakeClock(10_000L)
        val capture = capture(this, store, foreground, clock)

        capture.onFloor("alice", "Alice", isSelf = false)
        capture.onAudioFrame(frame(1))
        capture.onAudioFrame(frame(2))
        clock.nowMs = 15_000L
        capture.onFloor(null, null, isSelf = false)

        val record = store.recordsNow().single()
        assertEquals("Alice", record.senderName)
        assertEquals(10_000L, record.startedAtMs)
        assertEquals(15_000L, record.endedAtMs)
        assertEquals(
            2L * AudioConfig.FRAME_BYTES * 1000L / 32_000L,
            record.durationMs,
        )
        val pcm = Wav.pcmFromWav(store.files.getValue(record.fileName))
        assertEquals(2 * AudioConfig.FRAME_BYTES, pcm?.size)
        assertTrue(pcm!!.all { it == 1.toByte() || it == 2.toByte() })
    }

    @Test
    fun `a foreground transmission is never recorded`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeHistoryStore()
        val capture = capture(this, store, FakeForeground(true), FakeClock())

        capture.onFloor("alice", "Alice", isSelf = false)
        capture.onAudioFrame(frame(1))
        capture.onFloor(null, null, isSelf = false)

        assertTrue(store.recordsNow().isEmpty())
    }

    @Test
    fun `a record that came from the background is saved even after the app opened`() = runTest(UnconfinedTestDispatcher()) {
        // Spec: foreground-ness is fixed at the record's start; the beginning was already missed.
        val store = FakeHistoryStore()
        val foreground = FakeForeground(false)
        val capture = capture(this, store, foreground, FakeClock())

        capture.onFloor("alice", "Alice", isSelf = false)
        foreground.setForeground(true)
        capture.onAudioFrame(frame(1))
        capture.onFloor(null, null, isSelf = false)

        assertEquals(1, store.recordsNow().size)
    }

    @Test
    fun `a transmission that started in the foreground stays unrecorded even after backgrounding`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeHistoryStore()
            val foreground = FakeForeground(true)
            val capture = capture(this, store, foreground, FakeClock())

            capture.onFloor("alice", "Alice", isSelf = false)
            foreground.setForeground(false)
            capture.onAudioFrame(frame(1))
            capture.onFloor(null, null, isSelf = false)

            assertTrue(store.recordsNow().isEmpty())
        }

    @Test
    fun `the floor changing hands closes the old record and starts a new one`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeHistoryStore()
        val capture = capture(this, store, FakeForeground(false), FakeClock())

        capture.onFloor("alice", "Alice", isSelf = false)
        capture.onAudioFrame(frame(1))
        capture.onFloor("bob", "Bob", isSelf = false)
        capture.onAudioFrame(frame(2))
        capture.onFloor(null, null, isSelf = false)

        val records = store.recordsNow()
        assertEquals(2, records.size)
        assertEquals("Bob", records[0].senderName)
        assertEquals("Alice", records[1].senderName)
    }

    @Test
    fun `a disconnected session still saves the partial record`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeHistoryStore()
        val capture = capture(this, store, FakeForeground(false), FakeClock())

        capture.onFloor("alice", "Alice", isSelf = false)
        capture.onAudioFrame(frame(1))
        capture.onSessionClosed()

        assertEquals(1, store.recordsNow().size)
    }

    @Test
    fun `an over-long transmission is capped and closed at the frame limit`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeHistoryStore()
        val capture = capture(this, store, FakeForeground(false), FakeClock(), maxFrames = 3)

        capture.onFloor("alice", "Alice", isSelf = false)
        repeat(5) { capture.onAudioFrame(frame(1)) }

        val record = store.recordsNow().single()
        assertEquals(3, Wav.pcmFromWav(store.files.getValue(record.fileName))!!.size / AudioConfig.FRAME_BYTES)
        // Further frames of the same, now-finished, transmission go nowhere.
        capture.onAudioFrame(frame(2))
        assertEquals(1, store.recordsNow().size)
    }

    @Test
    fun `a silent hold records nothing`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeHistoryStore()
        val capture = capture(this, store, FakeForeground(false), FakeClock())

        capture.onFloor("alice", "Alice", isSelf = false)
        capture.onFloor(null, null, isSelf = false)

        assertTrue(store.recordsNow().isEmpty())
    }

    @Test
    fun `answering deletes every record that ended inside the window and keeps older ones`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeHistoryStore()
            val clock = FakeClock(100_000L)
            val capture = capture(this, store, FakeForeground(false), clock)

            // An old record, outside the window.
            capture.onFloor("alice", "Alice", isSelf = false)
            capture.onAudioFrame(frame(1))
            capture.onFloor(null, null, isSelf = false)

            clock.nowMs = 100_000L + BackgroundRecordCapture.ANSWER_WINDOW_MS + 1

            // A fresh one, inside it.
            capture.onFloor("bob", "Bob", isSelf = false)
            capture.onAudioFrame(frame(2))
            capture.onFloor(null, null, isSelf = false)
            assertEquals(2, store.recordsNow().size)

            capture.onAnsweredBySelf()

            val kept = store.recordsNow()
            assertEquals(1, kept.size)
            assertEquals("Alice", kept.single().senderName)
            assertEquals(1, store.files.size, "only the kept record's file survives")
        }

    @Test
    fun `the frame cap is two minutes of wire audio`() {
        val bytesIn = BackgroundRecordCapture.DEFAULT_MAX_FRAMES * AudioConfig.FRAME_BYTES
        val ms = bytesIn * 1000L / (AudioConfig.SAMPLE_RATE_HZ * AudioConfig.BYTES_PER_SAMPLE)
        assertEquals(BackgroundRecordCapture.MAX_RECORDING_MS, ms)
    }
}
