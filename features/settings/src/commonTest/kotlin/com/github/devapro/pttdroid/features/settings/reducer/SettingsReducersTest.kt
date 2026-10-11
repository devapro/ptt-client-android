package com.github.devapro.pttdroid.features.settings.reducer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsFormState
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.features.api.settings.SettingsEdit
import com.github.devapro.pttdroid.model.UiEvent
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
 * The settings screen's five reducers — all ten cases kept — exercised against a fake
 * `DataStore<Preferences>` rather than a real one: [SettingsRepository] takes the interface, so
 * no Android/desktop backing store is needed to prove the MVI loop: seeding on open, applying an
 * edit to the *current* state, persisting (or refusing to) on save, and the overlay-permission
 * escape hatch.
 *
 * The form — not a `screen` field — is what open/close flip: navigation is host state, not
 * `SettingsState`'s.
 *
 * The fake `DataStore` below moved with these tests (the module that owns a test must compile
 * it, and KMP test source sets are not consumable across modules without a dedicated
 * test-fixture publishing setup — same note as `:features:history`'s `HistoryTestDoubles.kt`).
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
    fun `OpenSettings seeds the form from the repository`() = runTest {
        val repository = SettingsRepository(FakeDataStore())
        repository.save(AppSettings(displayName = "Ann", channel = 5))
        val reducer = OpenSettingsReducer(repository)

        val result = reducer.reduce(SettingsAction.Open, SettingsState())

        val form = assertNotNull(result.state.form)
        assertEquals("Ann", form.displayName)
        assertEquals("5", form.channel)
    }

    @Test
    fun `OpenSettings seeds from whatever is on disk rather than the fresh install defaults`() = runTest {
        val repository = SettingsRepository(FakeDataStore())
        repository.save(AppSettings(accessToken = "s3cret"))
        val reducer = OpenSettingsReducer(repository)

        val result = reducer.reduce(SettingsAction.Open, SettingsState())

        assertEquals("s3cret", assertNotNull(result.state.form).accessToken)
    }

    // --- CloseSettings ------------------------------------------------------------------------

    @Test
    fun `CloseSettings clears the form`() = runTest {
        val reducer = CloseSettingsReducer()
        val openState = SettingsState(form = SettingsFormState.from(AppSettings()))

        val result = reducer.reduce(SettingsAction.Close, openState)

        assertNull(result.state.form)
    }

    // --- EditSettings -----------------------------------------------------------------------

    @Test
    fun `EditSettings applies the edit to the current form`() = runTest {
        val reducer = EditSettingsReducer()
        val state = SettingsState(form = SettingsFormState.from(AppSettings()))

        val result = reducer.reduce(SettingsAction.EditSettings(SettingsEdit.DisplayName("Bob")), state)

        assertEquals("Bob", assertNotNull(result.state.form).displayName)
    }

    @Test
    fun `EditSettings applies to the current state rather than a stale snapshot`() = runTest {
        // The point of moving this into the reducer: the form seen here is whatever the state
        // holds right now, never something the view captured earlier.
        val reducer = EditSettingsReducer()
        val firstEdit = reducer.reduce(
            SettingsAction.EditSettings(SettingsEdit.DisplayName("Ann")),
            SettingsState(form = SettingsFormState.from(AppSettings())),
        )

        val secondEdit = reducer.reduce(
            SettingsAction.EditSettings(SettingsEdit.Channel("7")),
            firstEdit.state,
        )

        val form = assertNotNull(secondEdit.state.form)
        assertEquals("Ann", form.displayName, "the earlier edit must not be lost")
        assertEquals("7", form.channel)
    }

    @Test
    fun `EditSettings with no open form leaves the state untouched`() = runTest {
        val reducer = EditSettingsReducer()
        val state = SettingsState(form = null)

        val result = reducer.reduce(SettingsAction.EditSettings(SettingsEdit.DisplayName("Bob")), state)

        assertNull(result.state.form)
    }

    // --- SaveSettings -----------------------------------------------------------------------

    @Test
    fun `SaveSettings persists the form - clears it - reconnects and shows a message`() = runTest {
        val repository = SettingsRepository(FakeDataStore())
        val reducer = SaveSettingsReducer(repository)
        val form = SettingsFormState.from(AppSettings()).apply(SettingsEdit.DisplayName("Ann"))
        val state = SettingsState(form = form)

        val result = reducer.reduce(SettingsAction.SaveSettings, state)

        // One Result carries all three: the cleared form, the chained reconnect that must stay
        // inside this processor (see SaveSettingsReducer's KDoc), and the snackbar event.
        assertNull(result.state.form)
        assertEquals(SettingsAction.Reconnect, result.action)
        assertTrue(result.event is UiEvent.ShowMessage)
        assertEquals("Ann", repository.settings.first().displayName)
    }

    @Test
    fun `SaveSettings on a form with an error writes nothing and leaves state untouched`() = runTest {
        val repository = SettingsRepository(FakeDataStore())
        val reducer = SaveSettingsReducer(repository)
        val badForm = SettingsFormState.from(AppSettings())
            .apply(SettingsEdit.DisplayName("x".repeat(AppSettings.MAX_NAME_LENGTH + 1)))
        val state = SettingsState(form = badForm)

        val result = reducer.reduce(SettingsAction.SaveSettings, state)

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
        val state = SettingsState(form = null)

        val result = reducer.reduce(SettingsAction.SaveSettings, state)

        assertEquals(state, result.state)
        assertNull(result.action)
        assertNull(result.event)
    }

    // --- RequestOverlayPermission -------------------------------------------------------------

    @Test
    fun `RequestOverlayPermission emits the event without changing state`() = runTest {
        val reducer = RequestOverlayPermissionReducer()
        val state = SettingsState(form = SettingsFormState.from(AppSettings()))

        val result = reducer.reduce(SettingsAction.RequestOverlayPermission, state)

        assertEquals(state, result.state)
        assertNull(result.action)
        assertEquals(UiEvent.RequestOverlayPermission, result.event)
    }
}
