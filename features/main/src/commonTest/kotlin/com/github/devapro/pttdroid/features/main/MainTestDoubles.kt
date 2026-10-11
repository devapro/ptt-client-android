package com.github.devapro.pttdroid.features.main

import com.github.devapro.pttdroid.domain.ForegroundProvider
import com.github.devapro.pttdroid.domain.UtcClock
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.history.VoiceRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Minimal in-memory [HistoryStore] for Main's reducer tests — the real DataStore-backed one is
 * JVM-only, and `:shared`'s test source sets (and `:features:history`'s copy of them) are not
 * consumable across modules, so this is the same duplication note as
 * `:features/history/src/commonTest/.../HistoryTestDoubles.kt`: a behaviour-locked copy; if one
 * changes, consider changing all. Kept private to this module and leaner than history's copy —
 * Main's reducers never read or write records, they just need a [HistoryStore] to hand
 * `BackgroundRecordCapture`/`HistoryPlayback` when building a real `PttController`.
 */
internal class FakeHistoryStore : HistoryStore {
    private val _records = MutableStateFlow<List<VoiceRecord>>(emptyList())
    private val _lastViewedAt = MutableStateFlow(0L)

    override val records: Flow<List<VoiceRecord>> = _records.asStateFlow()
    override val lastViewedAt: Flow<Long> = _lastViewedAt.asStateFlow()

    override suspend fun addRecord(record: VoiceRecord, wav: ByteArray): Boolean {
        _records.update { listOf(record) + it }
        return true
    }

    override suspend fun deleteRecord(id: String) {
        _records.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun deleteEndedAfter(cutoffMs: Long): List<VoiceRecord> {
        val removed = _records.value.filter { it.endedAtMs >= cutoffMs }
        _records.update { list -> list.filter { it.endedAtMs < cutoffMs } }
        return removed
    }

    override suspend fun markAllSeen(nowMs: Long) {
        if (nowMs > _lastViewedAt.value) _lastViewedAt.value = nowMs
    }

    override suspend fun wavOf(record: VoiceRecord): ByteArray? = null

    override suspend fun pruneOrphans() = Unit
}

internal class FakeClock(var nowMs: Long = 1_000_000L) : UtcClock {
    override fun nowMs(): Long = nowMs
}

internal class FakeForeground(private var foreground: Boolean) : ForegroundProvider {
    override fun isForeground(): Boolean = foreground
    fun setForeground(value: Boolean) {
        foreground = value
    }
}
