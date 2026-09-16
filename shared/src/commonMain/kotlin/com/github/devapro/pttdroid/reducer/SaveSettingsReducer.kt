package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.mvi.Reducer
import com.github.devapro.pttdroid.shared.resources.*
import kotlin.reflect.KClass

/**
 * Persists [ScreenState.settingsForm], then hands straight on to [MainAction.Reconnect].
 *
 * The Activity used to do this itself — six suspending repository calls and two dispatches from
 * inside a Compose callback. That put persistence outside the MVI loop, and each of the six
 * writes emitted separately, so the controller could pick up a half-applied address mid-save.
 * One write, one emission, one reconnect.
 *
 * Reads the form out of [state] rather than a payload on the action: the view no longer builds
 * the domain object, so there is nothing left for [MainAction.SaveSettings] to carry. The
 * `hasError` guard is a second line of defence, not the only one — the Save button is already
 * disabled on error — because a reducer must not depend on the view having got that right.
 */
class SaveSettingsReducer(
    private val settingsRepository: SettingsRepository,
) : Reducer<MainAction.SaveSettings, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.SaveSettings> = MainAction.SaveSettings::class

    override suspend fun reduce(
        action: MainAction.SaveSettings,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> {
        val form = state.settingsForm ?: return Reducer.Result(state)
        if (form.hasError) return Reducer.Result(state)
        settingsRepository.save(form.toSettings())
        return Reducer.Result(
            state.copy(screen = ScreenState.Screen.Main, settingsForm = null),
            MainAction.Reconnect,
            MainEvent.ShowMessage(Res.string.settings_saved),
        )
    }
}
