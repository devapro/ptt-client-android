package com.github.devapro.pttdroid.reducer

import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.model.SettingsFormState
import com.github.devapro.pttdroid.mvi.Reducer
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.first

/**
 * Seeds [ScreenState.settingsForm] from what is on disk, once, on entry.
 *
 * Seeding here rather than in the view is what stops an unrelated settings write (a
 * playback-volume save, an audio-output change from the main screen) from wiping in-progress
 * edits while Settings is open — the defect the old `remember(settings)` key in `SettingsScreen`
 * caused, since that key re-ran on every emission from the repository flow, not just the first.
 */
class OpenSettingsReducer(
    private val settingsRepository: SettingsRepository,
) : Reducer<MainAction.OpenSettings, ScreenState, MainAction, MainEvent> {

    override val actionClass: KClass<MainAction.OpenSettings> = MainAction.OpenSettings::class

    override suspend fun reduce(
        action: MainAction.OpenSettings,
        state: ScreenState,
    ): Reducer.Result<ScreenState, MainAction, MainEvent?> = Reducer.Result(
        state.copy(
            screen = ScreenState.Screen.Settings,
            settingsForm = SettingsFormState.from(settingsRepository.settings.first()),
        ),
    )
}
