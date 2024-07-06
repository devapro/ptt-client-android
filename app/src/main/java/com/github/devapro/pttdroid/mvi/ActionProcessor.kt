package com.github.devapro.pttdroid.mvi

import timber.log.Timber

abstract class ActionProcessor<STATE, ACTION : Any, EVENT>(
    private val reducers: Set<Reducer<ACTION, STATE, ACTION, EVENT>>
) {

    suspend fun process(action: ACTION, state: STATE): Reducer.Result<STATE, ACTION?, EVENT?> {
        if (reducers.distinctBy { it.actionClass }.size != reducers.size) {
            throw Exception("Reducers must have unique action classes")
        }
        val result = try {
            reducers.firstOrNull { it.actionClass == action::class }?.reduce(action, state)
        } catch (e: ClassCastException) {
            Timber.e(e)
            // return the same state in case the expected state does not match the actual state
            Reducer.Result(state)
        }
        return result ?: throw Exception("Reducer for action ${action::class} not found")
    }
}