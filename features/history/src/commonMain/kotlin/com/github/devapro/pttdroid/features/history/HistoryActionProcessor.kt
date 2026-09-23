package com.github.devapro.pttdroid.features.history

import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.ActionProcessor
import com.github.devapro.pttdroid.mvi.Reducer

/** The standard one-set [ActionProcessor] shell — history's actions, state and events. */
class HistoryActionProcessor(
    reducers: Set<Reducer<out HistoryAction, HistoryState, HistoryAction, UiEvent>>,
) : ActionProcessor<HistoryState, HistoryAction, UiEvent>(reducers)
