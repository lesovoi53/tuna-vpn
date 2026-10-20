package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class MasterDnsGoodputRelayTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test fun relayAccountsBothDirectionsAndStopsBeforeBudgetWithDatagramReserve() {
        DatagramSocket(0, loopback).use { remote ->
            val endpoint = MasterDnsEndpoint.parse("127.0.0.1:${remote.localPort}")!!
            val query = MasterDnsScanWire.query(77, "tunnel.example.invalid", "budget")
            val limit = 65535L + query.size * 2
            MasterDnsScanRelay(listOf(endpoint), byteBudget = limit) { }.use { relay ->
                val target = MasterDnsEndpoint.parse(relay.resolverMapping.keys.single())!!
                DatagramSocket(0, loopback).use { client ->
                    client.soTimeout = 2000
                    remote.soTimeout = 2000
                    client.send(DatagramPacket(query, query.size, target.address, target.port))
                    val forwarded = DatagramPacket(ByteArray(4096), 4096).also { remote.receive(it) }
                    val reply = forwarded.data.copyOf(forwarded.length).also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
                    remote.send(DatagramPacket(reply, reply.size, forwarded.socketAddress))
                    client.receive(DatagramPacket(ByteArray(4096), 4096))
                    assertEquals(query.size * 2L, relay.wireBytes)
                    client.send(DatagramPacket(query, query.size, target.address, target.port))
                    val deadline = System.nanoTime() + 2_000_000_000L
                    while (runCatching { relay.checkHealthy() }.isSuccess && System.nanoTime() < deadline) Thread.sleep(10)
                    assertThrows(IOException::class.java) { relay.checkHealthy() }
                    assertTrue(relay.wireBytes <= limit)
                    remote.soTimeout = 100
                    assertThrows(SocketTimeoutException::class.java) { remote.receive(DatagramPacket(ByteArray(4096), 4096)) }
                }
            }
        }
    }

    @Test fun rejectedMalformedRemoteDatagramsStillConsumeTheBudget() {
        DatagramSocket(0, loopback).use { remote ->
            val endpoint = MasterDnsEndpoint.parse("127.0.0.1:${remote.localPort}")!!
            val query = MasterDnsScanWire.query(88, "tunnel.example.invalid", "malformed")
            MasterDnsScanRelay(listOf(endpoint), byteBudget = 65535L + query.size) { }.use { relay ->
                val target = MasterDnsEndpoint.parse(relay.resolverMapping.keys.single())!!
                DatagramSocket(0, loopback).use { client ->
                    remote.soTimeout = 2000
                    client.send(DatagramPacket(query, query.size, target.address, target.port))
                    val forwarded = DatagramPacket(ByteArray(4096), 4096).also { remote.receive(it) }
                    remote.send(DatagramPacket(ByteArray(10), 10, forwarded.socketAddress))
                    val deadline = System.nanoTime() + 2_000_000_000L
                    while (runCatching { relay.checkHealthy() }.isSuccess && System.nanoTime() < deadline) Thread.sleep(10)
                    assertThrows(IOException::class.java) { relay.checkHealthy() }
                    assertEquals(query.size + 10L, relay.wireBytes)
                }
            }
        }
    }
}
