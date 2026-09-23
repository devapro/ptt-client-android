package com.github.devapro.pttdroid.features.api.history

/**
 * History-screen intents ([Open] and [Close] carry no payload; the record-carrying ones key on
 * a `recordId`). Navigation itself is not an action: entering and leaving the screen is the
 * Voyager `Navigator`'s stack, so [Open] exists only to seed state on entry and
 * [Close] only to undo in-flight playback.
 */
sealed interface HistoryAction {

    /** Open the history screen. Viewing the list is what clears the unread badge. */
    data object Open : HistoryAction

    data object Close : HistoryAction

    /** Replay a stored record through the speaker, unless the channel is busy. */
    data class PlayRecord(val recordId: String) : HistoryAction

    data object StopRecordPlayback : HistoryAction

    data class DeleteRecord(val recordId: String) : HistoryAction
}
