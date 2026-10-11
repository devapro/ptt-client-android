package com.github.devapro.pttdroid.history

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [HistoryBadge]'s unread derivation: a record is unread when it ended after `lastViewedAt`.
 * Driven with the same in-memory fakes the history reducers' tests use.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryBadgeTest {

    private fun record(id: String, endedAtMs: Long) =
        VoiceRecord(
            id = id,
            fileName = "$id.wav",
            startedAtMs = endedAtMs - 1,
            endedAtMs = endedAtMs,
            durationMs = 80,
            sizeBytes = 0,
            senderName = null,
        )

    @Test
    fun `no records means not unread`() = runTest(UnconfinedTestDispatcher()) {
        val badge = HistoryBadge(FakeHistoryStore(), FakeClock())

        assertEquals(false, badge.unread.value)
    }

    @Test
    fun `a record newer than lastViewedAt is unread`() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeHistoryStore()
        val badge = HistoryBadge(store, FakeClock())
        store.addRecord(record("r1", endedAtMs = 5_000L), Wav.toWav(ByteArray(1280)))

        assertEquals(true, badge.unread.value, "lastViewedAt 0 — never opened — makes every record new")
    }

    @Test
    fun `markSeen advancing lastViewedAt makes the record read again`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeHistoryStore()
            store.addRecord(record("r1", endedAtMs = 5_000L), Wav.toWav(ByteArray(1280)))
            val badge = HistoryBadge(store, FakeClock(nowMs = 10_000L))
            assertEquals(true, badge.unread.value)

            badge.markSeen()

            assertEquals(10_000L, store.lastViewedNow(), "opening the list is the read receipt")
            assertEquals(false, badge.unread.value)
        }
}
