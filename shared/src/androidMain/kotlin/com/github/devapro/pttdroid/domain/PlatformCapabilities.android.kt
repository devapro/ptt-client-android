package com.github.devapro.pttdroid.domain

/**
 * Android phones have both a loudspeaker and a handset receiver, and `:app`'s `VoicePlayer`
 * picks between them (`AudioAttributes` usage plus `AudioManager`'s communication device).
 *
 * Declared here rather than in `jvmCommonMain` alongside [canHostRelay] precisely because
 * desktop — the other target that compiles `jvmCommonMain` — answers this one differently.
 */
actual val canRouteAudioOutput: Boolean = true
