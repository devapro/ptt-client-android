package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.audio.AudioConfig

/**
 * Minimal WAV (RIFF/PCM16LE) codec for the history files.
 *
 * The wire format is already PCM16LE mono at [AudioConfig.SAMPLE_RATE_HZ], so [toWav] only has
 * to prepend a header; [pcmFromWav] strips one back off. Written by hand rather than pulled in
 * as a dependency because the file is a fixed-format container — the whole codec is arithmetic
 * over an 88-byte preamble.
 */
object Wav {
    private const val HEADER_BYTES = 44

    fun toWav(pcm: ByteArray, sampleRate: Int = AudioConfig.SAMPLE_RATE_HZ): ByteArray {
        val byteRate = sampleRate * AudioConfig.CHANNEL_COUNT * AudioConfig.BYTES_PER_SAMPLE
        val out = ByteArray(HEADER_BYTES + pcm.size)
        writeAscii(out, 0, "RIFF")
        writeLe32(out, 4, out.size - 8)
        writeAscii(out, 8, "WAVE")
        writeAscii(out, 12, "fmt ")
        writeLe32(out, 16, 16) // fmt chunk size
        writeLe16(out, 20, 1) // PCM
        writeLe16(out, 22, AudioConfig.CHANNEL_COUNT)
        writeLe32(out, 24, sampleRate)
        writeLe32(out, 28, byteRate)
        writeLe16(out, 32, AudioConfig.CHANNEL_COUNT * AudioConfig.BYTES_PER_SAMPLE)
        writeLe16(out, 34, AudioConfig.BYTES_PER_SAMPLE * 8)
        writeAscii(out, 36, "data")
        writeLe32(out, 40, pcm.size)
        pcm.copyInto(out, HEADER_BYTES)
        return out
    }

    /** The PCM payload of a well-formed header, or null if [wav] is not a WAV we can play. */
    fun pcmFromWav(wav: ByteArray): ByteArray? {
        if (wav.size < HEADER_BYTES) return null
        if (readAscii(wav, 0) != "RIFF" || readAscii(wav, 8) != "WAVE") return null
        if (readAscii(wav, 12) != "fmt ") return null
        if (readAscii(wav, 36) != "data") return null
        // Only the canonical layout this class writes is accepted — a 16-byte fmt chunk, a
        // 44-byte preamble, PCM, mono, and the sample rate we put on the wire.
        if (readLe32(wav, 16) != 16) return null
        if (readLe16(wav, 20) != 1) return null
        if (readLe16(wav, 22) != AudioConfig.CHANNEL_COUNT) return null
        if (readLe32(wav, 24) != AudioConfig.SAMPLE_RATE_HZ) return null
        val dataSize = readLe32(wav, 40)
        if (dataSize < 0 || HEADER_BYTES.toLong() + dataSize > wav.size) return null
        return wav.copyOfRange(HEADER_BYTES, HEADER_BYTES + dataSize)
    }

    private fun writeLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = (v ushr 8 and 0xFF).toByte()
    }

    private fun writeLe32(b: ByteArray, at: Int, v: Int) {
        writeLe16(b, at, v and 0xFFFF)
        writeLe16(b, at + 2, v ushr 16 and 0xFFFF)
    }

    private fun readLe16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or (b[at + 1].toInt() and 0xFF shl 8)

    private fun readLe32(b: ByteArray, at: Int): Int =
        readLe16(b, at) or (readLe16(b, at + 2) shl 16)

    private fun writeAscii(b: ByteArray, at: Int, s: String) {
        s.encodeToByteArray().copyInto(b, at)
    }

    private fun readAscii(b: ByteArray, at: Int): String =
        b.copyOfRange(at, at + 4).decodeToString()
}
