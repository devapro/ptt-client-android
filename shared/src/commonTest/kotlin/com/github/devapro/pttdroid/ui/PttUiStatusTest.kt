package com.github.devapro.pttdroid.ui

import com.github.devapro.pttdroid.domain.ConnectionStatus
import com.github.devapro.pttdroid.domain.PttState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mapping four surfaces share. Getting it wrong is not a cosmetic bug: the floating bubble
 * and the big button would disagree about whether the channel is free, and the only readout the
 * user has while not looking at the app would be lying.
 */
class PttUiStatusTest {

    private val connected = PttState(status = ConnectionStatus.Connected)

    @Test
    fun `a fresh state is offline`() {
        assertEquals(PttUiStatus.OFFLINE, PttUiStatus.of(PttState()))
    }

    @Test
    fun `connecting is distinct from offline`() {
        val state = PttState(status = ConnectionStatus.Connecting)
        assertEquals(PttUiStatus.CONNECTING, PttUiStatus.of(state))
    }

    @Test
    fun `a connected idle channel is ready`() {
        assertEquals(PttUiStatus.READY, PttUiStatus.of(connected))
    }

    @Test
    fun `a pending talk request is visible as its own state`() {
        val state = connected.copy(isRequestingFloor = true)
        assertEquals(PttUiStatus.REQUESTING, PttUiStatus.of(state))
    }

    @Test
    fun `holding the floor outranks everything else`() {
        val state = connected.copy(
            isTransmitting = true,
            isRequestingFloor = true,
            isFloorHeldByOther = true,
        )
        assertEquals(PttUiStatus.TRANSMITTING, PttUiStatus.of(state))
    }

    @Test
    fun `someone else holding the floor is receiving`() {
        val state = connected.copy(isFloorHeldByOther = true, floorHolderName = "Bob")
        assertEquals(PttUiStatus.RECEIVING, PttUiStatus.of(state))
    }

    @Test
    fun `losing the transport outranks stale floor bookkeeping`() {
        val state = PttState(
            status = ConnectionStatus.Disconnected,
            isRequestingFloor = true,
            isFloorHeldByOther = true,
        )
        assertEquals(PttUiStatus.OFFLINE, PttUiStatus.of(state))
    }

    @Test
    fun `ready is the only state that offers a press`() {
        assertTrue(PttUiStatus.READY.canTalk)
        PttUiStatus.entries.filter { it != PttUiStatus.READY }.forEach {
            assertFalse(it.canTalk, "$it must not offer a press")
        }
    }

    @Test
    fun `the ui only offers a press when the domain would accept one`() {
        listOf(
            PttState(),
            PttState(status = ConnectionStatus.Connecting),
            connected,
            connected.copy(isFloorHeldByOther = true),
        ).forEach { state ->
            if (PttUiStatus.of(state).canTalk) {
                assertTrue(state.canTalk, "offered a press the controller would reject: $state")
            }
        }
    }

    @Test
    fun `the control stays live while we hold the floor`() {
        // Regression: tying the button's enabled flag to canTalk greyed it out the moment the
        // grant arrived, which tore down the in-flight press and left the floor held forever.
        assertTrue(PttUiStatus.TRANSMITTING.isControlLive)
        assertTrue(PttUiStatus.REQUESTING.isControlLive)
        assertTrue(PttUiStatus.READY.isControlLive)
    }

    @Test
    fun `the control is dead when no press of ours could matter`() {
        listOf(PttUiStatus.OFFLINE, PttUiStatus.CONNECTING, PttUiStatus.RECEIVING).forEach {
            assertFalse(it.isControlLive, "$it must not present a live control")
        }
    }

    @Test
    fun `only transmitting and receiving animate`() {
        assertEquals(
            setOf(PttUiStatus.TRANSMITTING, PttUiStatus.RECEIVING),
            PttUiStatus.entries.filter { it.isOnAir }.toSet(),
        )
    }

    @Test
    fun `states are distinguishable by colour`() {
        // Connecting and requesting are both "something is pending", and deliberately share
        // amber; every other state has to be tellable apart at a glance.
        assertEquals(
            PttUiStatus.entries.size - 1,
            PttUiStatus.entries.map { it.argb }.toSet().size,
        )
    }
}
