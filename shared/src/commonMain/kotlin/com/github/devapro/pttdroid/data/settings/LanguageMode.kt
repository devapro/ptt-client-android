package com.github.devapro.pttdroid.data.settings

/**
 * Which language the UI is painted in, independently of the system setting.
 *
 * [SYSTEM] is the default and follows the device locale, falling back to English (the
 * `values/` default) when the device locale has no translation here. The other three force a
 * specific language regardless of the system setting — the same trade-off as [ThemeMode], for
 * the same reason: a walkie-talkie gets handed to someone who reads a different language than
 * the device owner.
 *
 * The override is applied through [com.github.devapro.pttdroid.ui.LocalAppLocale] at the
 * composition root, so it only affects the Compose UI. Surfaces outside the composition tree
 * (the Android notification and the Glance widget) keep reading the system locale — there is no
 * Compose Multiplatform API yet to set a process-wide resource locale (CMP-4197).
 */
enum class LanguageMode {
    SYSTEM,
    ENGLISH,
    RUSSIAN,
    SERBIAN,
    ;

    /**
     * The BCP-47 language tag this mode resolves to, or `null` for "follow the system". `null` is
     * what [com.github.devapro.pttdroid.ui.LocalAppLocale.provides] takes to mean "restore the
     * system locale" on every platform.
     */
    fun languageTag(): String? = when (this) {
        SYSTEM -> null
        ENGLISH -> "en"
        RUSSIAN -> "ru"
        SERBIAN -> "sr"
    }

    companion object {
        /** Tolerates an unknown or absent stored value — settings outlive enum constants. */
        fun fromStorage(value: String?): LanguageMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
