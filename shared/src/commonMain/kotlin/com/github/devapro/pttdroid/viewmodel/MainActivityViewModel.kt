package com.github.devapro.pttdroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.devapro.pttdroid.MainActionProcessor
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.history.HistoryStore
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.MviViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI shell for the UI.
 *
 * Connection/floor state is mirrored in from [PttController] rather than being owned here —
 * the service, the overlay and the widget all read the same source.
 */
class MainActivityViewModel(
    private val actionProcessor: MainActionProcessor,
    private val controller: PttController,
    private val historyStore: HistoryStore,
) : ViewModel(), MviViewModel<ScreenState, MainAction, MainEvent> {

    private val _state = MutableStateFlow(ScreenState())
    override val state = _state.asStateFlow()

    private val _event = Channel<MainEvent>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val event: Flow<MainEvent> = _event.receiveAsFlow()

    init {
        viewModelScope.launch {
            controller.state.collect { ptt -> _state.update { it.copy(ptt = ptt) } }
        }
        viewModelScope.launch {
            // Unread is derived, not stored per record: "new" means "arrived after the last time
            // the list was opened" (lastViewedAt 0 — never opened — makes every record new).
            combine(historyStore.records, historyStore.lastViewedAt) { records, lastViewed ->
                records to records.any { it.endedAtMs > lastViewed }
            }.collect { (records, unread) ->
                _state.update { it.copy(history = records, historyUnread = unread) }
            }
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        _state.update { it.copy(micPermissionGranted = granted) }
        if (granted) onAction(MainAction.InitConnection)
    }

    /**
     * Mirrors "draw over other apps" into [ScreenState.canDrawOverlay], the way
     * [onMicPermissionResult] mirrors the microphone grant. The platform layer polls this on
     * every entry to Settings, since unlike the microphone there is no runtime callback for it.
     */
    fun onOverlayPermissionResult(granted: Boolean) {
        _state.update { it.copy(canDrawOverlay = granted) }
    }

    override fun onAction(action: MainAction) {
        viewModelScope.launch {
            val result = actionProcessor.process(action, _state.value)
            _state.value = result.state
            result.event?.let { _event.trySend(it) }
            result.action?.let { onAction(it) }
        }
    }
}
