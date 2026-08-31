package com.github.devapro.pttdroid

import androidx.compose.ui.window.ComposeUIViewController
import com.github.devapro.pttdroid.ui.App
import platform.UIKit.UIViewController

/**
 * The entry point `iosApp/iosApp/ContentView.swift`'s `UIViewControllerRepresentable` calls as
 * `MainViewControllerKt.MainViewController()` (Kotlin/Native's Objective-C header names a
 * top-level function in `MainViewController.kt` as `MainViewControllerKt.MainViewController`).
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
