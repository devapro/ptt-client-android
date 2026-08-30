package com.github.devapro.pttdroid.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import timber.log.Timber

/**
 * Streams received PCM to the speaker.
 *
 * The old class released the `AudioTrack` in `stopPlay()` but left the field non-null, so the
 * next incoming frame wrote to a released track. [release] now nulls it, and [prepare] is
 * idempotent so repeated reconnects cannot leak tracks.
 */
class VoicePlayer : VoicePlayerContract {

    private var audioTrack: AudioTrack? = null

    override fun prepare() {
        audioTrack?.let { return }

        val minBuffer = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AudioConfig.OUT_CHANNEL_MASK,
            AudioConfig.PCM_ENCODING,
        )
        if (minBuffer == AudioTrack.ERROR || minBuffer == AudioTrack.ERROR_BAD_VALUE) {
            Timber.e("Speaker does not support %d Hz mono PCM16", AudioConfig.SAMPLE_RATE_HZ)
            return
        }

        val bufferSize = maxOf(minBuffer, AudioConfig.FRAME_BYTES * 4)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // This is a comms app, not media playback: routing and volume should
                        // follow the voice-call stream.
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioConfig.PCM_ENCODING)
                        .setSampleRate(AudioConfig.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioConfig.OUT_CHANNEL_MASK)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Timber.e(e, "Could not create AudioTrack")
            return
        }

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Timber.e("AudioTrack failed to initialise (state=%d)", track.state)
            track.release()
            return
        }

        runCatching { track.play() }
            .onFailure {
                Timber.e(it, "AudioTrack.play failed")
                track.release()
                return
            }

        Timber.i("AudioTrack ready: %d Hz, buffer %d bytes", AudioConfig.SAMPLE_RATE_HZ, bufferSize)
        audioTrack = track
    }

    /** Writes one received frame. No logging here — this runs per audio frame. */
    override fun play(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val track = audioTrack ?: return
        runCatching { track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING) }
            .onFailure { Timber.d("AudioTrack.write failed: %s", it.toString()) }
    }

    /** Idempotent; safe to call more than once and from either side of a reconnect. */
    override fun release() {
        val track = audioTrack ?: return
        audioTrack = null
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED &&
                track.playState != AudioTrack.PLAYSTATE_STOPPED
            ) {
                track.stop()
            }
            track.release()
        }.onFailure { Timber.d("AudioTrack release failed: %s", it.toString()) }
    }
}
