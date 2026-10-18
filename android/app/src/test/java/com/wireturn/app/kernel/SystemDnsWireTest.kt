package com.wireturn.app.kernel

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class SystemDnsWireTest {
    private fun query(type: Int = 1) = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, type.toByte(), 0, 1)
    @Test fun preservesQuestionAndFiltersRequestedFamily() {
        val wire = query()
        val question = SystemDnsWire.question(wire)
        assertEquals("example.com", question.name)
        val response = SystemDnsWire.answer(wire, question, arrayOf(InetAddress.getByName("192.0.2.3"), InetAddress.getByName("2001:db8::1")))
        assertArrayEquals(wire.copyOfRange(12, wire.size), response.copyOfRange(12, wire.size))
        assertEquals(1, response[7].toInt())
        assertEquals(0, response[3].toInt() and 15)
        assertEquals(0x12, response[0].toInt())
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 3), response.takeLast(4).toByteArray())
    }
    @Test fun absentFamilyIsNodataAndNotNxDomain() {
        val wire = query(28)
        val response = SystemDnsWire.answer(wire, SystemDnsWire.question(wire), arrayOf(InetAddress.getByName("192.0.2.3")))
        assertEquals(0, response[7].toInt())
        assertEquals(0, response[3].toInt() and 15)
    }
    @Test fun rejectsCompressionMalformedQuestionsAndNonAddressRequests() {
        val invalid = listOf(query().copyOf(13), query(16), query().apply { this[12] = 0xc0.toByte() },
            query().apply { this[5] = 2 }, query().apply { this[2] = 0x80.toByte() })
        invalid.forEach { wire -> assertTrue(runCatching { SystemDnsWire.question(wire) }.isFailure) }
    }
}
