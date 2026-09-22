package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/** Deletes a record — file and index together. If it was the playing one, playback stops first. */
class DeleteRecordReducer(
    private val controller: PttController,
    private val historyStore: HistoryStore,
) : Reducer<MainAction.DeleteRecord, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.DeleteRecord> = MainAction.DeleteRecord::class

    override suspend fun reduce(
        action: MainAction.DeleteRecord,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        if (state.ptt.historyPlayingId == action.recordId) {
            controller.stopHistoryPlayback()
        }
        historyStore.deleteRecord(action.recordId)
        return Reducer.Result(state)
    }
}
