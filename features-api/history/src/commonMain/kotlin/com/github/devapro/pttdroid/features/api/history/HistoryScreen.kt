package com.github.devapro.pttdroid.features.api.history

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import org.koin.compose.koinInject

/**
 * The History destination.
 *
 * A `data object`: the record list is mirrored into `HistoryState.records` by `HistoryViewModel`,
 * so the route carries nothing. [Content] resolves this slice's factory binding;
 * `HistoryScreenFactoryImpl` is the root that marks the list seen once per entry, pops on
 * `HistoryAction.Close`, and collects the view model's events.
 */
data object HistoryScreen : Screen {
    @Composable
    override fun Content() {
        koinInject<HistoryScreenFactory>().CreateHistoryScreen()
    }
}
