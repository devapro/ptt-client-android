package com.github.devapro.pttdroid.data

import com.github.devapro.pttdroid.data.settings.AppSettings
import com.github.devapro.pttdroid.data.settings.ServerMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Restoring the mode is the one part of this change that can break an install silently: read an
 * existing configured relay as "Default" and the app quietly starts dialling somewhere else.
 */
class ServerModeTest {

    @Test
    fun `a stored mode is used as written`() {
        assertEquals(ServerMode.CUSTOM, ServerMode.restore("CUSTOM", null, null))
        assertEquals(ServerMode.DEFAULT, ServerMode.restore("DEFAULT", "relay.local", 9000))
    }

    @Test
    fun `a fresh install with nothing stored is Default`() {
        assertEquals(ServerMode.DEFAULT, ServerMode.restore(null, null, null))
    }

    @Test
    fun `an install that predates the setting keeps its configured relay`() {
        assertEquals(ServerMode.CUSTOM, ServerMode.restore(null, "relay.local", 8000))
        assertEquals(ServerMode.CUSTOM, ServerMode.restore(null, AppSettings.DEFAULT_HOST, 9000))
    }

    @Test
    fun `an install that never moved off the built-in address is Default`() {
        assertEquals(
            ServerMode.DEFAULT,
            ServerMode.restore(null, AppSettings.DEFAULT_HOST, AppSettings.DEFAULT_PORT),
        )
    }

    @Test
    fun `an unrecognised stored value falls back the same way an absent one does`() {
        // Settings outlive enum constants; a value from a newer build must not crash an older one.
        assertEquals(ServerMode.DEFAULT, ServerMode.restore("SOMETHING_ELSE", null, null))
        assertEquals(ServerMode.CUSTOM, ServerMode.restore("SOMETHING_ELSE", "relay.local", 8000))
    }
}
