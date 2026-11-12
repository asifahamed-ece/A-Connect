package com.aplusconnect.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingPayloadTest {

    private val info = PairingInfo(
        pcName = "Studio PC",
        host = "192.168.1.39",
        port = 24443,
        code = "123456",
        fingerprint = "AA:BB:CC",
        pcId = "pc-1",
    )

    @Test
    fun `a payload round trips`() {
        val parsed = PairingPayload.fromJson(PairingPayload.toJson(info).toString())

        assertEquals(info, parsed)
    }

    @Test
    fun `something that is not a pairing payload is refused`() {
        assertNull(PairingPayload.fromJson("hello"))
        assertNull(PairingPayload.fromJson("{}"))
        assertNull(PairingPayload.fromJson("not json"))
    }

    @Test
    fun `a payload without an address or a code is refused`() {
        assertNull(PairingPayload.fromJson("""{"v":1,"type":"aplus-pairing","port":24443,"code":"123456"}"""))
        assertNull(PairingPayload.fromJson("""{"v":1,"type":"aplus-pairing","host":"1.2.3.4","code":"12345"}"""))
        assertNull(PairingPayload.fromJson("""{"v":1,"type":"aplus-pairing","host":"1.2.3.4","port":24443}"""))
    }

    @Test
    fun `a payload from another version is refused`() {
        assertNull(
            PairingPayload.fromJson(
                """{"v":99,"type":"aplus-pairing","host":"1.2.3.4","port":24443,"code":"123456"}"""
            )
        )
    }
}
