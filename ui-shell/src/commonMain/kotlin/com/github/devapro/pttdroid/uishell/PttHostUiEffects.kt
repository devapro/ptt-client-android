package com.github.devapro.pttdroid.uishell

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.github.devapro.pttdroid.ui.PttUiEffects
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * The desktop/iOS implementation of the host's [PttUiEffects] contract.
 *
 * `:app`'s `MainActivity` cannot use this — its two permission members need an
 * `ActivityResultLauncher` and an `Intent`, and its string resolution is happiest with the
 * Activity's own locale-applied `Context` — so it builds its own. Desktop and iOS have no runtime
 * microphone permission and no "draw over other apps" screen to route those two events to (the
 * exact asymmetry [PttUiEffects]'s KDoc documents), so they share this one.
 *
 * [showMessage] launches into the **calling composition's** scope rather than suspending from the
 * screen's collector: `showSnackbar` suspends until the snackbar is dismissed, and the collector
 * coroutine that observed the event belongs to a screen root that may pop before then (Settings'
 * save message outlives its own pop to Main). A suspension that outlived its scope would be
 * cancelled with it and the message lost; launched here, the snackbar rides out the navigation on
 * the one [SnackbarHostState] the whole app shares.
 */
@Composable
fun rememberPttHostUiEffects(hostState: SnackbarHostState): PttUiEffects {
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) {
        object : PttUiEffects {
            override val snackbarHostState: SnackbarHostState = hostState

            override fun showMessage(messageRes: StringResource) {
                scope.launch { snackbarHostState.showSnackbar(getString(messageRes)) }
            }

            override fun requestMicPermission() {
                // No runtime mic gate on desktop or iOS: both roots report the grant up front
                // (onMicPermissionResult(true)), so nothing should ever route an event here.
            }

            override fun requestOverlayPermission() {
                // No "draw over other apps" concept outside Android.
            }
        }
    }
}
