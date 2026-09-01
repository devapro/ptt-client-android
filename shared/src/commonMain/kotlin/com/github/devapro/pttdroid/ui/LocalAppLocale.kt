package com.github.devapro.pttdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue

/**
 * The locale the Compose Multiplatform resource loader resolves `stringResource` / `getString`
 * against, as a BCP-47 language tag (`"en"`, `"ru"`, `"sr"`, …) or `null` for "follow the system".
 *
 * Compose Multiplatform has no public API yet to set a process-wide resource locale (CMP-4197),
 * so this follows the official workaround: an `expect`/`actual` object whose [provides] swaps in a
 * platform-specific override — `Configuration.setLocale` on Android, `Locale.setDefault` on
 * desktop, `NSUserDefaults` `AppleLanguages` on iOS — and returns the `ProvidedValue` that
 * `CompositionLocalProvider` threads through the composition. See
 * `LocalAppLocale.android.kt` / `LocalAppLocale.desktop.kt` / `LocalAppLocale.ios.kt`.
 *
 * Scope: this only affects the Compose tree. The Android notification and the Glance widget read
 * `Res.string.*` from outside the composition, so they keep following the system locale.
 */
expect object LocalAppLocale {
    /** The effective language tag at this point in the composition. */
    val current: String

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}
