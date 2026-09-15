package com.github.devapro.pttdroid.data.settings

import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves `createAndroidSettingsDataStore()` (Phase 3's replacement for the
 * `Context.preferencesDataStore(name = "ptt_settings")` delegate, renamed in Phase 5 when its
 * `Context` started coming from Koin instead of the `AndroidSettingsContext` static holder) still
 * lands on the exact file the old delegate used —
 * `<filesDir>/datastore/ptt_settings.preferences_pb` — so upgrading an existing install does not
 * silently reset its settings.
 *
 * This has to run on a device/emulator: it reads a real `Context.filesDir`, which a JVM unit test
 * has no equivalent for.
 *
 * It does not instantiate the old `preferencesDataStore` delegate directly: DataStore refuses to
 * have two live instances open on the same file in one process
 * (`IllegalStateException: There are multiple DataStores active for this file`), and the delegate
 * has no exposed way to close its instance early. Instead it calls
 * `androidx.datastore.dataStoreFile(name)` — the exact, public, single-source-of-truth helper the
 * delegate itself calls internally to build that path (decompiled from the pinned
 * `datastore-android:1.2.1` sources: `preferencesDataStoreFile(context, name)` is
 * `context.dataStoreFile("$name.preferences_pb")`, and `dataStoreFile` is
 * `File(applicationContext.filesDir, "datastore/$fileName")`). So this test would fail if a
 * future AndroidX version changed that formula, not just if this repo's own code changed it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsDataStoreMigrationTest {

    private fun storeAt(file: File, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { file },
    )

    private fun restoredChannel(storedChannel: Int?): Int {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "channel-restore-${System.nanoTime()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        return try {
            val store = storeAt(file, scope)
            runBlocking {
                storedChannel?.let { channel ->
                    store.edit { prefs -> prefs[intPreferencesKey("channel")] = channel }
                }
                SettingsRepository(store).settings.first().channel
            }
        } finally {
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun createAndroidSettingsDataStore_writesToTheLegacyDelegatesFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // The same file androidx.datastore.preferences.preferencesDataStore(name = "ptt_settings")
        // resolves to — see the class kdoc for how that was verified.
        val expectedFile = context.dataStoreFile("ptt_settings.preferences_pb")
        assertEquals(
            "the legacy delegate wrote to <filesDir>/datastore/ptt_settings.preferences_pb",
            "datastore/ptt_settings.preferences_pb",
            expectedFile.relativeTo(context.filesDir).path,
        )
        expectedFile.delete()

        val store = createAndroidSettingsDataStore(context)
        val probeKey = stringPreferencesKey("phase3_migration_probe")
        runBlocking { store.edit { prefs -> prefs[probeKey] = "phase3" } }

        assertTrue(
            "createAndroidSettingsDataStore() should have written ${expectedFile.absolutePath}",
            expectedFile.exists(),
        )
        val readBack = runBlocking { store.data.first()[probeKey] }
        assertEquals("phase3", readBack)
    }

    @Test
    fun settings_restoresStoredChannel_andDefaultsOrClampsInvalidValues() {
        assertEquals(AppSettings.DEFAULT_CHANNEL, restoredChannel(storedChannel = null))
        assertEquals(7, restoredChannel(storedChannel = 7))
        assertEquals(AppSettings.CHANNEL_RANGE.first, restoredChannel(storedChannel = 0))
        assertEquals(AppSettings.CHANNEL_RANGE.last, restoredChannel(storedChannel = 100))
    }

    @Test
    fun broadcastStartBip_defaultsEnabledWhenAbsent_andRestoresSavedChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "broadcast-start-bip-${System.nanoTime()}.preferences_pb")
        val initialScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            val repository = SettingsRepository(storeAt(file, initialScope))

            runBlocking {
                assertTrue(repository.settings.first().broadcastStartBipEnabled)
                repository.save(AppSettings(broadcastStartBipEnabled = false))
            }
        } finally {
            initialScope.cancel()
        }

        val restoredScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = SettingsRepository(storeAt(file, restoredScope))

            runBlocking {
                assertEquals(false, repository.settings.first().broadcastStartBipEnabled)
            }
        } finally {
            restoredScope.cancel()
            file.delete()
        }
    }
}
