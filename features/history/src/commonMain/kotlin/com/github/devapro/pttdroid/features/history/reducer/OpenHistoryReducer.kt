package com.github.devapro.pttdroid.features.history.reducer

import com.github.devapro.pttdroid.domain.UtcClock
import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Marks everything currently in the list as seen — opening the list is the read receipt, per
 * spec, so the badge is gone the moment the user is looking at the records rather than after
 * each one has been played. That write flips `HistoryBadge.unread` on its own, so this reducer
 * touches no state: the old `historyUnread = false` and `screen =` writes both left with the
 * split (navigation is host state now).
 */
class OpenHistoryReducer(
    private val historyStore: HistoryStore,
    private val clock: UtcClock,
) : Reducer<HistoryAction.Open, HistoryState, HistoryAction, UiEvent> {

    override val actionClass: KClass<HistoryAction.Open> = HistoryAction.Open::class

    override suspend fun reduce(
        action: HistoryAction.Open,
        state: HistoryState,
    ): Reducer.Result<HistoryState, HistoryAction, UiEvent?> {
        historyStore.markAllSeen(clock.nowMs())
        return Reducer.Result(state)
    }
}
