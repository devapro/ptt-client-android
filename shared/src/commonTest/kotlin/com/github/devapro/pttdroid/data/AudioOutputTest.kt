package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.AudioOutput
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioOutputTest {

    @Test
    fun `a fresh install plays out of the loudspeaker`() {
        // The whole point of the setting: USAGE_VOICE_COMMUNICATION quietly put a good number of
        // devices on the earpiece at call volume, and a walkie-talkie is a loudspeaker.
        assertEquals(AudioOutput.SPEAKER, AudioOutput.DEFAULT)
        assertEquals(AudioOutput.SPEAKER, AppSettings().audioOutput)
    }

    @Test
    fun `stored values round-trip`() {
        AudioOutput.entries.forEach {
            assertEquals(it, AudioOutput.fromStorage(it.name))
        }
    }

    @Test
    fun `an absent or unrecognised stored value falls back to the speaker`() {
        // Settings outlive enum constants: a downgrade, or a renamed constant, must not crash
        // the whole settings flow on read — and must not land on the quiet route by accident.
        assertEquals(AudioOutput.SPEAKER, AudioOutput.fromStorage(null))
        assertEquals(AudioOutput.SPEAKER, AudioOutput.fromStorage(""))
        assertEquals(AudioOutput.SPEAKER, AudioOutput.fromStorage("BLUETOOTH"))
        assertEquals(AudioOutput.SPEAKER, AudioOutput.fromStorage("earpiece"))
    }
}
