package com.github.devapro.pttdroid.mvi

import com.github.devapro.pttdroid.PttLog

/**
 * Dispatches an action to the one reducer registered for its type.
 *
 * The set is declared with an `out` projection so concrete reducers (each typed to a single
 * action subtype) can be collected together; the cast in [process] is guarded by the
 * `actionClass` match immediately above it.
 */
abstract class ActionProcessor<STATE, ACTION : Any, EVENT>(
    private val reducers: Set<Reducer<out ACTION, STATE, ACTION, EVENT>>,
) {
    init {
        // Validated once at construction. This used to run on every single dispatch.
        val duplicates = reducers.groupBy { it.actionClass }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Reducers must have unique action classes; duplicated: $duplicates"
        }
    }

    private val byActionClass = reducers.associateBy { it.actionClass }

    suspend fun process(action: ACTION, state: STATE): Reducer.Result<STATE, ACTION?, EVENT?> {
        @Suppress("UNCHECKED_CAST")
        val reducer = byActionClass[action::class] as? Reducer<ACTION, STATE, ACTION, EVENT>
            ?: error("No reducer registered for action ${action::class.simpleName}")

        return try {
            reducer.reduce(action, state)
        } catch (e: ClassCastException) {
            // A reducer declared a narrower STATE than it actually received.
            PttLog.e(e) { "Reducer ${reducer::class.simpleName} rejected the current state" }
            Reducer.Result(state)
        }
    }
}
