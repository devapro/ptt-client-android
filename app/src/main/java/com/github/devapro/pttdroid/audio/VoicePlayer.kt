package com.github.devapro.pttdroid.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioTrack.PLAYSTATE_STOPPED
import android.media.AudioTrack.STATE_INITIALIZED
import timber.log.Timber

class VoicePlayer {
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private var audioTrack: AudioTrack? = null
    private var bufferSize = 0

    fun create() {
        val minRate = getMinRate()
        minRate?.let { sampleRate ->
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            bufferSize = sampleRate * (java.lang.Short.SIZE / java.lang.Byte.SIZE) * 4
            if (bufferSize < minBufferSize) bufferSize = minBufferSize
            audioTrack =
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize / 4,
                    AudioTrack.MODE_STREAM,
                    AudioTrack.WRITE_NON_BLOCKING
                )
            audioTrack?.apply {
                play()
            }
        }
    }

    fun play(bytes: ByteArray) {
        Timber.i("play ${bytes.size} - ${bytes[0]} ${bytes[1]}")
        if (audioTrack?.playState == PLAYSTATE_STOPPED) {
            Timber.w("PLAYER STOPPED!!!")
        }
        audioTrack?.apply {
            write(bytes, 0, bytes.size)
            Timber.i("write ${bytes.size}")
//            if (frame++ == 0){
//                audioTrack?.play()
//                audioTrack?.stop()
//            }
        }
    }

    fun stopPlay() {
        audioTrack
            ?.takeIf { it.state == STATE_INITIALIZED }
            ?.apply {
                if (playState != PLAYSTATE_STOPPED) {
                    stop()
                }
                release()
            }
    }


    private fun getMinRate(): Int? {
        val rates = arrayOf(8000, 11025, 16000, 22050, 44100)
        rates.forEach {
            val minBufferSize = AudioRecord.getMinBufferSize(
                it, channelConfig, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize != AudioRecord.ERROR &&
                minBufferSize != AudioRecord.ERROR_BAD_VALUE
            ) {
                return it
            }
        }
        return null
    }
}