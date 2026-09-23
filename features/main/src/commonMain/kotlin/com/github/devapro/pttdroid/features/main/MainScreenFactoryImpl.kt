package com.github.devapro.pttdroid.features.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.features.api.history.HistoryScreen
import com.github.devapro.pttdroid.features.api.main.MainScreenFactory
import com.github.devapro.pttdroid.features.api.settings.SettingsScreen
import com.github.devapro.pttdroid.history.HistoryBadge
import com.github.devapro.pttdroid.ui.LocalPttUiEffects
import com.github.devapro.pttdroid.ui.rememberPttUiEvents
import org.koin.compose.koinInject

/**
 * The Main screen's root: everything the pre-Voyager host used to thread in is resolved here.
 *
 * [MainViewModel] comes from Koin (registered `single` by [registerMainScreenDi], which is what
 * makes one instance per process the rule rather than a coincidence of who happened to resolve it
 * first), the endpoint / `audioOutput` / `playbackVolume` readouts and the `historyUnread` badge
 * come from `:shared`'s `SettingsRepository` and `HistoryBadge` singles, and the two nav callbacks
 * push this slice's own destinations — the `:features-api:*` `Screen` markers, which is the only
 * thing `:features:main` is allowed to know about Settings and History (no `:features:x` →
 * `:features:y` edge exists, and none is needed to push a screen).
 *
 * The host's [com.github.devapro.pttdroid.ui.PttUiEffects] is where this screen's one-shot events
 * go: [rememberPttUiEvents] collects **this view model's** event channel, so
 * `RequestMicPermission` (from `InitConnectionReducer`/`StartSpeakReducer` when the microphone is
 * not granted yet) reaches the platform prompt. [MainScreen] itself stays a pure function of its
 * arguments — previews and `MainScreenTest` drive it directly, with no Koin or Navigator around.
 */
class MainScreenFactoryImpl : MainScreenFactory {
    @Composable
    override fun CreateMainScreen() {
        val viewModel = koinInject<MainViewModel>()
        val settingsRepository = koinInject<SettingsRepository>()
        val historyBadge = koinInject<HistoryBadge>()
        val effects = LocalPttUiEffects.current
        val navigator = LocalNavigator.currentOrThrow

        val state by viewModel.state.collectAsState()
        val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
        val historyUnread by historyBadge.unread.collectAsState()

        rememberPttUiEvents(viewModel.event, effects)

        MainScreen(
            state = state,
            endpoint = "${settings.serverHost}:${settings.serverPort}",
            snackbarHostState = effects.snackbarHostState,
            onAction = viewModel::onAction,
            historyUnread = historyUnread,
            onOpenSettings = { navigator.push(SettingsScreen) },
            onOpenHistory = { navigator.push(HistoryScreen) },
            audioOutput = settings.audioOutput,
            playbackVolume = settings.playbackVolume,
        )
    }
}
