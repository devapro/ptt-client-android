package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.domain.UtcClock
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Opens the history screen and marks everything currently in it as seen — opening the list is
 * the read receipt, per spec, so the badge is gone the moment the user is looking at the records
 * rather than after each one has been played.
 */
class OpenHistoryReducer(
    private val historyStore: HistoryStore,
    private val clock: UtcClock,
) : Reducer<MainAction.OpenHistory, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.OpenHistory> = MainAction.OpenHistory::class

    override suspend fun reduce(
        action: MainAction.OpenHistory,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        historyStore.markAllSeen(clock.nowMs())
        return Reducer.Result(
            state.copy(screen = ScreenState.Screen.History, historyUnread = false),
        )
    }
}
