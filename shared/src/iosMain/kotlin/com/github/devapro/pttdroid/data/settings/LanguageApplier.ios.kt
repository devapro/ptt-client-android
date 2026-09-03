package com.github.devapro.pttdroid.data.settings

import platform.Foundation.NSUserDefaults

/**
 * Best-effort, **runtime-unverified**: this machine is Linux, so this file has only ever been
 * frontend-compiled (`-PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64`), never run
 * on a simulator or device. iOS has no public API to force a `stringResource()` locale for the
 * running process the way `Locale.setDefault` does on the JVM; the closest lever is the
 * `AppleLanguages` `NSUserDefaults` key, which `NSLocale.preferredLanguages` (and so
 * `androidx.compose.ui.text.intl.Locale.current` on this target) reads from. Writing it is
 * expected to take effect on the **next launch**, not the current composition — there is no
 * recomposition-forcing move available here the way `Activity.recreate()` gives Android, so
 * `App.kt`'s `key(language) { ... }` throws away the current subtree but iOS's `stringResource()`
 * calls will keep resolving against the old preferred-languages list until the process restarts.
 */
actual fun applyLanguagePreference(mode: LanguageMode) {
    val defaults = NSUserDefaults.standardUserDefaults
    val tag = mode.tag
    if (tag == null) {
        defaults.removeObjectForKey("AppleLanguages")
    } else {
        defaults.setObject(listOf(tag), forKey = "AppleLanguages")
    }
}
