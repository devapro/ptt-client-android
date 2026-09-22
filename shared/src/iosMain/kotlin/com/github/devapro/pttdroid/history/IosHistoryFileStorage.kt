package com.github.devapro.pttdroid.history

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * iOS's `<Documents>/history` — the analogue of Android's `filesDir/history`. The directory is
 * resolved and created here so both the WAV files and the history index DataStore (which lives
 * in the same directory) find it there.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun iosHistoryDirectoryPath(): String {
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
    val historyPath = "$documentsPath/history"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path = historyPath,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return historyPath
}

/**
 * [HistoryFileStorage] over okio's real filesystem — the JVM targets have `java.io`, iOS has
 * this; okio comes in transitively through DataStore, the same way `okio.Path` already does in
 * `data/settings/SettingsDataStore.ios.kt`.
 *
 * In practice iOS never captures — it binds a constant-foreground `ForegroundProvider` — but the
 * graph still needs a real implementation to satisfy `PttController`'s dependencies.
 */
@OptIn(ExperimentalForeignApi::class)
class IosHistoryFileStorage(directoryPath: String) : HistoryFileStorage {
    private val fileSystem = FileSystem.SYSTEM
    private val directory: Path = directoryPath.toPath()

    override suspend fun write(name: String, bytes: ByteArray): Boolean = runCatching {
        fileSystem.createDirectories(directory)
        fileSystem.write(directory / name) { write(bytes) }
    }.isSuccess

    override suspend fun read(name: String): ByteArray? = runCatching {
        fileSystem.read(directory / name) { readByteArray() }
    }.getOrNull()

    override suspend fun delete(name: String) {
        runCatching { fileSystem.delete(directory / name, mustExist = false) }
    }

    override suspend fun list(): List<String> = runCatching {
        fileSystem.list(directory).map { it.name }
    }.getOrDefault(emptyList())
}

/** The history index store, in the same directory — mirrors `createIosSettingsDataStore`. */
@OptIn(ExperimentalForeignApi::class)
internal fun createIosHistoryDataStore(): DataStore<Preferences> {
    val file = "${iosHistoryDirectoryPath()}/history_index.preferences_pb"
    return PreferenceDataStoreFactory.createWithPath(produceFile = { file.toPath() })
}
