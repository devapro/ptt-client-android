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
    /**
     * Builds and starts the underlying playback device.
     *
     * Returns `true` once audio written to [play] will actually be heard, `false` if setup
     * failed — a device that refuses the sample rate, an exception building the native track/line,
     * or one that built but never reached a running state. This used to return `Unit`, so a failed
     * setup was invisible: the caller could not tell "ready" from "silently broken", the user heard
     * nothing and saw a normal-looking UI, and nothing distinguished the two short of attaching a
     * debugger. `domain.PttController` folds a `false` here into `PttState.lastError` so the
     * failure reaches the user instead of only a log line nobody in production ever reads. Idempotent:
     * calling it again while already prepared returns `true` without rebuilding anything.
     */
    fun prepare(): Boolean
    fun play(pcm: ByteArray)

    /**
     * Cuts off whatever [play] has already fed the device: everything queued or buffered but not
     * yet audible is discarded, immediately, and the device stays ready for further [play] calls.
     *
     * [release] cannot stand in for this — it tears the device down for the whole session, and a
     * history replay must be able to stop mid-record while live audio keeps flowing through the
     * same track. Idempotent and safe to call at any time, including before [prepare] (then a pure
     * no-op). The one caller today is `HistoryPlayback.stop`, which needs exactly this: cancelling
     * the pump alone leaves whatever the player has already buffered — its own queue plus the
     * device's — to play out audibly from a record the user just deleted (or stopped) in the
     * History screen.
     */
    fun stop()

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
