package com.github.devapro.pttdroid.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** Same filename shape as the Android/desktop stores — see the two other `SettingsDataStore.*`. */
private const val DATA_STORE_FILE_NAME = "settings.preferences_pb"

/**
 * `<Documents>/settings.preferences_pb`, the iOS analogue of Android's app-private `filesDir`
 * and desktop's `$XDG_CONFIG_HOME` — see `docs/architecture.md#settings-storage`.
 *
 * `NSDocumentDirectory` (not `NSCachesDirectory` or `NSLibraryDirectory`) because the OS may
 * purge caches under storage pressure and this is user configuration, not a cache; it is
 * excluded from iCloud/iTunes file-sharing backup concerns the same way `filesDir` is on
 * Android — a relay address and an access token are not something to hand back to a restore of
 * a different install.
 *
 * Bound into the graph by `di/SharedDiIos.kt`, mirroring `createAndroidSettingsDataStore`/
 * `createDesktopSettingsDataStore` — plain platform functions, not `expect`/`actual` (see the
 * KDoc on those two for why the split happened this way in Phase 5).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun createIosSettingsDataStore(): DataStore<Preferences> {
    val documentsUrl = requireNotNull(
        NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ),
    ) { "Could not resolve the app's Documents directory" }
    val documentsPath = requireNotNull(documentsUrl.path) { "Documents directory URL had no path" }
    val file = "$documentsPath/$DATA_STORE_FILE_NAME"
    return PreferenceDataStoreFactory.createWithPath(produceFile = { file.toPath() })
}
