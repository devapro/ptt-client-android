package com.github.devapro.pttdroid.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The start-beep flag has to survive a save: the form writes it through [SettingsRepository.save],
 * and a forgotten key would silently fall back to on after every restart.
 */
class SettingsRepositoryTest {

    @Test
    fun startBeep_defaultsOnAndRoundTrips() {
        runBlocking {
            val file = File.createTempFile("ptt-settings", ".preferences_pb")
            file.delete()
            val store = PreferenceDataStoreFactory.create(produceFile = { file })
            val repo = SettingsRepository(store)

            assertTrue(
                "a missing key is a fresh install, which sends the tone",
                repo.settings.first().startBeepEnabled,
            )

            repo.save(AppSettings(startBeepEnabled = false))
            assertFalse(repo.settings.first().startBeepEnabled)

            repo.save(AppSettings(startBeepEnabled = true))
            assertTrue(repo.settings.first().startBeepEnabled)

            file.delete()
        }
    }
}
