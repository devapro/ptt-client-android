package com.github.devapro.pttdroid.features.settings

import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.ActionProcessor
import com.github.devapro.pttdroid.mvi.Reducer

/** The standard one-set [ActionProcessor] shell — settings' actions, state and events. */
class SettingsActionProcessor(
    reducers: Set<Reducer<out SettingsAction, SettingsState, SettingsAction, UiEvent>>,
) : ActionProcessor<SettingsState, SettingsAction, UiEvent>(reducers)
