package com.github.devapro.pttdroid.ui.components

import androidx.compose.ui.graphics.Color
import com.github.devapro.pttdroid.ui.PttUiStatus

/**
 * The one place a [PttUiStatus] becomes a Compose colour.
 *
 * Moved here from `:shared`'s `ui/theme/Color.kt` in Phase 5 of the module-graph refactor when
 * [PTTButton] and [StatusCard] — its only two consumers — moved to `:core` with it. It stays a
 * single definition: `:shared`'s palette (`Color.kt`) derives its `Signal*` colours from
 * `PttUiStatus.argb` directly and does not define a `color` extension any more, so nothing can
 * drift between the two. Living in the module that uses it keeps it `internal` — the least
 * exposure that works; `:shared`'s ARGB values remain the single source of truth.
 */
internal val PttUiStatus.color: Color get() = Color(argb)
