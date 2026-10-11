package com.github.devapro.pttdroid.features.history.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Deletes a record — file and index together. If it was the playing one, playback stops first.
 *
 * The stop decision reads the controller's **live** `historyPlayingId`, never the mirrored
 * `state.playingId`: the mirror travels `HistoryPlayback → PttState → HistoryViewModel` through
 * three asynchronous collectors, so between dispatch and reduce it can still name the row that
 * stopped a moment ago — or miss one that started — and a playing row must *always* be stopped
 * when deleted (QA finding H14). `controller.state.value` is the one source that cannot be stale
 * here, so the mirror is not trusted for a decision with a speaker attached to it.
 *
 * It also removes the row from `records` locally and clears `playingId` when it stopped the row:
 * the store's re-emission is async, and the ViewModel merge applies exactly these reducer-made
 * mirror changes (fields the collectors advanced meanwhile keep the fresher collected value), so
 * the deleted row disappears from the open list without waiting for persistence to round-trip.
 */
class DeleteRecordReducer(
    private val controller: PttController,
    private val historyStore: HistoryStore,
) : Reducer<HistoryAction.DeleteRecord, HistoryState, HistoryAction, UiEvent> {

    override val actionClass: KClass<HistoryAction.DeleteRecord> =
        HistoryAction.DeleteRecord::class

    override suspend fun reduce(
        action: HistoryAction.DeleteRecord,
        state: HistoryState,
    ): Reducer.Result<HistoryState, HistoryAction, UiEvent?> {
        // Read the live source once: `stopHistoryPlayback()` nulls the playback's own id
        // synchronously, but the mirror in `PttState` follows a beat later — re-reading it below
        // would be a coin flip.
        val wasPlaying = controller.state.value.historyPlayingId == action.recordId
        if (wasPlaying) controller.stopHistoryPlayback()
        historyStore.deleteRecord(action.recordId)
        return Reducer.Result(
            state.copy(
                records = state.records.filterNot { it.id == action.recordId },
                playingId = if (wasPlaying) null else state.playingId,
            ),
        )
    }
}
