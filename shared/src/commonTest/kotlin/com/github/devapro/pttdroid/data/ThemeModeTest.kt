package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeModeTest {

    @Test
    fun `system follows whatever the platform reports`() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemInDarkTheme = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemInDarkTheme = false))
    }

    @Test
    fun `an explicit choice overrides the platform in both directions`() {
        assertTrue(ThemeMode.DARK.isDark(systemInDarkTheme = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemInDarkTheme = true))
    }

    @Test
    fun `stored values round-trip`() {
        ThemeMode.entries.forEach {
            assertEquals(it, ThemeMode.fromStorage(it.name))
        }
    }

    @Test
    fun `an absent or unrecognised stored value falls back to system`() {
        // Settings outlive enum constants: a downgrade, or a renamed constant, must not crash
        // the whole settings flow on read.
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("SEPIA"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStorage("dark"))
    }
}
