package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy

class ExitObservationTest {
    @Test fun parsesWarpAndDoesNotInferServerDirectness() {
        val on = ExitObservation.parse("ip=203.0.113.24\nloc=DE\nwarp=on\n", 42)!!
        assertEquals(true, on.warp); assertEquals("DE", on.country); assertEquals(42L, on.checkedAt)
        assertEquals(false, ExitObservation.parse("ip=198.51.100.42\nwarp=off", 0)!!.warp)
        assertNull(ExitObservation.parse("ip=198.51.100.42\nwarp=unknown", 0)!!.warp)
        assertEquals(true, ExitObservation.parse("ip=2001:db8::1\nwarp=plus", 0)!!.warp)
    }
    @Test fun rejectsHtmlInvalidAddressesAndAmbiguousFields() {
        for (body in listOf("<html>login</html>", "ip=host.example", "ip=999.1.1.1", "ip=1.1.1.1\nip=8.8.8.8", "ip=127.1", "ip=01.2.3.4", "ip=::1%wlan0", "x".repeat(8193)))
            assertNull(body.take(40), ExitObservation.parse(body, 0))
    }
    @Test fun neverFallsBackToDirectConnection() {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { ExitObservation.fetch(Proxy.NO_PROXY) } }
    }
}
