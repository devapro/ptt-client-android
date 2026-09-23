package com.github.devapro.pttdroid.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.MviViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI shell for the Settings screen.
 *
 * Owns [SettingsState] outright — unlike `HistoryViewModel` in `:features:history`, which mirrors
 * store data in, this one has nothing to mirror: the form is seeded once by
 * `OpenSettingsReducer` from the repository and owned by this view model from then on, which is
 * exactly what keeps an unrelated settings write from wiping in-progress edits. It **does** emit
 * events (the save's `ShowMessage`, `RequestOverlayPermission`) — its screen root collects
 * [event] via `rememberPttUiEvents`; a dropped one is known-issues #22.
 *
 * Lives in Koin's root scope (registered by `registerSettingsScreenDi` as a `single`, which every
 * host loads), not per-navigation-instance and not in `SESSION_SCOPE` — the session it reconnects
 * ([SettingsAction.Reconnect] via `PttController`) runs on the latter regardless of which screen
 * is up. The `single` is what lets the Settings screen root simply `koinInject` its own view
 * model under Voyager and still be certain it talks to *the* instance: Koin 4's `viewModelOf`
 * would create a fresh view model per injection, and a second one's [event] flow would silently
 * never reach a snackbar. As a `single` it also survives an Android `recreate()`, which is how
 * the in-progress form used to survive one through the `ViewModelStore`.
 */
class SettingsViewModel(
    private val actionProcessor: SettingsActionProcessor,
) : ViewModel(), MviViewModel<SettingsState, SettingsAction, UiEvent> {

    private val _state = MutableStateFlow(SettingsState())
    override val state = _state.asStateFlow()

    private val _event = Channel<UiEvent>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val event: Flow<UiEvent> = _event.receiveAsFlow()

    /**
     * Mirrors "draw over other apps" into [SettingsState.canDrawOverlay].
     *
     * Android's `MainActivity.onResume` polls `canDrawOverlays()` and calls this with the result;
     * desktop and iOS have no such permission and never call it, leaving the field's warning-free
     * default in place.
     */
    fun onOverlayPermissionResult(granted: Boolean) {
        _state.update { it.copy(canDrawOverlay = granted) }
    }

    override fun onAction(action: SettingsAction) {
        viewModelScope.launch {
            val result = actionProcessor.process(action, _state.value)
            _state.value = result.state
            result.event?.let { _event.trySend(it) }
            result.action?.let { onAction(it) }
        }
    }
}
