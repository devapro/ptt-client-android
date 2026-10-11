package com.github.devapro.pttdroid.features.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.github.devapro.pttdroid.features.api.history.HistoryAction
import com.github.devapro.pttdroid.features.api.history.HistoryScreenFactory
import com.github.devapro.pttdroid.ui.LocalPttUiEffects
import com.github.devapro.pttdroid.ui.rememberPttUiEvents
import org.koin.compose.koinInject

/**
 * The History screen's root — the three things the interim host router's History branch used to
 * do, now done by the screen that owns them.
 *
 * **Seeding is once per entry:** [LaunchedEffect] keyed on the view model runs on each push and
 * dispatches `HistoryAction.Open`, whose reducer stamps the unread badge's read receipt
 * (`markAllSeen` is monotonic, so re-entering immediately just re-stamps it).
 *
 * **`HistoryAction.Close` pops, and pops only.** It still goes *through* the view model first (the
 * identity reducer is deliberate — `CloseHistoryReducer` must keep not stopping playback, which
 * `HistoryReducersTest` pins), then the root leaves the stack. Leaving while a record replays is
 * intended: nothing on this path touches the player, and the session lives on `PttController`,
 * which is process-scoped and outlives the screen.
 *
 * [rememberPttUiEvents] still collects this view model's channel even though
 * `HistoryViewModel` emits nothing today — a reducer that starts emitting would otherwise be able
 * to drop its event silently (known-issues #22).
 */
class HistoryScreenFactoryImpl : HistoryScreenFactory {
    @Composable
    override fun CreateHistoryScreen() {
        val viewModel = koinInject<HistoryViewModel>()
        val effects = LocalPttUiEffects.current
        val navigator = LocalNavigator.currentOrThrow

        val state by viewModel.state.collectAsState()

        LaunchedEffect(viewModel) { viewModel.onAction(HistoryAction.Open) }
        rememberPttUiEvents(viewModel.event, effects)

        HistoryScreen(
            state = state,
            onAction = { action ->
                viewModel.onAction(action)
                if (action is HistoryAction.Close) navigator.pop()
            },
        )
    }
}
