package com.aplusconnect.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileHistoryTest {
    private fun entry(id: String, dir: Direction, ts: Long, name: String = "f-$id") =
        SharedFileEntry(id, name, dir, 1024, ts, "content://x/$id")

    @Test fun newestFirstAndOneRowPerId() {
        var list = emptyList<SharedFileEntry>()
        list = FileHistoryRules.add(list, entry("a", Direction.Sent, 1))
        list = FileHistoryRules.add(list, entry("b", Direction.Received, 2))
        list = FileHistoryRules.add(list, entry("a", Direction.Sent, 3))
        assertEquals(listOf("a", "b"), list.map { it.id })
        assertEquals(3L, list.first().timestamp)
    }

    @Test fun keepsAtMostTwoHundred() {
        var list = emptyList<SharedFileEntry>()
        for (i in 0 until 230) list = FileHistoryRules.add(list, entry("r$i", Direction.Received, i.toLong()))
        assertEquals(200, FileHistoryRules.MAX)
        assertEquals(200, list.size)
        assertEquals("r229", list.first().id)
    }

    @Test fun splitsSentAndReceived() {
        val list = listOf(
            entry("s1", Direction.Sent, 10),
            entry("r1", Direction.Received, 30),
            entry("s2", Direction.Sent, 20),
        )
        assertEquals(2, FileHistoryRules.count(list, Direction.Sent))
        assertEquals(1, FileHistoryRules.count(list, Direction.Received))
        assertEquals(listOf("s2", "s1"), FileHistoryRules.of(list, Direction.Sent).map { it.id })
    }

    @Test fun survivesARoundTripAndBadData() {
        val list = listOf(entry("s1", Direction.Sent, 10, "report.pdf"), entry("r1", Direction.Received, 30))
        assertEquals(list, FileHistoryRules.decode(FileHistoryRules.encode(list)))
        assertTrue(FileHistoryRules.decode("not json").isEmpty())
        assertTrue(FileHistoryRules.decode(null).isEmpty())
    }
}
