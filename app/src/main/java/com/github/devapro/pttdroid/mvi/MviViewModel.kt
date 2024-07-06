package com.github.devapro.pttdroid.mvi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MviViewModel<STATE, ACTION, EVENT> {

    val state: StateFlow<STATE>
    val event: Flow<EVENT>
    fun onAction(action: ACTION)
}