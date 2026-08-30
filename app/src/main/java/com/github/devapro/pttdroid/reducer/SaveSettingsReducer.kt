package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.R
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass

/**
 * Persists the settings form, then hands straight on to [MainAction.Reconnect].
 *
 * The Activity used to do this itself — six suspending repository calls and two dispatches from
 * inside a Compose callback. That put persistence outside the MVI loop, and each of the six
 * writes emitted separately, so the controller could pick up a half-applied address mid-save.
 * One write, one emission, one reconnect.
 */
class SaveSettingsReducer(
    private val settingsRepository: SettingsRepository,
) : Reducer<MainAction.SaveSettings, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.SaveSettings> = MainAction.SaveSettings::class

    override suspend fun reduce(
        action: MainAction.SaveSettings,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        settingsRepository.save(action.settings)
        return Reducer.Result(
            state.copy(screen = ScreenState.Screen.Main),
            MainAction.Reconnect,
            MainEvent.ShowMessage(R.string.settings_saved),
        )
    }
}
