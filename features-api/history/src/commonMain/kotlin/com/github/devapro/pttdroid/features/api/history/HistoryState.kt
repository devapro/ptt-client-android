package com.github.devapro.pttdroid.features.api.history

import com.github.devapro.pttdroid.history.VoiceRecord

/**
 * Everything the History screen renders.
 *
 * `records` is mirrored in from `HistoryStore.records` and `playingId` from
 * `PttState.historyPlayingId` — both by `HistoryViewModel`, the usual "view-scoped state is
 * derived, not shared" pattern. [playingId]
 * lives here rather than being read off `PttState` by the screen because the delete reducer
 * needs the same fact, and a feature must not reach into another feature's state.
 */
data class HistoryState(
    /** Received-while-backgrounded records, newest first. */
    val records: List<VoiceRecord> = emptyList(),
    /** The record currently replaying through the speaker, or null when nothing is. */
    val playingId: String? = null,
)
