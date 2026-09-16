package com.github.devapro.pttdroid.reducer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.model.SettingsEdit
import com.github.devapro.pttdroid.model.SettingsFormState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The settings screen's five reducers, exercised against a fake `DataStore<Preferences>` rather
 * than a real one — [SettingsRepository] takes the interface, so no Android/desktop backing store
 * is needed to prove the MVI loop: seeding on open, applying an edit to the *current* state,
 * persisting (or refusing to) on save, and the overlay-permission escape hatch.
 */
class SettingsReducersTest {

    /** In-memory `DataStore<Preferences>`, matching what `SettingsRepository` actually reads. */
    private class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }

    // --- OpenSettings -------------------------------------------------------------------------

    @Test
    fun `OpenSettings seeds the form from the repository and opens the Settings screen`() = runTest {
        val repository = SettingsRepository(FakeDataStore())
        repository.save(AppSettings(displayName = "Ann", channel = 5))
        val reducer = OpenSettingsReducer(repository)

        val result = reducer.reduce(MainAction.OpenSettings, ScreenState())

        assertEquals(ScreenState.Screen.Settings, result.state.screen)
        val form = assertNotNull(result.state.settingsForm)
        assertEquals("Ann", form.displayName)
        assertEquals("5", form.channel)
    }

    @Test
    fun `OpenSettings seeds from whatever is on disk rather than the fresh install defaults`() = runTest {
        val repository = SettingsRepository(FakeDataStore())
        repository.save(AppSettings(accessToken = "s3cret"))
        val reducer = OpenSettingsReducer(repository)

        val result = reducer.reduce(MainAction.OpenSettings, ScreenState())

        assertEquals("s3cret", assertNotNull(result.state.settingsForm).accessToken)
    }

    // --- CloseSettings --------------------------------------------------------------------

    @Test
    fun `CloseSettings clears the form and returns to the Main screen`() = runTest {
        val reducer = CloseSettingsReducer()
        val openState = ScreenState(
            screen = ScreenState.Screen.Settings,
            settingsForm = SettingsFormState.from(AppSettings()),
        )

        val result = reducer.reduce(MainAction.CloseSettings, openState)

        assertEquals(ScreenState.Screen.Main, result.state.screen)
        assertNull(result.state.settingsForm)
    }

    // --- EditSettings -----------------------------------------------------------------------

    @Test
    fun `EditSettings applies the edit to the current form`() = runTest {
        val reducer = EditSettingsReducer()
        val state = ScreenState(
            screen = ScreenState.Screen.Settings,
            settingsForm = SettingsFormState.from(AppSettings()),
        )

        val result = reducer.reduce(MainAction.EditSettings(SettingsEdit.DisplayName("Bob")), state)

        assertEquals("Bob", assertNotNull(result.state.settingsForm).displayName)
    }

    @Test
    fun `EditSettings applies to the current state rather than a stale snapshot`() = runTest {
        // The point of moving this into the reducer: the form seen here is whatever the state
        // holds right now, never something the view captured earlier.
        val reducer = EditSettingsReducer()
        val firstEdit = reducer.reduce(
            MainAction.EditSettings(SettingsEdit.DisplayName("Ann")),
            ScreenState(settingsForm = SettingsFormState.from(AppSettings())),
        )

        val secondEdit = reducer.reduce(
            MainAction.EditSettings(SettingsEdit.Channel("7")),
            firstEdit.state,
        )

        val form = assertNotNull(secondEdit.state.settingsForm)
        assertEquals("Ann", form.displayName, "the earlier edit must not be lost")
        assertEquals("7", form.channel)
    }

    @Test
    fun `EditSettings with no open form leaves the state untouched`() = runTest {
        val reducer = EditSettingsReducer()
        val state = ScreenState(settingsForm = null)

        val result = reducer.reduce(MainAction.EditSettings(SettingsEdit.DisplayName("Bob")), state)

        assertNull(result.state.settingsForm)
    }

    // --- SaveSettings -----------------------------------------------------------------------

    @Test
    fun `SaveSettings persists the form - clears it - returns Main - reconnects and shows a message`() =
        runTest {
            val repository = SettingsRepository(FakeDataStore())
            val reducer = SaveSettingsReducer(repository)
            val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.DisplayName("Ann"))
            val state = ScreenState(screen = ScreenState.Screen.Settings, settingsForm = form)

            val result = reducer.reduce(MainAction.SaveSettings, state)

            assertEquals(ScreenState.Screen.Main, result.state.screen)
            assertNull(result.state.settingsForm)
            assertEquals(MainAction.Reconnect, result.action)
            assertTrue(result.event is MainEvent.ShowMessage)
            assertEquals("Ann", repository.settings.first().displayName)
        }

    @Test
    fun `SaveSettings on a form with an error writes nothing and leaves state untouched`() = runTest {
        val repository = SettingsRepository(FakeDataStore())
        val reducer = SaveSettingsReducer(repository)
        val badForm = SettingsFormState.from(AppSettings())
            .apply(SettingsEdit.DisplayName("x".repeat(AppSettings.MAX_NAME_LENGTH + 1)))
        val state = ScreenState(screen = ScreenState.Screen.Settings, settingsForm = badForm)

        val result = reducer.reduce(MainAction.SaveSettings, state)

        assertEquals(state, result.state)
        assertNull(result.action)
        assertNull(result.event)
        // Nothing was written: the stored settings are still the fresh-install defaults.
        assertEquals(AppSettings.DEFAULT_NAME, repository.settings.first().displayName)
    }

    @Test
    fun `SaveSettings with no open form writes nothing and leaves state untouched`() = runTest {
        val repository = SettingsRepository(FakeDataStore())
        val reducer = SaveSettingsReducer(repository)
        val state = ScreenState(screen = ScreenState.Screen.Settings, settingsForm = null)

        val result = reducer.reduce(MainAction.SaveSettings, state)

        assertEquals(state, result.state)
        assertNull(result.action)
        assertNull(result.event)
    }

    // --- RequestOverlayPermission -------------------------------------------------------------

    @Test
    fun `RequestOverlayPermission emits the event without changing state`() = runTest {
        val reducer = RequestOverlayPermissionReducer()
        val state = ScreenState(screen = ScreenState.Screen.Settings)

        val result = reducer.reduce(MainAction.RequestOverlayPermission, state)

        assertEquals(state, result.state)
        assertNull(result.action)
        assertEquals(MainEvent.RequestOverlayPermission, result.event)
    }
}
