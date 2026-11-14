package com.aplusconnect.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LinkScheduleTest {
    private fun run(s: LinkSchedule, from: Long, to: Long) {
        var t = from
        while (t <= to) { s.advance(t); t += 16 }
    }

    @Test fun startsBusy() {
        val s = LinkSchedule(LinkMode.Live, Random(1))
        assertTrue(s.advance(10_000))
        assertEquals(3, s.packets.size)
    }

    @Test fun keepsSendingWhileFramesRun() {
        val s = LinkSchedule(LinkMode.Live, Random(1))
        run(s, 10_000, 70_000)
        // a packet every 720 ms, each in flight 2 s: never empty, never more than 3 or 4 at once
        assertTrue(s.packets.size in 1..4)
        assertTrue(s.packets.any { 70_000 - it.start < 720 })
    }

    @Test fun nothingPilesUpWhileTheScreenIsOff() {
        val s = LinkSchedule(LinkMode.Live, Random(1))
        run(s, 10_000, 12_000)
        // eight hours with no frames, then the screen comes back on
        s.advance(10_000 + 8 * 3_600_000L)
        assertTrue(s.packets.size <= 4)
        run(s, 10_000 + 8 * 3_600_000L, 10_000 + 8 * 3_600_000L + 5_000)
        assertTrue(s.packets.size in 1..4)
    }

    @Test fun handshakeOnlyGoesToTheComputer() {
        val s = LinkSchedule(LinkMode.Pending, Random(2))
        run(s, 0, 20_000)
        assertTrue(s.packets.all { it.toComputer && it.bits.length == 4 })
    }

    @Test fun idleSendsNothing() {
        val s = LinkSchedule(LinkMode.Idle)
        assertFalse(s.advance(5_000))
        assertTrue(s.packets.isEmpty())
    }
}
