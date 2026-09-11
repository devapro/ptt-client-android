package com.github.devapro.pttdroid.domain

/**
 * `InternalPttServer` lives in `:shared`'s `jvmCommonMain` (it embeds a Ktor CIO server) and has
 * no iOS actual — iOS cannot see the class at all. See `ui/SettingsScreen.kt`'s `canHostRelay`
 * parameter: this is what makes the "Host a relay on this device" row disappear entirely on iOS
 * rather than showing disabled.
 */
actual val canHostRelay: Boolean = false

/**
 * iPhones have the same loudspeaker/receiver split Android phones do, and `IosAudioSession`
 * (`audio/IosAudio.kt`) picks between them through the `AVAudioSession` category options and
 * `overrideOutputAudioPort`. An `AVAudioSessionCategoryPlayAndRecord` session defaults to the
 * *receiver*, which is exactly the "why is this so quiet" symptom this control exists to fix.
 */
actual val canRouteAudioOutput: Boolean = true
