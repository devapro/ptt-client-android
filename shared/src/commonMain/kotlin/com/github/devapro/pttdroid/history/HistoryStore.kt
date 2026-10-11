package com.github.devapro.pttdroid.history

import kotlinx.coroutines.flow.Flow

/**
 * The persisted history: newest-first [records] plus the WAV bytes each one names.
 *
 * An interface rather than the DataStore class itself so the capture and the reducers can be
 * unit-tested on the JVM with an in-memory fake — the same seam `PttConnection` exists for.
 */
interface HistoryStore {
    /** Newest first; the order [addRecord] maintains. */
    val records: Flow<List<VoiceRecord>>

    /** Epoch millis of the last time the history screen was opened; 0 before that ever happened. */
    val lastViewedAt: Flow<Long>

    /**
     * Stores [wav] under `record.fileName` and prepends [record], trimming to the retention
     * cap. False when the file write failed — a record whose audio is not on disk is worse than
     * no record at all, so nothing is added to the index in that case.
     */
    suspend fun addRecord(record: VoiceRecord, wav: ByteArray): Boolean

    suspend fun deleteRecord(id: String)

    /** Deletes every record that ended at or after [cutoffMs]; returns what was deleted. */
    suspend fun deleteEndedAfter(cutoffMs: Long): List<VoiceRecord>

    suspend fun markAllSeen(nowMs: Long)

    /** The record's WAV bytes, or null when the file has gone missing. */
    suspend fun wavOf(record: VoiceRecord): ByteArray?

    /**
     * Deletes stored files no record points at any more — the residue of a write that landed
     * before the process died with the index edit still pending. Called once on service start.
     */
    suspend fun pruneOrphans()
}
