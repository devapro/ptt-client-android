package com.github.devapro.pttdroid.history

import kotlinx.serialization.Serializable

/**
 * One received transmission recorded while the app was in the background.
 *
 * [fileName] is the name inside the history directory, not a full path — the path is the
 * [HistoryFileStorage] implementation's business, and this object survives being stored in
 * DataStore and restored on another build of the app data dir.
 */
@Serializable
data class VoiceRecord(
    val id: String,
    val fileName: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    /** The talker's display name as the relay reported it, or null if it never did. */
    val senderName: String? = null,
)
