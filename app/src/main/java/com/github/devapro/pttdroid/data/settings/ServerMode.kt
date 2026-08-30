package com.github.devapro.pttdroid.data.settings

/**
 * Whether the relay address comes from the app or from the user.
 *
 * Two thirds of the settings form used to be a host field and a port field that most people never
 * had a reason to touch, sitting above the things they did. [DEFAULT] folds both away behind a
 * single choice; [CUSTOM] brings back one field, for the people pointing at their own relay.
 */
enum class ServerMode {
    DEFAULT,
    CUSTOM,
    ;

    val isCustom: Boolean get() = this == CUSTOM

    companion object {
        /**
         * The mode to apply to what is on disk.
         *
         * An install that predates this setting has no stored mode but may well have a configured
         * relay. Reading that as [DEFAULT] would quietly move it back to the built-in address, so
         * a stored address that differs from the default is taken as the [CUSTOM] choice it was
         * made under. An unrecognised stored value is treated the same way as an absent one —
         * settings outlive enum constants.
         */
        fun restore(stored: String?, host: String?, port: Int?): ServerMode =
            entries.firstOrNull { it.name == stored } ?: when {
                host == null && port == null -> DEFAULT
                host == AppSettings.DEFAULT_HOST &&
                    (port ?: AppSettings.DEFAULT_PORT) == AppSettings.DEFAULT_PORT -> DEFAULT

                else -> CUSTOM
            }
    }
}
