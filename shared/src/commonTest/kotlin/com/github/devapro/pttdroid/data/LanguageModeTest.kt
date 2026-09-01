package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.LanguageMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageModeTest {

    @Test
    fun `system resolves to no override so the device locale wins`() {
        assertNull(LanguageMode.SYSTEM.languageTag())
    }

    @Test
    fun `an explicit choice maps to its bcp-47 tag`() {
        assertEquals("en", LanguageMode.ENGLISH.languageTag())
        assertEquals("ru", LanguageMode.RUSSIAN.languageTag())
        assertEquals("sr", LanguageMode.SERBIAN.languageTag())
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
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStorage("ESPERANTO"))
        assertEquals(LanguageMode.SYSTEM, LanguageMode.fromStorage("russian"))
    }
}
