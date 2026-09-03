package com.github.devapro.pttdroid.data.settings

/**
 * Which language to render the UI in, independently of the system locale.
 *
 * [SYSTEM] is the default and the right answer for most people, but this app is a walkie-talkie:
 * it gets handed to someone who does not read the language the phone owner set up — a guest, a
 * kid, a coworker borrowing a channel for the afternoon. Forcing a language per-app lets that
 * person read "Talk"/"Listening" without touching the device's own locale. The app ships only
 * English, Russian and Serbian, so a system locale that matches none of them already falls back
 * to English; [SYSTEM] simply defers to that instead of overriding it.
 */
enum class LanguageMode(val tag: String?) {
    SYSTEM(tag = null),
    ENGLISH(tag = "en"),
    RUSSIAN(tag = "ru"),
    SERBIAN(tag = "sr"),
    ;

    companion object {
        /** Tolerates an unknown or absent stored value — settings outlive enum constants. */
        fun fromStorage(value: String?): LanguageMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
