package com.github.devapro.pttdroid.features.api.main

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import org.koin.compose.koinInject

/**
 * The Main destination, and the app's `Navigator` root.
 *
 * A `data object`, not a class: Main carries no navigation arguments — everything it renders
 * arrives from its own view model and from `:shared`'s repository/badge singles. Arguments would
 * become constructor values here if a screen ever gained any.
 *
 * [Content] is the whole of this module's routing job, and it is deliberately one line: resolve the
 * `MainScreenFactory` binding (implemented and bound by `:features:main`) and let it compose.
 * That is the reference project's convention — a Voyager `Screen` is a *key plus a root composable*,
 * and the feature owns its own wiring — and it is where the host-threaded arguments went: the old
 * interim host router used to collect the settings, badge, state and nav callbacks and pass nine of
 * them down. Each screen root now resolves what it needs itself (see `MainScreenFactoryImpl`), so
 * no host has to know what any screen takes, and pushing a screen is
 * `navigator.push(SettingsScreen)` rather than a callback handed across a module boundary.
 */
data object MainScreen : Screen {
    @Composable
    override fun Content() {
        koinInject<MainScreenFactory>().CreateMainScreen()
    }
}
