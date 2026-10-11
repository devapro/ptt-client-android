package com.github.devapro.pttdroid.uishell

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The entry point `iosApp/iosApp/ContentView.swift`'s `UIViewControllerRepresentable` calls as
 * `MainViewControllerKt.MainViewController()` (Kotlin/Native's Objective-C header names a
 * top-level function in `MainViewController.kt` as `MainViewControllerKt.MainViewController` —
 * the class name derives from the *file* name, which is why moving this from `:shared` to
 * `:ui-shell` in Phase 2 did not need a Swift change; only the framework's producing module did,
 * see `ui-shell/build.gradle.kts` and `iosApp/iosApp.xcodeproj`).
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
