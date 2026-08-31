package com.github.devapro.pttdroid.domain

/**
 * iOS has no foreground-service concept either — same as desktop
 * ([DesktopPttSessionLauncher]), just calls [PttController.start]/[stop] directly.
 *
 * Unlike desktop, "the app keeps running while backgrounded" is not automatic here: staying
 * connected and recording after the user leaves the app needs `UIBackgroundModes: audio` (set in
 * `iosApp/iosApp/Info.plist`) *and* an active `AVAudioSession` category that requests background
 * audio. As of Phase 7b, `audio/IosAudio.kt`'s `IosVoiceRecorder`/`IosVoicePlayer` configure and
 * activate exactly that category (`AVAudioSessionCategoryPlayAndRecord` +
 * `AVAudioSessionModeVoiceChat`) themselves, on `start()`/`prepare()` — so background operation is
 * real as of this phase, contingent on a real device/Xcode run actually exercising it (this
 * machine can only frontend-compile the Kotlin, not verify the OS actually keeps the process
 * alive backgrounded — see the Phase 7b report).
 */
internal class IosPttSessionLauncher(private val controller: PttController) : PttSessionLauncher {
    override fun start() = controller.start()
    override fun stop() = controller.stop()
}
