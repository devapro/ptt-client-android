package com.github.devapro.pttdroid.data.settings

import java.util.Locale

/**
 * Android's live *display* language: swaps the process default locale, which is what
 * `stringResource()` reads its locale from on this platform (see `LocaleApplier.android.kt`'s
 * KDoc on `Locale.setDefault`).
 *
 * The heavier `Context.attachBaseContext` + `Activity.recreate()` path stays the mechanism that
 * applies a **persisted** language change (real `res/` configuration: layout direction, plurals,
 * the works). This call is for the lighter, reversible case: the Settings screen applies the
 * language picked in the still-unsaved form so its labels react immediately (R9), and restores
 * the persisted language through this same function when the form goes away. Nothing here
 * persists anything; a process that never opens that screen never calls this — previously the
 * `actual` was a no-op existing only because `:ui-shell`'s `App.kt` is commonMain.
 */
actual fun applyLanguagePreference(mode: LanguageMode) {
    Locale.setDefault(displayLocaleFor(mode))
}
