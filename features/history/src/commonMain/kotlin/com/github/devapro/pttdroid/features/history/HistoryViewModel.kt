package com.github.devapro.pttdroid.features.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.history.HistoryBadge
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.MviViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI shell for the History screen.
 *
 * Owns no session or store data — `records` is mirrored in from [HistoryStore] and `playingId`
 * from [PttController]'s `historyPlayingId`, the usual "view-scoped state is derived, not
 * shared" pattern. `Open` dispatching
 * the read receipt through `OpenHistoryReducer` flips [HistoryBadge.unread] by itself; the
 * badge is exposed so a consumer that only holds this `ViewModel` can show it.
 *
 * Lives in Koin's root scope (registered by `registerHistoryScreenDi` as a `single`, which
 * every host loads), not per-navigation-instance: like every `ViewModel` here it must outlive
 * route pops — the session it fronts runs on [PttController] regardless of which screen is up.
 */
class HistoryViewModel(
    private val actionProcessor: HistoryActionProcessor,
    private val controller: PttController,
    private val historyStore: HistoryStore,
    private val badge: HistoryBadge,
) : ViewModel(), MviViewModel<HistoryState, HistoryAction, UiEvent> {

    private val _state = MutableStateFlow(HistoryState())
    override val state = _state.asStateFlow()

    private val _event = Channel<UiEvent>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val event: Flow<UiEvent> = _event.receiveAsFlow()

    /** Convenience pass-through; the derivation and its rationale live in [HistoryBadge]. */
    val unread: StateFlow<Boolean> get() = badge.unread

    init {
        viewModelScope.launch {
            controller.state.collect { ptt ->
                _state.update {
                    if (it.playingId != ptt.historyPlayingId) {
                        it.copy(playingId = ptt.historyPlayingId)
                    } else {
                        it
                    }
                }
            }
        }
        viewModelScope.launch {
            historyStore.records.collect { records ->
                _state.update { it.copy(records = records) }
            }
        }
    }

    override fun onAction(action: HistoryAction) {
        viewModelScope.launch {
            val snapshot = _state.value
            val result = actionProcessor.process(action, snapshot)
            // Merge, never clobber: `result.state` was built from the dispatch-time [snapshot],
            // but `reduce` suspends (store lookups, deletes), and the `init` collectors keep
            // writing fresher `records`/`playingId` into `_state` throughout. A raw assignment
            // here used to roll those async mirrors back — a deleted row reappearing in the open
            // list, a stopped/stale playing badge — until the next unrelated emission happened to
            // correct them (QA finding H14). The rule: a mirror field takes the reducer's value
            // only if the reducer *moved* it relative to what it was handed (e.g. DeleteRecord's
            // local row removal — that is why the delete reducer filters `records` itself rather
            // than waiting for the store re-emission); a field the collectors advanced while the
            // action was in flight keeps the collectors' fresher value. The collectors and the
            // store both converge to the same truth shortly after, so neither branch can strand
            // a field: the merge only decides which of two already-correct values is shown now.
            _state.update { current ->
                result.state.copy(
                    records =
                        if (current.records !== snapshot.records) current.records
                        else result.state.records,
                    playingId =
                        if (current.playingId != snapshot.playingId) current.playingId
                        else result.state.playingId,
                )
            }
            result.event?.let { _event.trySend(it) }
            result.action?.let { onAction(it) }
        }
    }
}
