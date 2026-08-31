package com.github.devapro.pttdroid.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File

/** The name the old `preferencesDataStore(name = "ptt_settings")` delegate used. */
private const val DATA_STORE_NAME = "ptt_settings"

/**
 * Reproduces `Context.dataStoreFile("$DATA_STORE_NAME.preferences_pb")` from
 * `androidx.datastore:datastore-android` — the helper the old `preferencesDataStore` delegate
 * called under the hood — exactly: `File(context.applicationContext.filesDir,
 * "datastore/$fileName")`. Verified by decompiling `DataStoreFile.android.kt` out of the pinned
 * `datastore-android:1.2.1` sources jar; see the Phase 3 report for the full trail. Getting this
 * path wrong silently drops every existing install's settings on upgrade.
 */
private fun settingsFile(context: Context): File =
    File(context.applicationContext.filesDir, "datastore/$DATA_STORE_NAME.preferences_pb")

/**
 * Builds the settings `DataStore<Preferences>` for Android.
 *
 * [context] comes from Koin's `androidContext()` (see `di/SharedDiAndroid.kt`) rather than a
 * process-wide static holder — the Phase 3 stopgap (`AndroidSettingsContext`) was deleted in
 * Phase 5 once the DI graph was split enough to carry a real `Context` through to this call.
 */
internal fun createAndroidSettingsDataStore(context: Context): DataStore<Preferences> {
    val appContext = context.applicationContext
    return PreferenceDataStoreFactory.create(produceFile = { settingsFile(appContext) })
}
