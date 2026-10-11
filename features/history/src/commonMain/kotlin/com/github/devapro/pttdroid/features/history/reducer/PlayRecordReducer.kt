package com.github.devapro.pttdroid.features.history.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.first

/**
 * Replays a stored record. Refused while the channel is busy in any direction — history never
 * talks over a live transmission, and the controller re-checks its own (fresher than [state])
 * floor state, so a race against an incoming `floor` message is settled there, not here.
 */
class PlayRecordReducer(
    private val controller: PttController,
    private val historyStore: HistoryStore,
) : Reducer<HistoryAction.PlayRecord, HistoryState, HistoryAction, UiEvent> {

    override val actionClass: KClass<HistoryAction.PlayRecord> = HistoryAction.PlayRecord::class

    override suspend fun reduce(
        action: HistoryAction.PlayRecord,
        state: HistoryState,
    ): Reducer.Result<HistoryState, HistoryAction, UiEvent?> {
        val record = historyStore.records.first().firstOrNull { it.id == action.recordId }
            ?: return Reducer.Result(state)
        controller.playHistory(record)
        return Reducer.Result(state)
    }
}
