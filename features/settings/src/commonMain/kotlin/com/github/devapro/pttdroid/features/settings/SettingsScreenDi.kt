package com.github.devapro.pttdroid.features.settings

import com.github.devapro.pttdroid.features.api.settings.SettingsScreenFactory
import com.github.devapro.pttdroid.features.settings.reducer.CloseSettingsReducer
import com.github.devapro.pttdroid.features.settings.reducer.EditSettingsReducer
import com.github.devapro.pttdroid.features.settings.reducer.OpenSettingsReducer
import com.github.devapro.pttdroid.features.settings.reducer.ReconnectReducer
import com.github.devapro.pttdroid.features.settings.reducer.RequestOverlayPermissionReducer
import com.github.devapro.pttdroid.features.settings.reducer.SaveSettingsReducer
import org.koin.core.module.Module
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind

/**
 * Koin registration for `:features:settings` — see `registerMainScreenDi`'s KDoc in
 * `:features:main` for the pattern and, more importantly, for why `SettingsViewModel` is a `single`
 * rather than a `viewModelOf`: with per-screen roots there is no host threading instances around,
 * so identity has to come from the graph. It matters here more than anywhere — the in-progress
 * settings form *is* this view model's state, so a second instance would mean a second, blank form
 * and a save whose `ShowMessage` reaches nobody (known-issues #22).
 */
fun Module.registerSettingsScreenDi() {
    factoryOf(::SettingsScreenFactoryImpl) bind SettingsScreenFactory::class
    factoryOf(::OpenSettingsReducer)
    factoryOf(::CloseSettingsReducer)
    factoryOf(::EditSettingsReducer)
    factoryOf(::SaveSettingsReducer)
    factoryOf(::RequestOverlayPermissionReducer)
    factoryOf(::ReconnectReducer)
    singleOf(::SettingsViewModel)

    factory {
        SettingsActionProcessor(
            reducers = setOf(
                get<OpenSettingsReducer>(),
                get<CloseSettingsReducer>(),
                get<EditSettingsReducer>(),
                get<SaveSettingsReducer>(),
                get<RequestOverlayPermissionReducer>(),
                get<ReconnectReducer>(),
            ),
        )
    }
}
