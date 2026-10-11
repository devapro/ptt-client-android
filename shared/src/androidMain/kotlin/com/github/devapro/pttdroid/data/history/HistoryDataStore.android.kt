package com.github.devapro.pttdroid.data.history

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * The history index store, kept in its own file rather than piggybacking on the settings one:
 * a corrupt or oversized history index must never take the relay address down with it.
 *
 * Bound into the graph by `di/SharedDiAndroid.kt` under the `HISTORY_DATA_STORE` qualifier.
 * Location and construction mirror `createAndroidSettingsDataStore` deliberately.
 */
internal fun createAndroidHistoryDataStore(context: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        produceFile = {
            File(
                context.applicationContext.filesDir,
                "datastore/history_index.preferences_pb",
            )
        },
    )
