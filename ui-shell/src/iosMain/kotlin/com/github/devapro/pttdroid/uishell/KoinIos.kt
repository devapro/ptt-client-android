package com.github.devapro.pttdroid.uishell

import com.github.devapro.pttdroid.di.sharedIosModule
import com.github.devapro.pttdroid.di.sharedModule
import com.github.devapro.pttdroid.features.history.registerHistoryScreenDi
import com.github.devapro.pttdroid.features.main.registerMainScreenDi
import com.github.devapro.pttdroid.features.settings.registerSettingsScreenDi
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * iOS's Koin entry point, called once from `iosApp/iosApp/iOSApp.swift`'s `init()` as
 * `KoinIosKt.initKoinIos()` (Kotlin/Native's Objective-C header names a top-level function in
 * `KoinIos.kt` as `KoinIosKt.initKoinIos` — the class name derives from the *file* name, so
 * moving this from `:shared`'s `di/` here in Phase 2 needed no Swift change).
 *
 * Mirrors `PTTdroidApplication.onCreate()` (Android) and `:desktopApp`'s `main()` (desktop).
 * Loads [sharedModule] (platform-independent) plus [sharedIosModule] (`:shared`'s
 * `di/SharedDiIos.kt`) — not `sharedAndroidModule`/`sharedDesktopModule`, and not `jvmDi()`,
 * which iOS cannot see — plus, since Phase 2, the three feature screen modules' bindings: the
 * `ScreenFactory`s and view-model `single`s the Voyager screen roots resolve live in
 * `:features:*`, and this file sits in `:ui-shell`, the one module allowed to depend on them
 * (see docs/architecture.md's module graph).
 */
fun initKoinIos() {
    startKoin {
        modules(
            sharedModule,
            sharedIosModule,
            module {
                registerMainScreenDi()
                registerSettingsScreenDi()
                registerHistoryScreenDi()
            },
        )
    }
}
