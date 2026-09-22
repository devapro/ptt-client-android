package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.PttLog
import com.github.devapro.pttdroid.domain.UtcClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [HistoryStore] over a preferences DataStore (the index) plus [HistoryFileStorage] (the audio).
 *
 * The index is one JSON list under a single key rather than a key per record: the whole list is
 * read on every screen entry and rewritten whole on every add/delete, which is exactly what
 * `SettingsRepository.save` already does for settings, and it keeps the FIFO trim — which has
 * to see *all* records to know what it is evicting — a single transaction instead of a scan.
 *
 * Takes the `DataStore<Preferences>` directly (a second, history-named store, bound by the
 * platform DI modules) for the same reason `SettingsRepository` does: to keep DataStore and
 * therefore any platform context out of everything that consumes this class.
 */
class DataStoreHistoryStore(
    private val dataStore: DataStore<Preferences>,
    private val fileStorage: HistoryFileStorage,
    private val clock: UtcClock,
) : HistoryStore {

    override val records: Flow<List<VoiceRecord>> = dataStore.data.map { prefs ->
        prefs[KEY_INDEX]?.let { decode(it) }.orEmpty()
    }

    override val lastViewedAt: Flow<Long> = dataStore.data.map { prefs ->
        prefs[KEY_LAST_VIEWED] ?: 0L
    }

    override suspend fun addRecord(record: VoiceRecord, wav: ByteArray): Boolean {
        if (!fileStorage.write(record.fileName, wav)) return false
        var evicted: List<VoiceRecord> = emptyList()
        edit { prefs ->
            val current = prefs[KEY_INDEX]?.let { decode(it) }.orEmpty()
            val kept = (listOf(record) + current).take(MAX_RECORDS)
            // The record just written always survives; only what fell off the tail of the old
            // list goes, and only when the new list is actually full.
            evicted = if (kept.size == MAX_RECORDS) current.drop(MAX_RECORDS - 1) else emptyList()
            prefs[KEY_INDEX] = encode(kept)
        }
        evicted.forEach { fileStorage.delete(it.fileName) }
        return true
    }

    override suspend fun deleteRecord(id: String) {
        var fileName: String? = null
        edit { prefs ->
            val current = prefs[KEY_INDEX]?.let { decode(it) }.orEmpty()
            fileName = current.firstOrNull { it.id == id }?.fileName
            val kept = current.filterNot { it.id == id }
            if (kept.isEmpty()) prefs.remove(KEY_INDEX) else prefs[KEY_INDEX] = encode(kept)
        }
        fileName?.let { fileStorage.delete(it) }
    }

    override suspend fun deleteEndedAfter(cutoffMs: Long): List<VoiceRecord> {
        var removed: List<VoiceRecord> = emptyList()
        edit { prefs ->
            val current = prefs[KEY_INDEX]?.let { decode(it) }.orEmpty()
            removed = current.filter { it.endedAtMs >= cutoffMs }
            val kept = current.filter { it.endedAtMs < cutoffMs }
            if (kept.isEmpty()) prefs.remove(KEY_INDEX) else prefs[KEY_INDEX] = encode(kept)
        }
        removed.forEach { fileStorage.delete(it.fileName) }
        return removed
    }

    override suspend fun markAllSeen(nowMs: Long) = edit { prefs ->
        // Monotonic: a clock that jumped backwards must not resurrect records as unread.
        if (nowMs > (prefs[KEY_LAST_VIEWED] ?: 0L)) prefs[KEY_LAST_VIEWED] = nowMs
    }

    override suspend fun wavOf(record: VoiceRecord): ByteArray? = fileStorage.read(record.fileName)

    override suspend fun pruneOrphans() {
        val referenced = records.first().mapTo(mutableSetOf()) { it.fileName }
        fileStorage.list()
            .filterNot { it in referenced }
            .forEach { fileStorage.delete(it) }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun encode(records: List<VoiceRecord>): String = Json.encodeToString(records)

    private fun decode(text: String): List<VoiceRecord>? = try {
        Json.decodeFromString<List<VoiceRecord>>(text)
    } catch (e: Exception) {
        // A corrupt index must not wedge the store: read it as empty, and the next successful
        // add overwrites it. Orphaned WAV files on disk go with it — nothing lists them any more.
        PttLog.e { "History index unreadable; starting over: ${e.message}" }
        null
    }

    companion object {
        /** Retention cap: the newest N records, older ones dropped file and index together. */
        const val MAX_RECORDS = 50

        private val KEY_INDEX = stringPreferencesKey("history_index")
        private val KEY_LAST_VIEWED = longPreferencesKey("history_last_viewed")
    }
}
