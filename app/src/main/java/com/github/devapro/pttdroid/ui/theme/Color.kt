package com.github.devapro.pttdroid.ui.theme

import androidx.compose.ui.graphics.Color
import com.github.devapro.pttdroid.ui.PttUiStatus

/**
 * The palette.
 *
 * Dark is the designed scheme and light is its mirror, not the other way round: this app holds a
 * foreground service open for hours and gets glanced at outdoors and in the dark at least as
 * often as in a bright room. The greys are a cool, low-chroma ladder so that the six status
 * accents in [PttUiStatus] are the only saturated things on the screen — a walkie-talkie should
 * have exactly one thing shouting at you, and it should be the state of the channel.
 */

// --- dark (default) ---------------------------------------------------------------------------

internal val Ink = Color(0xFF0B0F14)
internal val InkSurface = Color(0xFF121922)
internal val InkSurfaceHigh = Color(0xFF1A232E)
internal val InkOutline = Color(0xFF2B3846)
internal val Chalk = Color(0xFFE6EDF3)
internal val ChalkDim = Color(0xFF95A5B6)

// --- light ------------------------------------------------------------------------------------

internal val Paper = Color(0xFFF3F6F9)
internal val PaperSurface = Color(0xFFFFFFFF)
internal val PaperSurfaceHigh = Color(0xFFE7EDF3)
internal val PaperOutline = Color(0xFFC6D2DE)
internal val Graphite = Color(0xFF10161D)
internal val GraphiteDim = Color(0xFF556474)

// --- status accents ---------------------------------------------------------------------------

/** The one place a [PttUiStatus] becomes a Compose colour. */
internal val PttUiStatus.color: Color get() = Color(argb)

internal val SignalGreen = Color(PttUiStatus.READY.argb)
internal val SignalAmber = Color(PttUiStatus.CONNECTING.argb)
internal val SignalRed = Color(PttUiStatus.TRANSMITTING.argb)
internal val SignalSky = Color(PttUiStatus.RECEIVING.argb)
internal val SignalSlate = Color(PttUiStatus.OFFLINE.argb)

internal val ErrorDark = Color(0xFFF87171)
internal val ErrorLight = Color(0xFFB91C1C)

internal val ErrorSurfaceDark = Color(0xFF3A1416)
internal val ErrorSurfaceLight = Color(0xFFFDE8E8)

// The rest of the Material 3 surface ladder. Left unset, every one of these silently falls back
// to the baseline purple scheme — which is how a lavender chip turned up inside a segmented
// control on an otherwise green-and-slate screen.
internal val InkLowest = Color(0xFF070A0E)
internal val InkLow = Color(0xFF0F151C)
internal val InkHighest = Color(0xFF222D3A)

internal val PaperLowest = Color(0xFFFFFFFF)
internal val PaperLow = Color(0xFFF8FAFC)
internal val PaperHighest = Color(0xFFDCE4EC)
