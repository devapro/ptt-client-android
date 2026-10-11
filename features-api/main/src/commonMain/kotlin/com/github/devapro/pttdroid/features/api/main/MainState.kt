package com.github.devapro.pttdroid.features.api.main

import com.github.devapro.pttdroid.domain.PttState

/**
 * Everything the Main screen renders.
 *
 * Main deliberately renders just these two fields. `ptt` is mirrored in from `PttController`
 * (the service, overlay and widget share that one source), and
 * [micPermissionGranted] is the runtime grant the host reports through
 * `MainViewModel.onMicPermissionResult` — the microphone gate every press runs through.
 * Everything else that looks "main-ish" lives elsewhere on purpose: navigation is Voyager
 * stack state held by the `Navigator` (never screen state), the settings form lives in
 * `SettingsState`, and the history list/unread badge is served by
 * `:shared`'s `HistoryBadge` so this slice never needs a `:features:main -> :features:history`
 * edge.
 */
data class MainState(
    val ptt: PttState = PttState(),
    val micPermissionGranted: Boolean = false,
)
