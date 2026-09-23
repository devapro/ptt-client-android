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
 * Reads [HistoryState.playingId] rather than the old `state.ptt.historyPlayingId`: playing-id
 * is history's own fact now, mirrored in from the controller by `HistoryViewModel`, so this
 * reducer no longer reaches through a main-owned field to get it.
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
        if (state.playingId == action.recordId) {
            controller.stopHistoryPlayback()
        }
        historyStore.deleteRecord(action.recordId)
        return Reducer.Result(state)
    }
}
