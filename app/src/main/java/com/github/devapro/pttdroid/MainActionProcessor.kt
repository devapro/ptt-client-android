package com.github.devapro.pttdroid

import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.ActionProcessor
import com.github.devapro.pttdroid.mvi.Reducer

class MainActionProcessor(
    reducers: Set<Reducer<MainAction, ScreenState, MainAction, MainEvent>>
) : ActionProcessor<ScreenState, MainAction, MainEvent>(reducers)