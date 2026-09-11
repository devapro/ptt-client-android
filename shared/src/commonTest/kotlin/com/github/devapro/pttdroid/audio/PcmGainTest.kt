package com.github.devapro.pttdroid.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PcmGainTest {

    /** Little-endian, the wire order — see `AudioConfig.ENCODING_NAME`. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, sample ->
            out[i * 2] = (sample and 0xFF).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(pcm: ByteArray): List<Int> =
        (0 until pcm.size / 2).map {
            ((pcm[it * 2 + 1].toInt() shl 8) or (pcm[it * 2].toInt() and 0xFF)).toShort().toInt()
        }

    @Test
    fun `full gain hands back the very same array`() {
        // The default, and therefore the frame-by-frame common case: it must not allocate.
        val input = pcm(1000, -1000)
        assertSame(input, PcmGain.scale(input, 1f))
        assertSame(input, PcmGain.scale(input, 1.5f))
    }

    @Test
    fun `half gain halves every sample, sign included`() {
        val scaled = samples(PcmGain.scale(pcm(1000, -1000, 0, 32_767), 0.5f))
        assertEquals(listOf(500, -500, 0, 16_383), scaled)
    }

    @Test
    fun `zero gain is silence, not an untouched buffer`() {
        assertContentEquals(ByteArray(8), PcmGain.scale(pcm(32_767, -32_768, 1234, -1), 0f))
    }

    @Test
    fun `the extremes of the sample range survive attenuation`() {
        // -32768 * 0.5 is exactly representable; the clamp exists for the other direction and
        // must not truncate anything legal on the way past.
        val scaled = samples(PcmGain.scale(pcm(-32_768, 32_767), 0.5f))
        assertEquals(listOf(-16_384, 16_383), scaled)
    }

    @Test
    fun `an odd trailing byte is carried through rather than dropped`() {
        // Cannot happen with whole PCM16 frames; if it ever does, the frame must not shrink.
        val input = pcm(2000) + byteArrayOf(7)
        val scaled = PcmGain.scale(input, 0.5f)
        assertEquals(input.size, scaled.size)
        assertEquals(7, scaled.last().toInt())
    }
}
