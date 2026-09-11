package com.github.devapro.pttdroid.domain

/**
 * A desktop has one playback device, chosen in the operating system's own sound settings, and
 * `javax.sound.sampled` gives `DesktopVoicePlayer` nothing to switch between — see that class's
 * `setOutput`. The speaker/earpiece control is omitted here rather than shown inert.
 */
actual val canRouteAudioOutput: Boolean = false
