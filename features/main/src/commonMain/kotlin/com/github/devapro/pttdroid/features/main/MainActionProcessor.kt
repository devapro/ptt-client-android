package com.github.devapro.pttdroid.features.main

import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.ActionProcessor
import com.github.devapro.pttdroid.mvi.Reducer

/**
 * Main's own action processor — the standard one-set [ActionProcessor] shell over this slice's
 * [MainState]/[MainAction]/[UiEvent].
 */
class MainActionProcessor(
    reducers: Set<Reducer<out MainAction, MainState, MainAction, UiEvent>>,
) : ActionProcessor<MainState, MainAction, UiEvent>(reducers)
