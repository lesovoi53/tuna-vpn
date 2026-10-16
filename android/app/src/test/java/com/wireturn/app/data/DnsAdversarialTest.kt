package com.wireturn.app.data

import com.wireturn.app.domain.DnsWire
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Packet fixtures stay in memory; no resolver, sockets, device or live profile is used. */
class DnsAdversarialTest {
    private val query = DnsWire.query(0x4a21)

    private fun pointer(offset: Int) = byteArrayOf((0xc0 or (offset ushr 8)).toByte(), offset.toByte())

    private fun answer(owner: ByteArray = pointer(12)): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            write(owner)
            writeShort(1) // A
            writeShort(1) // IN
            writeInt(60)
            writeShort(4)
            write(byteArrayOf(192.toByte(), 0, 2, 53))
        }
    }.toByteArray()

    private fun reply(vararg answers: ByteArray): ByteArray = query.copyOf().also {
        it[2] = 0x81.toByte()
        it[3] = 0x80.toByte()
        it[7] = answers.size.toByte()
    } + answers.fold(byteArrayOf()) { all, next -> all + next }

    private fun rejected(packet: ByteArray, description: String) {
        assertThrows(description, IllegalArgumentException::class.java) { DnsWire.validate(query, packet) }
    }

    @Test fun validAnswerNamesCanCompressThroughAnEarlierAnswer() {
        DnsWire.validate(query, reply(answer(), answer(pointer(query.size))))
        DnsWire.validate(query, reply(answer(query.copyOfRange(12, query.size - 4))))
    }

    @Test fun everyTruncatedPrefixFailsWithoutIndexErrors() {
        val complete = reply(answer())
        for (length in 0 until complete.size) {
            rejected(complete.copyOf(length), "Truncated packet at byte $length")
        }
        DnsWire.validate(query, complete)
    }

    @Test(timeout = 1000) fun malformedCompressedAndOverlongNamesCannotReportHealthyDns() {
        val invalidOwners = linkedMapOf(
            "self pointer" to pointer(query.size),
            "out of packet pointer" to pointer(0x3fff),
            "pointer into DNS header" to pointer(0),
            "label and backward pointer cycle" to (byteArrayOf(1, 'x'.code.toByte()) + pointer(query.size)),
            "reserved label encoding" to byteArrayOf(0x40, 0),
            "name longer than 255 octets" to (ByteArray(256) { if (it % 64 == 0) 63 else 'x'.code.toByte() } + byteArrayOf(0))
        )
        for ((description, owner) in invalidOwners) rejected(reply(answer(owner)), description)
    }

    @Test fun anEarlierValidAddressDoesNotHideAMissingOrTruncatedLaterAnswer() {
        val missing = reply(answer()).also { it[7] = 2 }
        rejected(missing, "ANCOUNT announces an absent second answer")
        rejected(reply(answer(), answer().dropLast(1).toByteArray()), "Second address is truncated")
        rejected(reply(answer(), byteArrayOf(0xc0.toByte())), "Second name has half a pointer")
    }

    @Test fun bootstrapRejectsAmbiguousHostsAndUnrequestedUrlComponents() {
        val invalid = listOf(
            "tcp://resolver.example:53", "tcp://2130706433", "tcp://127.1",
            "tcp://192.0.2.53:65536", "tcp://192.0.2.53:0",
            "tcp://user:password@192.0.2.53:53", "tcp://192.0.2.53?fallback=system",
            "tcp://192.0.2.53#ignored", "tcp://192.0.2.53/dns-query",
            "https://192.0.2.53", "udp://192.0.2.53",
            "tcp://[fe80::1%25eth0]", "tcp://[::ffff:192.0.2.53]"
        )
        for (endpoint in invalid) assertTrue(endpoint, runCatching { BootstrapDns.parse(endpoint) }.isFailure)
    }

    @Test fun bootstrapKeepsAddressFamilyAndPortConsistentForBothAdapters() {
        assertNull(BootstrapDns.parse(" \t\n"))
        for ((endpoint, address, port) in listOf(
            Triple(" tcp://192.0.2.53 ", "192.0.2.53", 53),
            Triple("tcp://192.0.2.53:1", "192.0.2.53", 1),
            Triple("tcp://[2001:db8::53]:65535", "2001:db8::53", 65535)
        )) {
            val parsed = BootstrapDns.parse(endpoint)!!
            val server = parsed.singBoxServer("fixture-bootstrap")
            assertEquals(address, server["server"].asString)
            assertEquals(port, server["server_port"].asInt)
            assertEquals("tcp", server["type"].asString)
            assertEquals("fixture-bootstrap", server["tag"].asString)
            assertEquals(parsed, BootstrapDns.parse(parsed.xrayAddress()))
        }
    }
}
