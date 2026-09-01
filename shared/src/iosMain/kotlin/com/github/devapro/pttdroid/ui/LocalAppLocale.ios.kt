package com.github.devapro.pttdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import platform.Foundation.NSUserDefaults

/**
 * iOS: Compose Multiplatform resolves resources against `NSLocale.preferredLanguages`, which
 * `NSUserDefaults` `AppleLanguages` overrides. `null` removes the override so the device locale
 * takes over again.
 *
 * `default` is a constant rather than read from `NSLocale.preferredLanguages()`: that class method
 * is not exposed in the cross-compile Foundation stub used on non-Mac hosts, and the value only
 * keys the composition for the SYSTEM case — the actual locale restoration is the
 * `removeObjectForKey` side effect above, not this string, so a placeholder is correct where the
 * real call is unavailable.
 */
actual object LocalAppLocale {
    private const val LANG_KEY = "AppleLanguages"
    private const val default = "en"
    private val ambient = staticCompositionLocalOf { default }

    actual val current: String
        @Composable get() = ambient.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val new = value ?: default
        val defaults = NSUserDefaults.standardUserDefaults
        if (value == null) {
            defaults.removeObjectForKey(LANG_KEY)
        } else {
            defaults.setObject(arrayListOf(new), LANG_KEY)
        }
        return ambient.provides(new)
    }
}
