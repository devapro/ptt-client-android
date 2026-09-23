package com.github.devapro.pttdroid.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsScreenFactory
import com.github.devapro.pttdroid.ui.LocalPttUiEffects
import com.github.devapro.pttdroid.ui.rememberPttUiEvents
import org.koin.compose.koinInject

/**
 * The Settings screen's root: seeding, the pop, and event collection all live here now, which is
 * the whole of what the interim host router used to do for this branch.
 *
 * **Seeding is once per entry.** [LaunchedEffect] keyed on the view model runs when this screen
 * enters the composition and not again while it stays composed — a data-object destination is
 * re-entered (and so re-seeded) on every push, which is the intent: `SettingsAction.Open` reads a
 * fresh form off disk, and it is dispatched *after* the observer below starts collecting so the
 * seed itself cannot be missed.
 *
 * **The exit rule is [SettingsExitObserver]'s**, not this file's: the observer collects the view
 * model's state and pops on the seeded → null transition (what a save's success or refusal is
 * decided by), and the arrow's `SettingsAction.Close` pops through the same observer, guarded so
 * the two paths produce exactly one pop. Read that KDoc for why a `form == null` level would
 * bounce out before the seed lands and why popping on `Close` alone cannot cover the save.
 *
 * One consequence of system back existing (which it did not under the interim host router): leaving
 * Settings with the back gesture does **not** dispatch `Close`, so the form stays seeded. Harmless
 * by construction — the next entry's `Open` overwrites it from disk, which is the guarantee that
 * matters — but it does mean the stale form is what renders for the moment before that read lands.
 */
class SettingsScreenFactoryImpl : SettingsScreenFactory {
    @Composable
    override fun CreateSettingsScreen() {
        val viewModel = koinInject<SettingsViewModel>()
        val effects = LocalPttUiEffects.current
        val navigator = LocalNavigator.currentOrThrow

        val state by viewModel.state.collectAsState()

        // Keyed on the view model, but the `remember` table belongs to *this* composition: pushing
        // Settings again builds a new root, so a second visit gets a fresh `popped` flag and its
        // arrow is not dead. The lambda, not `navigator::pop`, because Voyager's pop() returns the
        // screen it removed.
        val exit = remember(viewModel) { SettingsExitObserver { navigator.pop() } }

        // Observer first, seed second: see the class KDoc for why the order is load-bearing.
        LaunchedEffect(viewModel) { exit.observe(viewModel.state) }
        LaunchedEffect(viewModel) { viewModel.onAction(SettingsAction.Open) }
        rememberPttUiEvents(viewModel.event, effects)

        SettingsScreen(
            state = state,
            onAction = { action ->
                viewModel.onAction(action)
                if (action is SettingsAction.Close) exit.onClose()
            },
        )
    }
}
