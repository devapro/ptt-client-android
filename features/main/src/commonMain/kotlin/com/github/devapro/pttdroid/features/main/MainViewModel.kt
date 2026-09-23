package com.github.devapro.pttdroid.features.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.devapro.pttdroid.domain.PttController
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
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
 * MVI shell for the Main screen.
 *
 * Mirrors connection/floor state in from [PttController] rather than owning it here — the
 * service, the overlay and the widget all read the same source. It **does** emit events
 * (`RequestMicPermission` from the mic gates) — its screen root collects [event] via
 * `rememberPttUiEvents`; a silently dropped one is known-issues #22.
 *
 * Lives in Koin's root scope (registered by `registerMainScreenDi` as a `single`, which every
 * host loads), not per-navigation-instance and not in `SESSION_SCOPE`: the session it fronts runs
 * on the latter regardless of which screen is up. The `single` is what makes its identity
 * structural under Voyager — the screen root `koinInject`s this type and no host threads it any
 * more, so one process-wide instance is a registration guarantee, not a resolution convention
 * (Koin 4's `viewModelOf` would be a fresh `ViewModel` per injection; see
 * [registerMainScreenDi]'s KDoc). As a `single` it also survives an Android `recreate()` — the
 * locale-change path.
 */
class MainViewModel(
    private val actionProcessor: MainActionProcessor,
    private val controller: PttController,
) : ViewModel(), MviViewModel<MainState, MainAction, UiEvent> {

    private val _state = MutableStateFlow(MainState())
    override val state = _state.asStateFlow()

    private val _event = Channel<UiEvent>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val event: Flow<UiEvent> = _event.receiveAsFlow()

    init {
        viewModelScope.launch {
            controller.state.collect { ptt -> _state.update { it.copy(ptt = ptt) } }
        }
    }

    /**
     * Mirrors the runtime microphone grant into [MainState.micPermissionGranted] and, on a
     * grant, dispatches [MainAction.InitConnection] — the session start that has always been the
     * consequence of the permission rather than an event in its own right.
     *
     * Hosts call this from every place the grant becomes known (Android: `onStart` and the
     * permission-dialog callback; desktop/iOS: once, unconditionally), which means a grant can
     * arrive while the session is already running. That is safe by construction, and was
     * re-verified before this dispatch was added: [PttController] starts nothing while its
     * session job is active, and the Android service's foreground promotion re-arms its
     * notification but starts the controller only on its first call. The repeated cost of a
     * redundant `InitConnection` is a re-affirmed foreground claim, never a re-dialled session.
     */
    fun onMicPermissionResult(granted: Boolean) {
        _state.update { it.copy(micPermissionGranted = granted) }
        if (granted) onAction(MainAction.InitConnection)
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
