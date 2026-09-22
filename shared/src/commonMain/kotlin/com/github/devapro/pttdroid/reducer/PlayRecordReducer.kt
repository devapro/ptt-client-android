package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
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
) : Reducer<MainAction.PlayRecord, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.PlayRecord> = MainAction.PlayRecord::class

    override suspend fun reduce(
        action: MainAction.PlayRecord,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        val record = historyStore.records.first().firstOrNull { it.id == action.recordId }
            ?: return Reducer.Result(state)
        controller.playHistory(record)
        return Reducer.Result(state)
    }
}
