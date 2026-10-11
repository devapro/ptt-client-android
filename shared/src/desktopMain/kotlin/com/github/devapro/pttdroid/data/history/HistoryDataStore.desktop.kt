package com.github.devapro.pttdroid.data.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import okio.Path.Companion.toPath
import java.io.File

/** `$XDG_CONFIG_HOME/ptt-client/history_index.preferences_pb`, or `~/.config/...` — see the
 *  same choice documented in `SettingsDataStore.desktop.kt`. */
internal fun createDesktopHistoryDataStore(): DataStore<Preferences> {
    val file = File(desktopHistoryDirectory(), "history_index.preferences_pb")
    return PreferenceDataStoreFactory.createWithPath(produceFile = { file.absolutePath.toPath() })
}

internal fun desktopHistoryDirectory(): File {
    val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        ?: (System.getProperty("user.home") + "/.config")
    return File(configHome, "ptt-client/history").also { it.mkdirs() }
}
