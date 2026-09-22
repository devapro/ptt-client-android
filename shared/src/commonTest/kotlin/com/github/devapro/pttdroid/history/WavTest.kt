package com.github.devapro.pttdroid.history

import com.github.devapro.pttdroid.audio.AudioConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The hand-rolled RIFF/PCM16LE codec: what it writes, it must read back byte for byte, and
 * anything that is not one of its own files — truncated, tampered, or a different shape of WAV —
 * must be refused with null rather than half-parsed into noise on the speaker.
 */
class WavTest {

    private fun pcm(size: Int = 3 * AudioConfig.FRAME_BYTES) =
        ByteArray(size) { (it % 251).toByte() }

    /** Little-endian write of an Int, mirroring the codec's own, to tamper with headers. */
    private fun ByteArray.putIntLE(at: Int, value: Int) {
        this[at] = (value and 0xFF).toByte()
        this[at + 1] = (value ushr 8 and 0xFF).toByte()
        this[at + 2] = (value ushr 16 and 0xFF).toByte()
        this[at + 3] = (value ushr 24 and 0xFF).toByte()
    }

    @Test
    fun `a payload round-trips through the header byte for byte`() {
        val original = pcm()
        val wav = Wav.toWav(original)

        assertEquals(original.size + 44, wav.size)
        assertContentEquals(original, Wav.pcmFromWav(wav))
    }

    @Test
    fun `the header describes the wire format it wraps`() {
        val original = pcm(1000)
        val wav = Wav.toWav(original)

        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        assertEquals("fmt ", wav.copyOfRange(12, 16).decodeToString())
        assertEquals("data", wav.copyOfRange(36, 40).decodeToString())
        assertEquals(wav.size - 8, wav.decodeLe32(4))
        assertEquals(16, wav.decodeLe32(16), "canonical 16-byte fmt chunk")
        assertEquals(1, wav.decodeLe16(20), "PCM")
        assertEquals(AudioConfig.CHANNEL_COUNT, wav.decodeLe16(22))
        assertEquals(AudioConfig.SAMPLE_RATE_HZ, wav.decodeLe32(24))
        assertEquals(
            AudioConfig.SAMPLE_RATE_HZ * AudioConfig.CHANNEL_COUNT * AudioConfig.BYTES_PER_SAMPLE,
            wav.decodeLe32(28),
        )
        assertEquals(1000, wav.decodeLe32(40), "the data chunk carries exactly the payload")
    }

    @Test
    fun `an empty payload is still a valid file`() {
        val wav = Wav.toWav(ByteArray(0))

        assertEquals(44, wav.size)
        assertEquals(0, Wav.pcmFromWav(wav)?.size)
    }

    @Test
    fun `anything shorter than a header is not a wav`() {
        assertNull(Wav.pcmFromWav(ByteArray(43)))
        assertNull(Wav.pcmFromWav(ByteArray(0)))
    }

    @Test
    fun `a tampered file is refused rather than half-parsed`() {
        val good = Wav.toWav(pcm(100))

        val badMagic = good.copyOf().apply { this[0] = 'X'.code.toByte() }
        assertNull(Wav.pcmFromWav(badMagic), "RIFF signature broken")

        val badWave = good.copyOf().apply { this[8] = 'X'.code.toByte() }
        assertNull(Wav.pcmFromWav(badWave), "WAVE signature broken")

        val extendedFmt = good.copyOf().apply { putIntLE(16, 18) }
        assertNull(Wav.pcmFromWav(extendedFmt), "a non-canonical fmt chunk shifts the data tag")

        val floatSamples = good.copyOf().apply { this[20] = 3 }
        assertNull(Wav.pcmFromWav(floatSamples), "IEEE-float payload is not our PCM")

        val otherRate = good.copyOf().apply { putIntLE(24, 44100) }
        assertNull(Wav.pcmFromWav(otherRate), "the codec only plays what the wire carries")

        val lyingSize = good.copyOf().apply { putIntLE(40, 101) }
        assertNull(Wav.pcmFromWav(lyingSize), "a data chunk longer than the file")

        val negativeSize = good.copyOf().apply { putIntLE(40, -1) }
        assertNull(Wav.pcmFromWav(negativeSize))
    }

    @Test
    fun `a declared data size shorter than the payload reads only the declared part`() {
        // Extra bytes after the declared chunk are padding, not audio; reading them would
        // append noise to the end of a record.
        val original = pcm(100)
        val wav = Wav.toWav(original).apply { putIntLE(40, 50) }

        assertContentEquals(original.copyOfRange(0, 50), Wav.pcmFromWav(wav))
    }

    private fun ByteArray.decodeLe16(at: Int): Int =
        (this[at].toInt() and 0xFF) or (this[at + 1].toInt() and 0xFF shl 8)

    private fun ByteArray.decodeLe32(at: Int): Int =
        decodeLe16(at) or (decodeLe16(at + 2) shl 16)
}
