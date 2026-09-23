package com.github.devapro.pttdroid.features.settings.reducer

import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsFormState
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.first

/**
 * Seeds [SettingsState.form] from what is on disk, once, on entry.
 *
 * Seeding here rather than in the view is what stops an unrelated settings write (a
 * playback-volume save, an audio-output change from the main screen) from wiping in-progress
 * edits while Settings is open — the defect the old `remember(settings)` key in `SettingsScreen`
 * caused, since that key re-ran on every emission from the repository flow, not just the first.
 * Navigation is the Voyager `Navigator`'s stack, not this reducer's — it only seeds, and the
 * screen root dispatches this `Open` once per push.
 */
class OpenSettingsReducer(
    private val settingsRepository: SettingsRepository,
) : Reducer<SettingsAction.Open, SettingsState, SettingsAction, UiEvent> {

    override val actionClass: KClass<SettingsAction.Open> = SettingsAction.Open::class

    override suspend fun reduce(
        action: SettingsAction.Open,
        state: SettingsState,
    ): Reducer.Result<SettingsState, SettingsAction, UiEvent?> = Reducer.Result(
        state.copy(form = SettingsFormState.from(settingsRepository.settings.first())),
    )
}
