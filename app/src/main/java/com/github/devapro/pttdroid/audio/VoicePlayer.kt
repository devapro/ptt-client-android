package com.github.devapro.pttdroid.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import timber.log.Timber

/**
 * Streams received PCM to the speaker.
 *
 * The old class released the `AudioTrack` in `stopPlay()` but left the field non-null, so the
 * next incoming frame wrote to a released track. [release] now nulls it, and [prepare] is
 * idempotent so repeated reconnects cannot leak tracks.
 *
 * ## Why this class talks to `AudioManager` at all
 *
 * It used to build one `AudioTrack`, always with `USAGE_VOICE_COMMUNICATION` — the honest
 * description of the stream, and the wrong default. That usage puts playback on
 * `STREAM_VOICE_CALL`, which on a good number of devices routes to the **handset receiver**
 * rather than the loudspeaker, at the call volume rather than the media volume. The app was
 * loud on some phones and a whisper held to the ear on others, with nothing anywhere to change
 * it. A walkie-talkie is a loudspeaker by default.
 *
 * So the route is now a choice, and each side of it is a different stream, not just a different
 * device. Both use `CONTENT_TYPE_SPEECH`; what differs is the usage and the process's audio mode:
 *
 * - **[AudioOutput.SPEAKER]** (the default) — `USAGE_MEDIA`, so `STREAM_MUSIC`; `MODE_NORMAL`
 *   with no communication device claimed.
 * - **[AudioOutput.EARPIECE]** — `USAGE_VOICE_COMMUNICATION`, so `STREAM_VOICE_CALL`;
 *   `MODE_IN_COMMUNICATION` with the communication device pinned to the built-in earpiece.
 *
 * `USAGE_MEDIA` is deliberate for the speaker case and not merely "louder": it is what makes the
 * volume rocker adjust *this* app while it is playing, keeps the system out of call mode (which
 * ducks other apps and relabels the volume UI), and reaches an A2DP headset in music quality
 * rather than an HFP headset at 8 kHz. Echo cancellation is what `USAGE_VOICE_COMMUNICATION`
 * buys and it is not needed here: PTT is half-duplex, so the microphone is never open while
 * this is playing.
 *
 * `MODE_IN_COMMUNICATION` for the earpiece case *is* a process-wide side effect, which is why
 * [release] undoes it and only if this class was the thing that set it.
 */
class VoicePlayer(context: Context) : VoicePlayerContract {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var audioTrack: AudioTrack? = null

    /**
     * Remembered rather than passed to [prepare]: `AudioAttributes` are immutable once the
     * track is built, so a route change means rebuilding it, and every rebuild — including the
     * ones a reconnect causes, which nobody re-applies a preference around — has to come back
     * with the same answer.
     */
    private var output: AudioOutput = AudioOutput.DEFAULT
    private var volume: Float = AppSettings.DEFAULT_PLAYBACK_VOLUME

    /** True only while we hold `MODE_IN_COMMUNICATION`, so [release] restores nothing it did not take. */
    private var communicationRouteHeld = false

    override fun prepare() {
        audioTrack?.let { return }

        val minBuffer = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE_HZ,
            AndroidAudioFormat.OUT_CHANNEL_MASK,
            AndroidAudioFormat.PCM_ENCODING,
        )
        if (minBuffer == AudioTrack.ERROR || minBuffer == AudioTrack.ERROR_BAD_VALUE) {
            Timber.e("Speaker does not support %d Hz mono PCM16", AudioConfig.SAMPLE_RATE_HZ)
            return
        }

        applyRoute()

        val bufferSize = maxOf(minBuffer, AudioConfig.FRAME_BYTES * 4)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usageFor(output))
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.PCM_ENCODING)
                        .setSampleRate(AudioConfig.SAMPLE_RATE_HZ)
                        .setChannelMask(AndroidAudioFormat.OUT_CHANNEL_MASK)
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

        runCatching { track.setVolume(volume) }
            .onFailure { Timber.w(it, "AudioTrack.setVolume(%f) rejected", volume) }

        runCatching { track.play() }
            .onFailure {
                Timber.e(it, "AudioTrack.play failed")
                track.release()
                return
            }

        Timber.i(
            "AudioTrack ready: %d Hz, buffer %d bytes, out=%s",
            AudioConfig.SAMPLE_RATE_HZ,
            bufferSize,
            output,
        )
        audioTrack = track
    }

    /**
     * Rebuilds the track when the route changes while it is playing — the attributes that carry
     * the route cannot be changed on a live `AudioTrack`. A gap of a frame or two at the moment
     * the user taps is the cost, and it is the moment they are least likely to notice one.
     */
    override fun setOutput(output: AudioOutput) {
        if (this.output == output) return
        this.output = output
        if (audioTrack == null) {
            // Nothing playing yet: applied for real by the next prepare().
            return
        }
        release()
        prepare()
    }

    override fun setVolume(volume: Float) {
        val clamped = AppSettings.clampVolume(volume)
        if (this.volume == clamped) return
        this.volume = clamped
        val track = audioTrack ?: return
        runCatching { track.setVolume(clamped) }
            .onFailure { Timber.w(it, "AudioTrack.setVolume(%f) rejected", clamped) }
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
        releaseCommunicationRoute()
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

    private fun usageFor(output: AudioOutput): Int = when (output) {
        AudioOutput.SPEAKER -> AudioAttributes.USAGE_MEDIA
        AudioOutput.EARPIECE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
    }

    /**
     * The half of the routing that the attributes cannot express.
     *
     * `USAGE_VOICE_COMMUNICATION` on its own is not enough to *guarantee* the receiver — which
     * device `STREAM_VOICE_CALL` lands on is the device manufacturer's business until something
     * says otherwise. `setCommunicationDevice` (API 31) says otherwise; below that the only
     * lever is the deprecated speakerphone flag, which is why it is still here.
     */
    private fun applyRoute() {
        when (output) {
            AudioOutput.SPEAKER -> releaseCommunicationRoute()

            AudioOutput.EARPIECE -> runCatching {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                communicationRouteHeld = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val earpiece = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                    if (earpiece == null) {
                        Timber.w("No built-in earpiece on this device; leaving the default route")
                    } else if (!audioManager.setCommunicationDevice(earpiece)) {
                        Timber.w("setCommunicationDevice(earpiece) refused")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
            }.onFailure { Timber.w(it, "Could not route playback to the earpiece") }
        }
    }

    /** Puts the process-wide audio mode back exactly when we were the ones who moved it. */
    private fun releaseCommunicationRoute() {
        if (!communicationRouteHeld) return
        communicationRouteHeld = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }.onFailure { Timber.w(it, "Could not restore the normal audio mode") }
    }
}
