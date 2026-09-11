package com.github.devapro.pttdroid.audio

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Seams over the two Android audio classes so the domain layer can be unit-tested on the JVM
 * — `AudioRecord`/`AudioTrack` are unavailable outside an instrumented test.
 */
interface VoiceRecorderContract {
    /** Captured PCM frames, sized per `AudioConfig.FRAME_BYTES`. */
    val frames: ReceiveChannel<ByteArray>
    fun start()
    fun stop()
    fun release()
}

interface VoicePlayerContract {
    fun prepare()
    fun play(pcm: ByteArray)

    /**
     * Chooses the loudspeaker or the handset receiver.
     *
     * Remembered by the implementation across [prepare]/[release] rather than being re-applied
     * by the caller: on Android the route is baked into the `AudioTrack`'s `AudioAttributes`,
     * which are immutable once built, so a change here has to survive being re-applied every
     * time the track is rebuilt on reconnect. Safe to call before [prepare] and while playing.
     */
    fun setOutput(output: AudioOutput)

    /**
     * Playback gain as a linear 0..1 factor, [AppSettings.clampVolume]-ed by the caller.
     * Remembered across [prepare]/[release] for the same reason as [setOutput].
     */
    fun setVolume(volume: Float)

    fun release()
}
