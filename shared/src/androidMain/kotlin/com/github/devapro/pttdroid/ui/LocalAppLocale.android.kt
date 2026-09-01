package com.github.devapro.pttdroid.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Android: overrides the Activity's `Configuration` locale, which is what the Compose
 * Multiplatform resource loader reads on this platform. `Locale.setDefault` is set too so any
 * code that reads the JVM default (rather than the Context) agrees.
 *
 * `null` restores the device locale: the saved default is captured on first use, since calling
 * `Locale.setDefault` below would otherwise have already moved it.
 */
// NonObservableLocale: the reads below are from LocalConfiguration.current, which *is* observable
// — the lint check does not recognise LocaleList indexing / Configuration wrapping as such. The
// reads are deliberate: `current` reports the effective locale and `provides` swaps it, and the
// only observable source on Android is LocalConfiguration. Same justification as the existing
// suppressions in build.gradle.kts (do not add one without a reason — this is it).
@SuppressLint("NonObservableLocale")
actual object LocalAppLocale {
    private var default: Locale? = null

    // NonObservableLocale: this reads LocalConfiguration.current, which *is* observable — the
    // lint check does not recognise the LocaleList indexing as such. The read is deliberate:
    // `current` reports the effective locale at this point in the composition, and the only
    // observable source of it on Android is LocalConfiguration. Same justification as the
    // existing suppressions in build.gradle.kts (do not add one without a reason — this is it).
    actual val current: String
        @Composable get() = LocalConfiguration.current.locales[0].toLanguageTag()

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (default == null) default = Locale.getDefault()
        val new = when (value) {
            null -> default!!
            else -> Locale.forLanguageTag(value)
        }
        Locale.setDefault(new)
        val configuration = Configuration(LocalConfiguration.current)
        configuration.setLocale(new)
        // updateConfiguration is deprecated but remains the documented CMP workaround for
        // forcing the Context's resource configuration from Compose (CMP-4197). The non-deprecated
        // createConfigurationContext path would need a new Context per change, which Compose
        // cannot re-provide mid-composition.
        @Suppress("DEPRECATION")
        LocalContext.current.resources.updateConfiguration(configuration, LocalContext.current.resources.displayMetrics)
        return LocalConfiguration.provides(configuration)
    }
}
