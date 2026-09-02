package com.github.devapro.pttdroid.data.settings

/**
 * Which locale the UI should use, independently of the system setting.
 *
 * [SYSTEM] is the default and follows the device locale. Explicit [ENGLISH], [RUSSIAN] and
 * [SERBIAN] pin the app to that language even when the rest of the device is something else.
 */
enum class LanguageMode {
    SYSTEM,
    ENGLISH,
    RUSSIAN,
    SERBIAN,
    ;

    /** BCP 47 language tag, or null when the system locale should apply. */
    val tag: String?
        get() = when (this) {
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
