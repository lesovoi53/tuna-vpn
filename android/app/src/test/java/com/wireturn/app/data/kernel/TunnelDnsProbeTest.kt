package com.wireturn.app.data.kernel

import com.wireturn.app.data.*
import com.wireturn.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*

class TunnelDnsProbeTest {
    private fun client(port: Int) = ClientConfig(socksAddr="127.0.0.1:$port", socksUser="user", socksPass="password", isSocksAuthEnabled=true)
    private fun resolver(type: String) = SubscriptionPolicyParser.Resolver(type,"192.0.2.53",null,RouteAction.VPN)
    private fun handshake(socket: Socket, command: Int): DataInputStream {
        socket.soTimeout=3000
        val input=DataInputStream(socket.getInputStream()); val output=socket.getOutputStream()
        assertArrayEquals(byteArrayOf(5,1,2), ByteArray(3).also(input::readFully))
        output.write(byteArrayOf(5,2)); output.flush()
        assertEquals(1,input.readUnsignedByte())
        assertEquals("user",ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8))
        assertEquals("password",ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8))
        output.write(byteArrayOf(1,0));output.flush()
        assertArrayEquals(byteArrayOf(5,command.toByte(),0,1),ByteArray(4).also(input::readFully))
        val ip=ByteArray(4).also(input::readFully)
        assertArrayEquals(if(command==1) byteArrayOf(192.toByte(),0,2,53) else byteArrayOf(0,0,0,0),ip)
        assertEquals(if(command==1) 53 else 0,input.readUnsignedShort())
        return input
    }
    @Test fun tcpUsesAuthenticatedSocksAndDnsFraming() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=3000
            val executor=Executors.newSingleThreadExecutor()
            try {
                val task=executor.submit<Unit> { server.accept().use { socket ->
                    val input=handshake(socket,1); val output=DataOutputStream(socket.getOutputStream())
                    output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0));output.flush()
                    val request=ByteArray(input.readUnsignedShort()).also(input::readFully)
                    assertArrayEquals(byteArrayOf(1,2,3),request)
                    output.writeShort(3);output.write(byteArrayOf(4,5,6));output.flush()
                } }
                assertArrayEquals(byteArrayOf(4,5,6),TunnelDnsProbe.query(client(server.localPort),resolver("TCP"),byteArrayOf(1,2,3)))
                task.get(5,TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun udpUsesAssociateAndLocalRelay() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=3000
            DatagramSocket(0,InetAddress.getByName("127.0.0.1")).use { relay ->
                relay.soTimeout=3000
                val executor=Executors.newSingleThreadExecutor()
                try {
                    val task=executor.submit<Unit> { server.accept().use { socket ->
                        handshake(socket,3)
                        socket.getOutputStream().apply { write(byteArrayOf(5,0,0,1,127,0,0,1,(relay.localPort shr 8).toByte(),relay.localPort.toByte()));flush() }
                        val packet=DatagramPacket(ByteArray(100),100);relay.receive(packet)
                        assertArrayEquals(byteArrayOf(0,0,0,1,192.toByte(),0,2,53,0,53,1,2,3),packet.data.copyOf(packet.length))
                        val reply=packet.data.copyOf(packet.length);reply[10]=4
                        relay.send(DatagramPacket(reply,reply.size,packet.socketAddress))
                    } }
                    assertArrayEquals(byteArrayOf(4,2,3),TunnelDnsProbe.query(client(server.localPort),resolver("UDP"),byteArrayOf(1,2,3)))
                    task.get(5,TimeUnit.SECONDS)
                } finally { executor.shutdownNow() }
            }
        }
    }
    @Test fun rejectedSocksAuthenticationFailsWithoutDirectFallback() {
        ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout=3000
            val executor=Executors.newSingleThreadExecutor()
            try {
                val task=executor.submit<Unit> { server.accept().use { socket ->
                    socket.soTimeout=3000
                    DataInputStream(socket.getInputStream()).readFully(ByteArray(3))
                    socket.getOutputStream().apply { write(byteArrayOf(5,255.toByte()));flush() }
                } }
                val error=assertThrows(IllegalArgumentException::class.java) { TunnelDnsProbe.query(client(server.localPort),resolver("TCP"),byteArrayOf(1,2,3)) }
                assertTrue(error.message!!.contains("SOCKS"))
                task.get(5,TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
