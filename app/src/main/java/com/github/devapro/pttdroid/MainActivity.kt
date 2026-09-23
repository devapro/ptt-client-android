package com.github.devapro.pttdroid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.Navigator
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.LanguageMode
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.data.settings.applyLocale
import com.github.devapro.pttdroid.features.api.main.MainAction
import com.github.devapro.pttdroid.features.api.main.MainScreen
import com.github.devapro.pttdroid.features.main.MainViewModel
import com.github.devapro.pttdroid.features.settings.SettingsViewModel
import com.github.devapro.pttdroid.foreground.ActivityForegroundTracker
import com.github.devapro.pttdroid.model.UiEvent
import com.github.devapro.pttdroid.overlay.OverlayController
import com.github.devapro.pttdroid.ui.LocalPttUiEffects
import com.github.devapro.pttdroid.ui.PttUiEffects
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.android.ext.android.inject

/**
 * Thin UI shell.
 *
 * It owns no session state — no socket, no recorder, no player; that is `PttController`, hosted
 * by `PttForegroundService`, so backgrounding the app no longer tears down a transmission in
 * flight. It owns no persistence either: saving settings is an action like any other, and this
 * class is left with the things only an Activity can do — asking for permissions, resolving
 * string resources for one-shot messages, and being the Android half of the locale plumbing.
 *
 * Since the Voyager migration this Activity renders `Navigator(MainScreen)` and stops collecting
 * view-model event flows: each screen root collects **its own** view model's events into the
 * [PttUiEffects] provided below ([LocalPttUiEffects]), so what remains here is only the
 * platform half of those three effects — and the STARTED gate those effects are delivered under,
 * which the deleted `collectEvents()` used to provide and which per-screen collection does not
 * (see [pendingEffects]). The view models are injected with plain `by inject()`
 * rather than `by viewModel()`: the feature DI modules register them as Koin `single`s, which is
 * the same identity guarantee the `ViewModelStore` used to give — the instance observed here and
 * the one the screens resolve are one and the same, and it survives `recreate()` (the locale
 * path below) because nothing about it is tied to this Activity's lifecycle any more. That is
 * also why a *second* injection can no longer fork a shadow view model (known-issues #22's
 * shape under Koin 4's factory-flavoured `viewModelOf`): identity is structural now.
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by inject()
    private val settingsViewModel: SettingsViewModel by inject()
    private val settingsRepository: SettingsRepository by inject()
    private val overlayController: OverlayController by inject()
    private val snackbarHostState = SnackbarHostState()

    /**
     * One-shot effects raised by the screens, parked until this Activity is STARTED.
     *
     * Same shape as every feature view model's event channel (capacity 8, `DROP_OLDEST`) on
     * purpose: a stopped Activity must be able to absorb a burst without growing a queue that
     * nothing will ever drain, and dropping the oldest of four queued snackbars while backgrounded
     * is the trade the rest of the app already makes. [trySend] never fails on this channel, which
     * is what lets [PttUiEffects]'s members stay non-suspending.
     */
    private val pendingEffects = Channel<UiEvent>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The host half of [PttUiEffects]: screens collect their events per-screen and call through
     * this. Nothing is *performed* here — each member only parks the request in [pendingEffects],
     * which the STARTED-gated drain in `setContent` runs. That gate is the whole point: showing a
     * snackbar, launching a permission prompt or starting the "draw over other apps" screen from a
     * stopped Activity either targets a window that is not there or leaves `showSnackbar` holding
     * [SnackbarHostState]'s internal mutex on a host that will never recompose, which then blocks
     * every message after it. The deleted `collectEvents()` had this guarantee structurally;
     * per-screen collection moved into the screens for Voyager, and without the queue here the
     * Activity had lost it.
     */
    private val uiEffects = object : PttUiEffects {
        override val snackbarHostState: SnackbarHostState = this@MainActivity.snackbarHostState

        override fun showMessage(messageRes: StringResource) {
            pendingEffects.trySend(UiEvent.ShowMessage(messageRes))
        }

        override fun requestMicPermission() {
            pendingEffects.trySend(UiEvent.RequestMicPermission)
        }

        override fun requestOverlayPermission() {
            pendingEffects.trySend(UiEvent.RequestOverlayPermission)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.RECORD_AUDIO] == true
        // The single mic-grant entry point: MainViewModel mirrors the flag *and* dispatches
        // InitConnection on a grant (the service start the old single view model used to drive).
        mainViewModel.onMicPermissionResult(granted)
    }

    /**
     * The [AppSettings] `attachBaseContext` actually read from DataStore, including the
     * [LanguageMode] it applied to this instance's base context.
     *
     * `setContent` must seed `collectAsState`'s `initial` with this, not with `AppSettings()`:
     * `settingsRepository.settings` is a cold flow whose first collection can take a beat to
     * reach DataStore, so `AppSettings()` — [LanguageMode.SYSTEM] — would otherwise render, and be
     * compared against, for one frame even when a different language is already stored and
     * already applied to this very context. That phantom mismatch is what used to send this
     * Activity into an unbounded `recreate()` loop: each recreated instance re-ran the same
     * pre-DataStore frame and recreated again, with no exception to point at it. Seeding from the
     * real read means the first composed value always agrees with what `attachBaseContext`
     * applied, so a `recreate()` only fires on a genuine language change — and, as a side effect,
     * the UI never flashes against default host/theme values before DataStore emits.
     */
    private var initialSettings: AppSettings = AppSettings()

    override fun attachBaseContext(base: Context) {
        // A DataStore read failure here must never turn into a launch crash — AppSettings()'s
        // SYSTEM default is always a safe fallback. Reading it with a blocking call is
        // unfortunate but unavoidable: attachBaseContext has no suspend equivalent, and the base
        // context has to be replaced before super.attachBaseContext() hands it to the rest of the
        // Activity's construction. settingsRepository resolves here because Koin is started in
        // PTTdroidApplication.onCreate(), which the platform runs before any Activity's
        // attachBaseContext.
        val settings = try {
            runBlocking { settingsRepository.settings.first() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppSettings()
        }
        initialSettings = settings
        super.attachBaseContext(applyLocale(base, settings.languageMode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 36 enforces edge-to-edge; the old code set the deprecated
        // window.statusBarColor, which is now a no-op. Insets are handled by the Scaffold in
        // each screen rather than by padding the whole window, so the background still runs
        // under the status bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by settingsRepository.settings.collectAsState(initial = initialSettings)

            // Seeded from initialSettings.languageMode — the same value collectAsState's initial
            // just rendered — so the first composition always agrees with itself. See
            // initialSettings's KDoc for why AppSettings()'s default must never appear here.
            var appliedLanguage by remember { mutableStateOf(initialSettings.languageMode) }
            LaunchedEffect(settings.languageMode) {
                if (settings.languageMode != appliedLanguage) {
                    appliedLanguage = settings.languageMode
                    recreate()
                }
            }

            PTTdroidTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())) {
                // The STARTED gate the deleted collectEvents() carried, restored on the delivery
                // side instead of the collection side: the per-screen collectors in
                // rememberPttUiEvents must keep draining the view models' buffered channels while
                // this Activity is stopped (that is how a save made just before backgrounding is
                // not lost), so what this loop controls is only *when the host acts* on what they
                // forwarded. Below STARTED the loop suspends and [pendingEffects] holds the
                // requests — never a prompt or a snackbar aimed at a window that is not there.
                LaunchedEffect(Unit) {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        for (event in pendingEffects) {
                            when (event) {
                                // resolveMessage() rather than getString(...) straight here: with an
                                // explicit receiver position the compiler binds
                                // Context.getString(Int) (a Java member) and fails, while a member
                                // function of this class resolves against it as the implicit
                                // receiver and reaches the compose-resources extension. The
                                // Activity's locale-applied Context is what resolves the string.
                                is UiEvent.ShowMessage ->
                                    snackbarHostState.showSnackbar(resolveMessage(event.messageRes))
                                // The two permission paths: this launcher call is exactly what
                                // onStart() already does for the mic, and ACTION_MANAGE_OVERLAY_PERMISSION
                                // from a stopped Activity is the Android 10+ background-activity-start
                                // ban the old gate existed to respect.
                                UiEvent.RequestMicPermission -> requestMicPermission()
                                UiEvent.RequestOverlayPermission -> requestOverlayPermission()
                            }
                        }
                    }
                }

                // Voyager: the Navigator root lives in this composition, so a recreate() (the
                // language path above) starts a fresh stack on Main — the same landing point the
                // old router's composition state had. The screen roots resolve their own view
                // models; the only thing threaded through here is this Activity's effects host.
                CompositionLocalProvider(LocalPttUiEffects provides uiEffects) {
                    Navigator(MainScreen)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ActivityForegroundTracker.onActivityStarted()
        // The floating bubble is for reaching PTT from other apps; over our own screen it just
        // covers the button it duplicates.
        overlayController.setAppVisible(true)

        // Requesting from a visible Activity is what makes it legal to start a microphone
        // foreground service on Android 14+.
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        // Mirrors the flag into MainViewModel and, on a grant, dispatches InitConnection — the
        // session kick this used to be through the old single view model. A second
        // InitConnection is harmless by construction: PttController.start() returns early while
        // its session job is active and PttForegroundService.promoteToForeground() only starts
        // the controller once (the `started` flag), so the repeated cost is a re-affirmed
        // foreground claim, never a re-dialled session.
        mainViewModel.onMicPermissionResult(granted)
        if (!granted) requestMicPermission()
    }

    override fun onResume() {
        super.onResume()
        // Re-check the special permission whenever we come back to the app — including back from
        // the system "draw over other apps" screen the Settings warning row sends the user to.
        // It used to ride on every screen change; that poll died with the single screen state.
        // Gating it on Settings instead would need navigation knowledge this Activity no longer
        // has, and there is no reason to: canDrawOverlay only ever gates a warning row rendered
        // from SettingsState, and writing the flag on every resume is what keeps that row
        // truthful the moment the grant actually changes.
        settingsViewModel.onOverlayPermissionResult(canDrawOverlays())
        lifecycleScope.launch {
            // Applying the toggle needs the permission that may have just been granted. The
            // session start lives behind MainViewModel's mic gate like every other InitConnection
            // dispatch; on Android the launcher it reaches starts the foreground service, which
            // hosts the session even with the app never opened further.
            val settings = settingsRepository.settings.first()
            if (settings.floatingButtonEnabled && canDrawOverlays()) {
                mainViewModel.onAction(MainAction.InitConnection)
            }
        }
    }

    /**
     * Hides the bubble over our own screen and clears the foreground claim. Nothing is cancelled
     * here and nothing needs to be: the per-screen collectors keep draining the view models'
     * buffered channels, [pendingEffects] holds whatever they forward, and the STARTED-gated drain
     * in `setContent` picks it up on the way back — the same guarantee the deleted
     * `collectEvents()` had, just moved from the collection to the delivery. The
     * ActivityResultRegistry still defers permission *results* to STARTED on its own.
     */
    override fun onStop() {
        overlayController.setAppVisible(false)
        ActivityForegroundTracker.onActivityStopped()
        super.onStop()
    }

    private suspend fun resolveMessage(res: StringResource): String =
        getString(resource = res)

    private fun requestMicPermission() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        micPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}
