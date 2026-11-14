package com.aplusconnect.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPhaseTest {

    private fun phase(
        enabled: Boolean = true,
        hasHost: Boolean = true,
        isConnected: Boolean = false,
        mirroring: Boolean = false,
        asked: Boolean = false,
        state: String = "Not connected",
    ) = connectionPhase(enabled, hasHost, isConnected, mirroring, asked, state)

    @Test
    fun `kill switch off wins over everything`() {
        assertEquals(ConnectionPhase.Off, phase(enabled = false, isConnected = true))
    }

    @Test
    fun `disconnected and not trying never says connecting (audit B2)`() {
        val p = phase(state = "Disconnected by the computer")
        assertEquals(ConnectionPhase.NotConnected, p)
        assertFalse(p.breathes)
    }

    @Test
    fun `service dialing reads as connecting and breathes`() {
        val p = phase(state = "Connecting to 192.168.1.39…")
        assertEquals(ConnectionPhase.Connecting, p)
        assertTrue(p.breathes)
        assertEquals(ConnectionPhase.Connecting, phase(state = "Disconnected · retrying"))
    }

    @Test
    fun `connected is still, mirroring offers stop`() {
        assertFalse(phase(isConnected = true).breathes)
        assertEquals(PrimaryAction.StopMirroring, phase(isConnected = true, mirroring = true).primaryAction)
    }

    @Test
    fun `no stored computer asks to scan`() {
        assertEquals(PrimaryAction.ScanQr, phase(hasHost = false).primaryAction)
    }

    @Test
    fun `computer off waits without breathing`() {
        val p = phase(state = "The computer is off · waiting")
        assertEquals(ConnectionPhase.ComputerOff, p)
        assertFalse(p.breathes)
    }

    @Test
    fun `pairing code is grouped in threes`() {
        assertEquals("482 913", groupCode("482913"))
    }

    @Test
    fun `host and port use the stored port (audit B3)`() {
        assertEquals("192.168.1.39:25000", hostPort("192.168.1.39", 25000))
        assertEquals("", hostPort("", 24443))
    }

    @Test
    fun `directions are words, not arrows`() {
        assertEquals("From computer", directionLabel("pc_to_phone"))
        assertEquals("To computer", directionLabel("phone_to_pc"))
        assertNull(directionLabel(null))
    }

    @Test
    fun `bytes are human`() {
        assertEquals("512 B", humanBytes(512))
        assertEquals("1.5 MB", humanBytes(1_572_864))
        assertEquals("118 GB", humanBytes(118L * 1024 * 1024 * 1024))
    }
}
