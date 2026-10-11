package com.github.devapro.pttdroid.features.history.reducer

import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Purely an identity branch now that navigation is host state — but it is still dispatched, and
 * it still must **not** stop playback: replay survives leaving the list, which is the behaviour
 * `HistoryReducersTest` pins.
 */
class CloseHistoryReducer :
    Reducer<HistoryAction.Close, HistoryState, HistoryAction, UiEvent> {

    override val actionClass: KClass<HistoryAction.Close> = HistoryAction.Close::class

    override suspend fun reduce(
        action: HistoryAction.Close,
        state: HistoryState,
    ): Reducer.Result<HistoryState, HistoryAction, UiEvent?> = Reducer.Result(state)
}
