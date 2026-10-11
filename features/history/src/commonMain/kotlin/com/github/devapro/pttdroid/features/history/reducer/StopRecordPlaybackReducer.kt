package com.github.devapro.pttdroid.features.history.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/** Stops a history replay. No-op — like every other stop here — when nothing is playing. */
class StopRecordPlaybackReducer(
    private val controller: PttController,
) : Reducer<HistoryAction.StopRecordPlayback, HistoryState, HistoryAction, UiEvent> {

    override val actionClass: KClass<HistoryAction.StopRecordPlayback> =
        HistoryAction.StopRecordPlayback::class

    override suspend fun reduce(
        action: HistoryAction.StopRecordPlayback,
        state: HistoryState,
    ): Reducer.Result<HistoryState, HistoryAction, UiEvent?> {
        controller.stopHistoryPlayback()
        return Reducer.Result(state)
    }
}
