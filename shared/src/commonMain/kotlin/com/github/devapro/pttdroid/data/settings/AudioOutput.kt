package com.github.devapro.pttdroid.data.settings

/**
 * Which of the phone's two earpieces received audio comes out of.
 *
 * Not a cosmetic choice. `AudioTrack` built with `USAGE_VOICE_COMMUNICATION` — which is what this
 * app used unconditionally, because it is a comms app and that is the honest description of the
 * stream — routes to the **handset receiver** on a good number of devices, at whatever the
 * voice-call volume happens to be, which is a separate and usually much quieter slider than the
 * one the volume rocker moves outside a call. The app was audible on the developer's phone and
 * nearly silent on other people's, with no control anywhere to fix it.
 *
 * A walkie-talkie is a loudspeaker by default: [SPEAKER] is the default here for the same reason
 * the physical thing sits on a table making noise. [EARPIECE] is for holding the phone to your
 * ear when the channel is not for the whole room.
 *
 * Desktop has no such distinction — see `domain/canRouteAudioOutput`, which is what hides the
 * choice there rather than offering one that does nothing.
 */
enum class AudioOutput {
    /** The loudspeaker, at media volume. */
    SPEAKER,

    /** The handset receiver you hold to your ear, at call volume. */
    EARPIECE,
    ;

    companion object {
        val DEFAULT: AudioOutput = SPEAKER

        /** Tolerates an unknown or absent stored value — settings outlive enum constants. */
        fun fromStorage(value: String?): AudioOutput =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
