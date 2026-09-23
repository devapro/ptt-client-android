package com.github.devapro.pttdroid.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.github.devapro.pttdroid.PttLog
import com.github.devapro.pttdroid.model.UiEvent
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.flow.Flow

/**
 * The host's side of one-shot UI effects — the things a `ViewModel` can ask for that are neither
 * state nor navigation.
 *
 * Voyager made event handling **per screen** (each screen root in `:features:*` collects its own
 * `ViewModel`'s event flow — see [rememberPttUiEvents]), and a screen root cannot do these three
 * things by itself: showing a snackbar needs the platform's string resolution, requesting the
 * microphone needs an `ActivityResultLauncher`, and requesting "draw over other apps" needs an
 * `Intent`. So the host supplies this object, once, through [LocalPttUiEffects], and every feature
 * routes its events through it. Living in `:shared` — next to [UiEvent] itself, whose three
 * variants its three members mirror — is what lets `:features:*`, `:ui-shell`, `:app` and
 * `:desktopApp` all name it without any of them owning it; `:core` cannot host it because the
 * api/impl split means a feature's *contract* module must not depend on `:core`.
 *
 * [snackbarHostState] is part of the contract rather than a parameter of the screens because it
 * has to be **one instance for the whole app**: `MainScreen`'s `Scaffold` is the only surface that
 * renders a `SnackbarHost`, and a message raised by Settings has to still be on screen after the
 * save pops back to Main.
 */
interface PttUiEffects {
    /** The single [SnackbarHostState] `MainScreen`'s `Scaffold` renders. */
    val snackbarHostState: SnackbarHostState

    /** Resolve [messageRes] and put it on [snackbarHostState]. */
    fun showMessage(messageRes: StringResource)

    /** `UiEvent.RequestMicPermission` — the runtime RECORD_AUDIO prompt (Android only). */
    fun requestMicPermission()

    /** `UiEvent.RequestOverlayPermission` — the "draw over other apps" screen (Android only). */
    fun requestOverlayPermission()

    /**
     * What a screen sees when no host provided anything: previews and the instrumented screen
     * tests, which drive the pure composables and never raise events.
     *
     * Every member logs rather than doing nothing, because the failure mode this guards is real
     * (known-issues #22: a dropped `ShowMessage` is a silently-failed save) and a screen wired up
     * without a host should be loud in logcat, not quiet on screen.
     */
    object None : PttUiEffects {
        override val snackbarHostState = SnackbarHostState()

        override fun showMessage(messageRes: StringResource) {
            // The resource itself is not nameable here: StringResource's identifier is
            // `@InternalResourceApi`. Say which event was lost, that is the point.
            PttLog.w { "No PttUiEffects host: dropping a ShowMessage event" }
        }

        override fun requestMicPermission() {
            PttLog.w { "No PttUiEffects host: dropping RequestMicPermission" }
        }

        override fun requestOverlayPermission() {
            PttLog.w { "No PttUiEffects host: dropping RequestOverlayPermission" }
        }
    }
}

/**
 * The host-provided [PttUiEffects] for the screens under it. [PttUiEffects.None] by default, so a
 * screen composed standalone (a preview, a UI test) still compiles and runs — see [PttUiEffects]
 * for why the default is loud rather than silent.
 */
val LocalPttUiEffects: ProvidableCompositionLocal<PttUiEffects> =
    staticCompositionLocalOf { PttUiEffects.None }

/**
 * Collect one screen's [UiEvent] stream into its host.
 *
 * Called by every screen root with **its own** view model's flow, which is what replaced the hosts
 * each collecting all three flows themselves — with per-`Screen` roots there is no single place
 * above the screens left to do it, and a root that forgot this call would drop its own events
 * silently (known-issues #22 again). All three [UiEvent] variants are handled here rather than
 * filtered per feature, so an event a reducer starts emitting is never routed into the void:
 * `MainViewModel` emits `RequestMicPermission`, `SettingsViewModel` emits the save's `ShowMessage`
 * and `RequestOverlayPermission`, and `HistoryViewModel` emits nothing today.
 *
 * Keyed on both arguments: the flow is a per-view-model instance and the effects object a per-host
 * one, so this restarts on neither a recomposition nor a navigation push/pop — only on a genuinely
 * different screen/model pair, which is what keeps one collection per screen *entry*.
 */
@Composable
fun rememberPttUiEvents(events: Flow<UiEvent>, effects: PttUiEffects) {
    LaunchedEffect(events, effects) {
        events.collect { event ->
            when (event) {
                is UiEvent.ShowMessage -> effects.showMessage(event.messageRes)
                UiEvent.RequestMicPermission -> effects.requestMicPermission()
                UiEvent.RequestOverlayPermission -> effects.requestOverlayPermission()
            }
        }
    }
}
