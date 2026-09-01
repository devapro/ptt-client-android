package com.github.devapro.pttdroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * Desktop (JVM): Compose Multiplatform resolves resources against `Locale.getDefault()`, so the
 * override is `Locale.setDefault`. There is no per-window `Configuration` to thread the way
 * Android does, so the provided value is a composition local read by [current]; the
 * `Locale.setDefault` side effect is what actually moves the resource loader.
 */
actual object LocalAppLocale {
    private var default: Locale? = null
    private val ambient = staticCompositionLocalOf { Locale.getDefault().toLanguageTag() }

    actual val current: String
        @Composable get() = ambient.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (default == null) default = Locale.getDefault()
        val new = when (value) {
            null -> default!!
            else -> Locale.forLanguageTag(value)
        }
        Locale.setDefault(new)
        return ambient.provides(new.toLanguageTag())
    }
}
