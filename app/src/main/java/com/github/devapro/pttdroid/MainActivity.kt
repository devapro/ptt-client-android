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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.LanguageMode
import com.github.devapro.pttdroid.data.settings.SettingsRepository
import com.github.devapro.pttdroid.data.settings.applyLocale
import com.github.devapro.pttdroid.model.MainAction
import com.github.devapro.pttdroid.model.MainEvent
import com.github.devapro.pttdroid.model.ScreenState
import com.github.devapro.pttdroid.overlay.OverlayController
import com.github.devapro.pttdroid.ui.MainScreen
import com.github.devapro.pttdroid.ui.SettingsScreen
import com.github.devapro.pttdroid.ui.theme.PTTdroidTheme
import com.github.devapro.pttdroid.viewmodel.MainActivityViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Thin UI shell.
 *
 * It owns no session state — no socket, no recorder, no player; that is `PttController`, hosted
 * by `PttForegroundService`, so backgrounding the app no longer tears down a transmission in
 * flight. It owns no persistence either: saving settings is an action like any other, and this
 * class is left with the two things only an Activity can do — asking for permissions and
 * resolving string resources for one-shot messages.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainActivityViewModel by viewModel()
    private val settingsRepository: SettingsRepository by inject()
    private val overlayController: OverlayController by inject()
    private val snackbarHostState = SnackbarHostState()

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        viewModel.onMicPermissionResult(grants[Manifest.permission.RECORD_AUDIO] == true)
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
            val state by viewModel.state.collectAsStateWithLifecycle()
            val settings by settingsRepository.settings.collectAsState(initial = initialSettings)
            var overlayGranted by remember { mutableStateOf(canDrawOverlays()) }

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
                when (state.screen) {
                    ScreenState.Screen.Main -> MainScreen(
                        state = state,
                        endpoint = "${settings.serverHost}:${settings.serverPort}",
                        snackbarHostState = snackbarHostState,
                        onAction = viewModel::onAction,
                    )

                    ScreenState.Screen.Settings -> SettingsScreen(
                        settings = settings,
                        canDrawOverlay = overlayGranted,
                        onSave = { viewModel.onAction(MainAction.SaveSettings(it)) },
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onBack = { viewModel.onAction(MainAction.CloseSettings) },
                    )
                }
            }

            // Re-check the special permission whenever we come back from Settings.
            LaunchedEffect(state.screen) { overlayGranted = canDrawOverlays() }
        }

        collectEvents()
    }

    override fun onStart() {
        super.onStart()
        // The floating bubble is for reaching PTT from other apps; over our own screen it just
        // covers the button it duplicates.
        overlayController.setAppVisible(true)

        // Requesting from a visible Activity is what makes it legal to start a microphone
        // foreground service on Android 14+.
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        viewModel.onMicPermissionResult(granted)
        if (!granted) requestMicPermission()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            // Applying the toggle needs the permission that may have just been granted.
            val settings = settingsRepository.settings.first()
            if (settings.floatingButtonEnabled && canDrawOverlays()) {
                viewModel.onAction(MainAction.InitConnection)
            }
        }
    }

    /**
     * Bound to STARTED: a permission dialog or a snackbar raised while the Activity is stopped
     * would either be dropped or arrive at a window that is no longer there. Events are buffered
     * on a channel, so nothing is lost while it is suspended.
     */
    override fun onStop() {
        overlayController.setAppVisible(false)
        super.onStop()
    }

    private fun collectEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.event.collect { event ->
                    when (event) {
                        MainEvent.RequestMicPermission -> requestMicPermission()
                        MainEvent.RequestOverlayPermission -> requestOverlayPermission()
                        is MainEvent.ShowMessage ->
                            snackbarHostState.showSnackbar(getString(resource = event.messageRes))
                    }
                }
            }
        }
    }

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
