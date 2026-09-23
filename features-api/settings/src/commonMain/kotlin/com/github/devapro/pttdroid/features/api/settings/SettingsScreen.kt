package com.github.devapro.pttdroid.features.api.settings

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import org.koin.compose.koinInject

/**
 * The Settings destination.
 *
 * A `data object`: Settings carries no navigation arguments — the form it renders
 * (`SettingsFormState`) is seeded into `SettingsState` by `OpenSettingsReducer` from the repository
 * and lives in `SettingsViewModel`, not in the route (see docs/architecture.md "Settings form
 * state"). Pushing this object is therefore the whole of "open Settings", from anywhere, and
 * `:features:main` may push it because it is a contract type, not an implementation.
 *
 * [Content] resolves this slice's factory binding; `SettingsScreenFactoryImpl` is the root that
 * seeds the form once per entry, pops on the seeded → null edge, and collects the view model's
 * events.
 */
data object SettingsScreen : Screen {
    @Composable
    override fun Content() {
        koinInject<SettingsScreenFactory>().CreateSettingsScreen()
    }
}
