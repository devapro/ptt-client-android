package com.github.devapro.pttdroid.audio

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
    fun release()
}
