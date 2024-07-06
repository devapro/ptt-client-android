package com.github.devapro.pttdroid.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.devapro.pttdroid.CoroutineContextProvider
import com.github.devapro.pttdroid.MainActionProcessor
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.MviViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivityViewModel(
    private val coroutineContextProvider: CoroutineContextProvider,
    private val actionProcessor: MainActionProcessor
): ViewModel(), MviViewModel<ScreenState, MainAction, MainEvent> {

    private val _state = MutableStateFlow<ScreenState>(ScreenState.NoConnection)
    override val state = _state.asStateFlow()

    private val _event = Channel<MainEvent>()
    override val event: Flow<MainEvent> = _event.receiveAsFlow()

    override fun onAction(action: MainAction) {
        viewModelScope.launch(coroutineContextProvider.io) {
            actionProcessor.process(
                action,
                _state.value
            ).let { result ->
                _state.update { result.state }
                result.event?.let { optionsEvent ->
                    _event.send(optionsEvent)
                }
                result.action?.let { nextAction ->
                    onAction(nextAction)
                }
            }
        }
    }
}