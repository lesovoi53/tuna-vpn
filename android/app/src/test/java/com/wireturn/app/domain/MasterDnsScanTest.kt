package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicInteger

class MasterDnsScanTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private fun reply(query: ByteArray) = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
    private fun receive(socket: DatagramSocket): DatagramPacket =
        DatagramPacket(ByteArray(4096), 4096).also { socket.receive(it) }
    private fun bytes(packet: DatagramPacket) = packet.data.copyOf(packet.length)

    @Test fun dnsHintsRequireTransactionOpcodeQuestionAndNoTruncation() {
        val query = MasterDnsScanWire.query(9123, "tunnel.example", "nonce")
        assertTrue(MasterDnsScanWire.responseMatches(query, reply(query)))
        assertFalse(MasterDnsScanWire.responseMatches(query, reply(query).also { it[0]++ }))
        assertFalse(MasterDnsScanWire.responseMatches(query, reply(query).also { it[2] = 0x89.toByte() }))
        assertFalse(MasterDnsScanWire.responseMatches(query, reply(query).also { it[2] = 0x83.toByte() }))
        assertFalse(MasterDnsScanWire.responseMatches(query, reply(query).also { it[it.lastIndex] = 2 }))
        assertFalse(MasterDnsScanWire.responseMatches(query, reply(MasterDnsScanWire.query(9123, "other.example", "nonce"))))
        assertFalse(MasterDnsScanWire.responseMatches(query, reply(query).copyOf(15)))
        // A well-formed negative answer can shortlist a resolver, but cannot assert tunnel compatibility.
        assertTrue(MasterDnsScanWire.responseMatches(query, reply(query).also { it[3] = 0x83.toByte() }))
    }

    @Test fun malformedCompressedQuestionsAreRejected() {
        val packet = MasterDnsScanWire.query(5, "tunnel.example", "a")
        packet[12] = 0xc0.toByte(); packet[13] = 12
        assertThrows(IllegalArgumentException::class.java) { MasterDnsScanWire.question(packet) }
    }

    @Test fun relayBindsBeforeConnectCorrelatesCollidingIdsAndRejectsWrongQuestionAndSource() {
        DatagramSocket(0, loopback).use { authority ->
            authority.soTimeout = 2000
            val bound = AtomicInteger()
            val endpoint = MasterDnsEndpoint.parse("127.0.0.1:${authority.localPort}")!!
            MasterDnsScanRelay(listOf(endpoint)) { socket ->
                assertFalse(socket.isConnected)
                bound.incrementAndGet()
            }.use { relay ->
                val local = MasterDnsEndpoint.parse(relay.resolverMapping.keys.single())!!
                DatagramSocket(0, loopback).use { first ->
                    DatagramSocket(0, loopback).use { second ->
                        first.soTimeout = 2000; second.soTimeout = 2000
                        val a = MasterDnsScanWire.query(77, "tunnel.example", "first")
                        val b = MasterDnsScanWire.query(77, "tunnel.example", "second")
                        first.send(DatagramPacket(a, a.size, local.address, local.port))
                        second.send(DatagramPacket(b, b.size, local.address, local.port))
                        val received = listOf(receive(authority), receive(authority))
                        assertNotEquals(MasterDnsScanWire.id(bytes(received[0])), MasterDnsScanWire.id(bytes(received[1])))
                        val upstreamA = received.single { MasterDnsScanWire.question(bytes(it)).name.startsWith("first.") }
                        val upstreamB = received.single { MasterDnsScanWire.question(bytes(it)).name.startsWith("second.") }

                        val wrongQuestion = reply(bytes(upstreamB))
                        MasterDnsScanWire.setId(wrongQuestion, MasterDnsScanWire.id(bytes(upstreamA)))
                        authority.send(DatagramPacket(wrongQuestion, wrongQuestion.size, upstreamA.socketAddress))
                        DatagramSocket(0, loopback).use { wrongSource ->
                            val forged = reply(bytes(upstreamA))
                            wrongSource.send(DatagramPacket(forged, forged.size, upstreamA.socketAddress))
                        }
                        first.soTimeout = 100
                        assertThrows(SocketTimeoutException::class.java) { receive(first) }
                        first.soTimeout = 2000
                        for (packet in listOf(upstreamB, upstreamA)) {
                            val answer = reply(bytes(packet))
                            authority.send(DatagramPacket(answer, answer.size, packet.socketAddress))
                        }
                        assertTrue(MasterDnsScanWire.responseMatches(a, bytes(receive(first))))
                        assertTrue(MasterDnsScanWire.responseMatches(b, bytes(receive(second))))
                        relay.checkHealthy()
                    }
                }
                assertEquals(1, bound.get())
            }
        }
    }

    @Test fun relayCloseReleasesPortsAndBindingFailureCannotFallback() {
        val endpoint = MasterDnsEndpoint.parse("127.0.0.1:53")!!
        val relay = MasterDnsScanRelay(listOf(endpoint)) {}
        val local = MasterDnsEndpoint.parse(relay.resolverMapping.keys.single())!!
        relay.close()
        DatagramSocket(null).use { it.bind(InetSocketAddress(loopback, local.port)) }
        assertThrows(java.io.IOException::class.java) {
            MasterDnsScanRelay(listOf(endpoint)) { throw java.io.IOException("selected network disappeared") }
        }
    }

    @Test fun unroutableEndpointTimesOutWithoutBreakingOtherResolvers() {
        DatagramSocket(0, loopback).use { authority ->
            authority.soTimeout = 2000
            val unreachable = MasterDnsEndpoint.parse("[2001:db8::1]:53")!!
            val reachable = MasterDnsEndpoint.parse("127.0.0.1:${authority.localPort}")!!
            val bound = AtomicInteger()
            val remotes = mutableListOf<DatagramChannel>()
            var localEndpoints = emptyList<MasterDnsEndpoint>()
            MasterDnsScanRelay(
                listOf(unreachable, reachable),
                connectSocket = { channel, address ->
                    remotes += channel
                    if (address.address == unreachable.address) throw NoRouteToHostException("synthetic IPv4-only network")
                    channel.connect(address)
                },
                bindSocket = { socket ->
                    assertFalse(socket.isConnected)
                    bound.incrementAndGet()
                }
            ).use { relay ->
                assertEquals(2, bound.get())
                assertFalse(remotes[0].isOpen)
                assertTrue(remotes[1].isOpen)
                localEndpoints = relay.resolverMapping.keys.map { MasterDnsEndpoint.parse(it)!! }
                val bad = MasterDnsEndpoint.parse(relay.resolverMapping.entries.single { it.value == unreachable.canonical }.key)!!
                val good = MasterDnsEndpoint.parse(relay.resolverMapping.entries.single { it.value == reachable.canonical }.key)!!
                DatagramSocket(0, loopback).use { badClient ->
                    DatagramSocket(0, loopback).use { goodClient ->
                        badClient.soTimeout = 100
                        goodClient.soTimeout = 2000
                        val badQuery = MasterDnsScanWire.query(11, "tunnel.example", "bad")
                        val goodQuery = MasterDnsScanWire.query(12, "tunnel.example", "good")
                        badClient.send(DatagramPacket(badQuery, badQuery.size, bad.address, bad.port))
                        goodClient.send(DatagramPacket(goodQuery, goodQuery.size, good.address, good.port))
                        val upstream = receive(authority)
                        assertEquals(MasterDnsScanWire.question(goodQuery), MasterDnsScanWire.question(bytes(upstream)))
                        val answer = reply(bytes(upstream))
                        authority.send(DatagramPacket(answer, answer.size, upstream.socketAddress))
                        assertTrue(MasterDnsScanWire.responseMatches(goodQuery, bytes(receive(goodClient))))
                        assertThrows(SocketTimeoutException::class.java) { receive(badClient) }
                        relay.checkHealthy()
                    }
                }
            }
            assertTrue(remotes.all { !it.isOpen })
            localEndpoints.forEach { endpoint ->
                DatagramSocket(null).use { it.bind(InetSocketAddress(loopback, endpoint.port)) }
            }
        }
    }

    @Test fun lossReportsOnlyMeasuredAttempts() {
        assertNull(MasterDnsResolverResult("192.0.2.1:53", "tunnel.example", false, 0, 0, 0).lossPercent)
        assertEquals(40.0, MasterDnsResolverResult("192.0.2.1:53", "tunnel.example", true, 100, 200, 50, 5, 3).lossPercent!!, 0.001)
    }
}
