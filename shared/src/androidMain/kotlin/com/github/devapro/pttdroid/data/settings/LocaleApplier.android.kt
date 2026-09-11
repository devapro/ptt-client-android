package com.github.devapro.pttdroid.data.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The phone's own locale, captured once at class-init time — before anything in this app has had
 * a chance to call [Locale.setDefault].
 *
 * `Locale.setDefault` is process-global and sticky: once [applyLocale] forces, say, Russian, the
 * JVM-wide default stays Russian until the process dies, even if the user later switches
 * [LanguageMode] back to [LanguageMode.SYSTEM]. Simply returning [base] unchanged for `SYSTEM`
 * would leave the previously forced language in force. Resolving `SYSTEM` back to this captured
 * value is what makes "back to System" actually restore the device's own locale rather than
 * whichever language happened to be forced last.
 *
 * Lint's `ConstantLocale` objects to exactly this — a device locale changed while the process is
 * alive leaves this value stale. That is the lesser of the two failures here: `PTTdroidApplication`
 * touches this class in `onCreate()`, so the capture happens before any of our own
 * `Locale.setDefault` calls, and a device locale change restarts the process for a stale value to
 * survive at all. Not capturing it makes "back to System" broken every time, not rarely.
 */
@Suppress("ConstantLocale")
private val systemLocale: Locale = Locale.getDefault()

/**
 * Returns a [Context] configured for [mode], for [Context.attachBaseContext] /
 * [android.app.Application.onCreate] to apply.
 *
 * Also calls [Locale.setDefault]: `stringResource()` and any other Compose-resources lookup reads
 * the live platform locale, not just this context's configuration, and surfaces without their own
 * `Context` (the Glance widget, the notification, the overlay bubble) need the process-wide
 * default to already be right.
 *
 * Lint's `AppBundleLocaleChanges` wants Play Core's language-split downloads alongside a dynamic
 * locale change. This app ships as a plain APK through F-Droid — every locale under
 * `composeResources/` is already in it, and Play Core is a proprietary dependency the catalogue
 * would not accept anyway (see `docs/fdroid.md`).
 */
@Suppress("AppBundleLocaleChanges")
fun applyLocale(base: Context, mode: LanguageMode): Context {
    val locale = mode.tag?.let(Locale::forLanguageTag) ?: systemLocale
    Locale.setDefault(locale)

    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return base.createConfigurationContext(config)
}
