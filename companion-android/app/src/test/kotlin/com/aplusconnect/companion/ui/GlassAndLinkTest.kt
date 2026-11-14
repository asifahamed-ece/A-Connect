package com.aplusconnect.companion.ui

import com.aplusconnect.companion.ui.theme.BAR_CLARITY_DEFAULT
import com.aplusconnect.companion.ui.theme.barAlphaFor
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassAndLinkTest {
    @Test fun solidAtZeroClarity() = assertEquals(0.96f, barAlphaFor(0, reduceTransparency = false), 0.001f)

    @Test fun clearestKeepsReadableTint() = assertEquals(0.28f, barAlphaFor(100, reduceTransparency = false), 0.001f)

    @Test fun defaultIsSixty() = assertEquals(60, BAR_CLARITY_DEFAULT)

    @Test fun reduceTransparencyAlwaysSolid() = assertEquals(1f, barAlphaFor(100, reduceTransparency = true), 0f)

    @Test fun outOfRangeIsClamped() = assertEquals(0.28f, barAlphaFor(250, reduceTransparency = false), 0.001f)

    @Test fun connectedAndMirroringSendBits() {
        assertEquals(LinkMode.Live, linkModeFor(ConnectionPhase.Connected))
        assertEquals(LinkMode.Live, linkModeFor(ConnectionPhase.Mirroring))
    }

    @Test fun connectingSendsHandshake() = assertEquals(LinkMode.Pending, linkModeFor(ConnectionPhase.Connecting))

    @Test fun disconnectedStatesAreQuiet() {
        listOf(ConnectionPhase.Off, ConnectionPhase.NotPaired, ConnectionPhase.ComputerOff, ConnectionPhase.NotConnected)
            .forEach { assertEquals(LinkMode.Idle, linkModeFor(it)) }
    }
}
