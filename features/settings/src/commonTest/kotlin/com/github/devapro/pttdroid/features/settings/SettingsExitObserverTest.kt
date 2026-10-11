package com.github.devapro.pttdroid.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.features.api.settings.SettingsAction
import com.github.devapro.pttdroid.features.api.settings.SettingsEdit
import com.github.devapro.pttdroid.features.api.settings.SettingsState
import com.github.devapro.pttdroid.features.settings.reducer.CloseSettingsReducer
import com.github.devapro.pttdroid.features.settings.reducer.EditSettingsReducer
import com.github.devapro.pttdroid.features.settings.reducer.OpenSettingsReducer
import com.github.devapro.pttdroid.features.settings.reducer.RequestOverlayPermissionReducer
import com.github.devapro.pttdroid.features.settings.reducer.SaveSettingsReducer
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.mvi.Reducer
import com.github.devapro.pttdroid.shared.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The Settings screen's exit rule ([SettingsExitObserver]) — the only route out of the screen for
 * both the back arrow and a successful save.
 *
 * Nothing here is a Compose or Voyager test on purpose: `:features:settings`' roots are otherwise
 * covered by the instrumented `SettingsScreenTest`, which threads the *pure* composable and so
 * never touches the pop. These tests cover the half of the root that was net-new in the Voyager
 * migration and had no test at all, against the **real** collector ([SettingsExitObserver.observe]),
 * the **real** [SettingsViewModel], [SettingsActionProcessor], reducers and [SettingsRepository]. A
 * Compose or coroutines bump that changes when the seeded → null transition is seen now fails a
 * desktop test in CI instead of silently stranding the user in Settings.
 *
 * Two deliberate deviations from the production graph, both in the wiring and not in what is under
 * test:
 *  - [NoopReconnectReducer] stands in for `ReconnectReducer`. `SaveSettingsReducer` chains a
 *    [SettingsAction.Reconnect] and the processor `error()`s on an unregistered action, so
 *    *something* must answer it — but answering it for real needs a whole `PttController` (socket,
 *    recorder, player), which decides nothing about whether the screen pops. What the controller
 *    does on a reconnect is `:shared`'s `PttControllerTest`'s job.
 *  - `Dispatchers.setMain` backs `viewModelScope`: the multiplatform `ViewModel` builds its scope
 *    on `Dispatchers.Main.immediate`, which has no platform dispatcher in a plain unit test. The
 *    unconfined test dispatcher is bound to each `runTest`'s own scheduler (see [runOnMain]) so
 *    nothing the view model dispatches can land on a scheduler nobody pumps.
 *
 * What is *not* covered here, because it is a composition property rather than a rule: that the
 * seed `LaunchedEffect` in `SettingsScreenFactoryImpl` runs once per entry (its
 * `LaunchedEffect(viewModel)` key) and after the observer starts. That needs a composition — a
 * device (`:features:settings`' instrumented suite) or a desktop `runComposeUiTest`, which this
 * module's test classpath has no skiko-backed UI-test infrastructure for. The declaration order of
 * the two `LaunchedEffect`s is the guarantee, and it is commented at the call site.
 */
class SettingsExitObserverTest {

    /** In-memory `DataStore<Preferences>`, the same double `SettingsReducersTest` uses. */
    private class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences,
        ): Preferences = transform(state.value).also { state.value = it }
    }

    /** Answers the save's chained Reconnect without a PttController — see the class KDoc. */
    private class NoopReconnectReducer :
        Reducer<SettingsAction.Reconnect, SettingsState, SettingsAction, UiEvent> {
        override val actionClass = SettingsAction.Reconnect::class

        override suspend fun reduce(
            action: SettingsAction.Reconnect,
            state: SettingsState,
        ): Reducer.Result<SettingsState, SettingsAction, UiEvent?> = Reducer.Result(state)
    }

    /** Counts pops the way the root's `navigator::pop` would: one per actual pop. */
    private var pops = 0

    /**
     * The test-body counterpart of the real observer's dispatcher: the screen root's collector runs
     * on `Dispatchers.Main.immediate`, which is eager, so an observer here must be too — a plain
     * [kotlinx.coroutines.test.TestDispatcher] child of [TestScope.backgroundScope] would only start
     * once the body suspends, and would then see conflated values instead of the transitions.
     */
    private fun TestScope.main(): TestDispatcher = UnconfinedTestDispatcher(testScheduler)

    private fun runOnMain(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModelWith(repository: SettingsRepository) = SettingsViewModel(
        SettingsActionProcessor(
            setOf(
                OpenSettingsReducer(repository),
                CloseSettingsReducer(),
                EditSettingsReducer(),
                SaveSettingsReducer(repository),
                RequestOverlayPermissionReducer(),
                NoopReconnectReducer(),
            ),
        ),
    )

    // --- the transition rule itself -------------------------------------------------------------

    @Test
    fun `a seeded form going away pops once`() {
        val exit = SettingsExitObserver { pops++ }

        exit.onFormPresent(true)
        exit.onFormPresent(false)

        assertEquals(1, pops)
    }

    @Test
    fun `the null frames before the seed arrives do not pop`() {
        val exit = SettingsExitObserver { pops++ }

        // The frames between this root entering the composition and OpenSettingsReducer's
        // repository read landing: the form is legitimately null, and popping on them is the bug
        // the wasSeeded flag exists to stop.
        exit.onFormPresent(false)
        exit.onFormPresent(false)
        assertEquals(0, pops)

        exit.onFormPresent(true)
        assertEquals(0, pops)

        exit.onFormPresent(false)
        assertEquals(1, pops)
    }

    @Test
    fun `Close pops once and the edge that follows it does not pop again`() {
        val exit = SettingsExitObserver { pops++ }

        exit.onFormPresent(true)
        // The arrow: Close is dispatched, the root pops beside it, and CloseSettingsReducer then
        // clears the form — the second of these two paths must be the no-op, or the pop would
        // take Main off the stack as well.
        exit.onClose()
        exit.onFormPresent(false)

        assertEquals(1, pops)
    }

    @Test
    fun `an edge that fires first leaves a later Close a no-op`() {
        val exit = SettingsExitObserver { pops++ }

        exit.onFormPresent(true)
        exit.onFormPresent(false)
        exit.onClose()

        assertEquals(1, pops)
    }

    // --- the same rule driven through the real MVI loop ------------------------------------------

    @Test
    fun `Open then Close pops exactly once through the real view model`() = runOnMain {
        val repository = SettingsRepository(FakeDataStore())
        val viewModel = viewModelWith(repository)
        val exit = SettingsExitObserver { pops++ }

        // The root's own order: observer first, seed second.
        backgroundScope.launch(main()) { exit.observe(viewModel.state) }
        advanceUntilIdle()
        assertNull(viewModel.state.value.form)
        assertEquals(0, pops)

        viewModel.onAction(SettingsAction.Open)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.form)
        assertEquals(0, pops)

        viewModel.onAction(SettingsAction.Close)
        exit.onClose()
        advanceUntilIdle()

        assertNull(viewModel.state.value.form)
        assertEquals(1, pops)
    }

    @Test
    fun `a rejected save does not pop and the form keeps the edit`() = runOnMain {
        // useTls = false everywhere it appears in these two tests: the default form is not
        // saveable (TLS on with no pinned fingerprint), which would make a "rejected save" pass
        // for the wrong reason.
        val repository = SettingsRepository(FakeDataStore())
        repository.save(AppSettings(displayName = "Ann", useTls = false))
        val viewModel = viewModelWith(repository)
        val exit = SettingsExitObserver { pops++ }

        backgroundScope.launch(main()) { exit.observe(viewModel.state) }
        viewModel.onAction(SettingsAction.Open)
        advanceUntilIdle()

        // A channel outside AppSettings.CHANNEL_RANGE. The Save button is already disabled on it,
        // but a reducer may not depend on the view having got that right (known-issues #25).
        viewModel.onAction(SettingsAction.EditSettings(SettingsEdit.Channel("0")))
        advanceUntilIdle()
        val form = assertNotNull(viewModel.state.value.form)
        assertTrue(form.channelError)
        assertTrue(form.hasError)

        viewModel.onAction(SettingsAction.SaveSettings)
        advanceUntilIdle()

        assertEquals(0, pops)
        assertEquals("0", assertNotNull(viewModel.state.value.form).channel)
        // Refusing the save also means not writing it.
        assertEquals("Ann", repository.settings.first().displayName)
    }

    @Test
    fun `a successful save pops and still gets its message out`() = runOnMain {
        val repository = SettingsRepository(FakeDataStore())
        repository.save(AppSettings(displayName = "Ann", useTls = false))
        val viewModel = viewModelWith(repository)
        val exit = SettingsExitObserver { pops++ }
        val events = mutableListOf<UiEvent>()

        backgroundScope.launch(main()) { exit.observe(viewModel.state) }
        backgroundScope.launch(main()) { viewModel.event.collect { events += it } }
        viewModel.onAction(SettingsAction.Open)
        advanceUntilIdle()
        assertFalse(assertNotNull(viewModel.state.value.form).hasError)

        viewModel.onAction(SettingsAction.EditSettings(SettingsEdit.DisplayName("Bo")))
        viewModel.onAction(SettingsAction.SaveSettings)
        advanceUntilIdle()

        assertEquals(1, pops)
        assertNull(viewModel.state.value.form)
        assertEquals("Bo", repository.settings.first().displayName)
        // The ordering inside SettingsViewModel.onAction (_state first, then trySend) is what lets
        // the snackbar survive the pop this observer just caused: the pop must not swallow it.
        val message = events.single() as UiEvent.ShowMessage
        assertEquals(Res.string.settings_saved, message.messageRes)
    }

    @Test
    fun `an unrelated state write while the form is seeded does not pop`() = runOnMain {
        val repository = SettingsRepository(FakeDataStore())
        val viewModel = viewModelWith(repository)
        val exit = SettingsExitObserver { pops++ }

        backgroundScope.launch(main()) { exit.observe(viewModel.state) }
        viewModel.onAction(SettingsAction.Open)
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.form)

        // MainActivity.onResume mirrors "draw over other apps" into SettingsState while Settings is
        // open, and the screen can emit RequestOverlayPermission: neither touches the form, so
        // neither may read as the form going away.
        viewModel.onOverlayPermissionResult(granted = false)
        advanceUntilIdle()
        assertEquals(0, pops)

        viewModel.onAction(SettingsAction.RequestOverlayPermission)
        advanceUntilIdle()
        assertEquals(0, pops)

        assertNotNull(viewModel.state.value.form)
    }
}
