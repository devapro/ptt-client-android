package com.github.devapro.pttdroid.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath

/**
 * `$XDG_CONFIG_HOME/ptt-client/settings.preferences_pb`, falling back to
 * `~/.config/ptt-client/...` when the variable is unset — the desktop-native equivalent of the
 * Android build's app-private `filesDir`. Deliberately not `java.io.tmpdir`, which is wiped
 * between logins/reboots on most desktops.
 *
 * Bound into the graph by `di/SharedDiDesktop.kt`; takes no `Context`-like parameter, since
 * desktop has nothing equivalent to hand it.
 */
internal fun createDesktopSettingsDataStore(): DataStore<Preferences> {
    val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        ?: (System.getProperty("user.home") + "/.config")
    val directory = java.io.File(configHome, "ptt-client")
    directory.mkdirs()
    val file = java.io.File(directory, "settings.preferences_pb")
    return PreferenceDataStoreFactory.createWithPath(produceFile = { file.absolutePath.toPath() })
}
