package com.github.devapro.pttdroid.data.settings

/**
 * Which palette to paint, independently of the system setting.
 *
 * [SYSTEM] is the default and the right answer for most people, but this app is used in the dark
 * with a phone that is otherwise kept in light mode, and vice versa — a walkie-talkie gets pulled
 * out at night on a device whose owner has never touched the system theme. Forcing it per-app is
 * cheap and occasionally the difference between readable and not.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Resolved against the system's current setting. Kept pure so it can be unit-tested. */
    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Tolerates an unknown or absent stored value — settings outlive enum constants. */
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
