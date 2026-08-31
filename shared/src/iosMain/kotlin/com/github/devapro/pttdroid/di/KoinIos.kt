package com.github.devapro.pttdroid.di

import org.koin.core.context.startKoin

/**
 * iOS's Koin entry point, called once from `iosApp/iosApp/iOSApp.swift`'s `init()` as
 * `KoinIosKt.initKoinIos()` (Kotlin/Native's Objective-C header names a top-level function in
 * `KoinIos.kt` as `KoinIosKt.initKoinIos`).
 *
 * Mirrors `PTTdroidApplication.onCreate()` (Android) and `:desktopApp`'s `main()` (desktop): both
 * call `startKoin { modules(...) }` themselves, since neither an iOS `App` nor a Swift `App`
 * struct is a Koin-aware entry point the way `PTTdroidApplication` is for Android. Loads
 * [sharedModule] (platform-independent) plus [sharedIosModule] (`di/SharedDiIos.kt`) — not
 * `sharedAndroidModule`/`sharedDesktopModule`, and not `jvmDi()`, which iOS cannot see.
 */
fun initKoinIos() {
    startKoin {
        modules(sharedModule, sharedIosModule)
    }
}
