package com.github.devapro.pttdroid.ui

import com.github.devapro.pttdroid.domain.ConnectionStatus
import com.github.devapro.pttdroid.domain.PttState
import com.github.devapro.pttdroid.shared.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * How a [PttState] presents itself — colour and wording — in exactly one place.
 *
 * The app screen, the floating bubble, the home-screen widget and the notification are four
 * views of one session. When each mapped state to a colour on its own they drifted: the bubble
 * had four states, the widget four different ones, the big button three, and none of them showed
 * that a talk request was still in flight. A colour that means "on air" on one surface has to
 * mean "on air" on all of them, so the mapping lives here.
 *
 * Colours follow radio convention, not traffic-light convention: red is *transmitting*, not
 * "stop", and an incoming transmission is blue rather than green — green here means "the channel
 * is yours", which is precisely what someone else holding the floor is not.
 *
 * Held as raw ARGB because the three renderers want three different colour types: Compose's
 * `Color`, `android.graphics.Paint` on the overlay `Canvas`, and Glance's `ColorProvider`.
 * Kept free of Compose UI and of Android so [of] can be unit-tested.
 */
enum class PttUiStatus(
    val argb: Long,
    val labelRes: StringResource,
    val captionRes: StringResource,
) {
    /** No transport. Nothing on this screen will do anything until it comes back. */
    OFFLINE(0xFF64748B, Res.string.status_disconnected, Res.string.ptt_cap_offline),

    /** Socket in progress, including every automatic backoff retry. */
    CONNECTING(0xFFF59E0B, Res.string.status_connecting, Res.string.ptt_cap_linking),

    /** Connected and the floor is free — the only state in which pressing does anything. */
    READY(0xFF22C55E, Res.string.status_ready, Res.string.ptt_cap_talk),

    /** `talk_request` sent, server has not answered. Speaking now would clip the first word. */
    REQUESTING(0xFFF59E0B, Res.string.status_requesting, Res.string.ptt_cap_wait),

    /** The server granted us the floor; the microphone is open. */
    TRANSMITTING(0xFFEF4444, Res.string.status_transmitting, Res.string.ptt_cap_on_air),

    /** Somebody else holds the floor. Our button is inert until they release it. */
    RECEIVING(0xFF38BDF8, Res.string.status_receiving, Res.string.ptt_cap_busy),
    ;

    /** True while audio is moving in either direction — the states worth animating. */
    val isOnAir: Boolean get() = this == TRANSMITTING || this == RECEIVING

    /** True when pressing the PTT control can actually start a transmission. */
    val canTalk: Boolean get() = this == READY

    /**
     * True when the control is live — it can start a transmission, or is already holding one.
     *
     * Distinct from [canTalk] on purpose. Tying the button's enabled flag to [canTalk] alone
     * greys it out at the exact moment the floor is granted, and — because a disabled button
     * drops its gesture detector — the press is torn down mid-hold and the release never fires,
     * stranding the floor with the microphone open. Whoever is holding the floor still needs a
     * live button to let go of.
     */
    val isControlLive: Boolean
        get() = this == READY || this == REQUESTING || this == TRANSMITTING

    companion object {

        /**
         * Order matters. Holding the floor outranks everything (we are audible, say so); a
         * pending request outranks the idle view of the same connected socket; and no transport
         * outranks any floor bookkeeping left over from before it dropped.
         */
        fun of(state: PttState): PttUiStatus = when {
            state.isTransmitting -> TRANSMITTING
            !state.isConnected && state.status is ConnectionStatus.Connecting -> CONNECTING
            !state.isConnected -> OFFLINE
            state.isRequestingFloor -> REQUESTING
            state.isFloorHeldByOther -> RECEIVING
            else -> READY
        }
    }
}
