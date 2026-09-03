package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.LanguageMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageModeTest {

    @Test
    fun `system carries no tag, so the platform locale is left alone`() {
        assertNull(LanguageMode.SYSTEM.tag)
    }

    @Test
    fun `every explicit choice carries its BCP-47 tag`() {
        assertEquals("en", LanguageMode.ENGLISH.tag)
        assertEquals("ru", LanguageMode.RUSSIAN.tag)
        assertEquals("sr", LanguageMode.SERBIAN.tag)
    }

    @Test
    fun `stored values round-trip`() {
        LanguageMode.entries.forEach {
            assertEquals(it, LanguageMode.fromStorage(it.name))
        }
    }

    @Test
    fun `an absent or unrecognised stored value falls back to system`() {
        // Settings outlive enum constants: a downgrade, or a renamed constant, must not crash
        // the whole settings flow on read.
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStorage(null))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStorage(""))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStorage("FRENCH"))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStorage("en"))
    }
}
