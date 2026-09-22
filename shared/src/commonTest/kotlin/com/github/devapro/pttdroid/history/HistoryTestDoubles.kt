package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.domain.ForegroundProvider
import com.github.devapro.pttdroid.domain.UtcClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [HistoryStore] for common tests — the real DataStore-backed one is JVM-only. */
class FakeHistoryStore : HistoryStore {
    private val _records = MutableStateFlow<List<VoiceRecord>>(emptyList())
    private val _lastViewedAt = MutableStateFlow(0L)

    val files = mutableMapOf<String, ByteArray>()
    var addFails = false

    override val records: Flow<List<VoiceRecord>> = _records.asStateFlow()
    override val lastViewedAt: Flow<Long> = _lastViewedAt.asStateFlow()

    override suspend fun addRecord(record: VoiceRecord, wav: ByteArray): Boolean {
        if (addFails) return false
        files[record.fileName] = wav
        _records.update { listOf(record) + it }
        return true
    }

    override suspend fun deleteRecord(id: String) {
        var fileName: String? = null
        _records.update { list ->
            fileName = list.firstOrNull { it.id == id }?.fileName
            list.filterNot { it.id == id }
        }
        fileName?.let { files.remove(it) }
    }

    override suspend fun deleteEndedAfter(cutoffMs: Long): List<VoiceRecord> {
        val removed = _records.value.filter { it.endedAtMs >= cutoffMs }
        _records.update { list -> list.filter { it.endedAtMs < cutoffMs } }
        removed.forEach { files.remove(it.fileName) }
        return removed
    }

    override suspend fun markAllSeen(nowMs: Long) {
        if (nowMs > _lastViewedAt.value) _lastViewedAt.value = nowMs
    }

    override suspend fun wavOf(record: VoiceRecord): ByteArray? = files[record.fileName]

    override suspend fun pruneOrphans() = Unit

    fun recordsNow(): List<VoiceRecord> = _records.value
    fun lastViewedNow(): Long = _lastViewedAt.value
}

class FakeClock(var nowMs: Long = 1_000_000L) : UtcClock {
    override fun nowMs(): Long = nowMs
}

class FakeForeground(private var foreground: Boolean) : ForegroundProvider {
    override fun isForeground(): Boolean = foreground
    fun setForeground(value: Boolean) {
        foreground = value
    }
}
