package com.wireturn.app.domain

import com.wireturn.app.data.ClientConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import javax.net.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Explicit SOCKS transport, connection-local authentication, no direct network fallback. */
object TunnelDnsProbe {
    private const val TIMEOUT = 5000
    fun query(config: ClientConfig, resolver: SubscriptionPolicyParser.Resolver, request: ByteArray): ByteArray {
        // Validate the resolver in its declared pool; duplicating it in both pools
        // incorrectly gives one side the other side's routing action.
        when (resolver.route) {
            com.wireturn.app.data.RouteAction.VPN -> SplitDnsSettings(remote = resolver)
            com.wireturn.app.data.RouteAction.DIRECT -> SplitDnsSettings(domestic = resolver)
            else -> error("Неподдерживаемый маршрут DNS")
        }.validate()
        require(resolver.transport in setOf("UDP", "TCP", "DOH")) { "Проверка DNS поддерживает UDP, TCP и DoH" }
        val uri = URI(if (resolver.transport == "DOH") resolver.endpoint else "dns://${resolver.endpoint}")
        val host = uri.host.removeSurrounding("[", "]")
        val port = uri.port.takeIf { it > 0 } ?: if (resolver.transport == "DOH") 443 else 53
        return when (resolver.transport) {
            "UDP" -> udp(config, host, port, request)
            "TCP" -> SocksSocket(config, host, port).use { socket ->
                socket.connect(InetSocketAddress.createUnresolved(host, port), TIMEOUT)
                DataOutputStream(socket.getOutputStream()).apply { writeShort(request.size); write(request); flush() }
                DataInputStream(socket.getInputStream()).let { input -> ByteArray(input.readUnsignedShort()).also(input::readFully) }
            }
            else -> {
                val destination = resolver.bootstrapIp?.takeIf { it.isNotBlank() } ?: host
                val factory = object : SocketFactory() {
                    override fun createSocket(): Socket = SocksSocket(config, destination, port)
                    override fun createSocket(h: String, p: Int): Socket = createSocket().apply { connect(InetSocketAddress.createUnresolved(h, p), TIMEOUT) }
                    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = createSocket(h, p)
                    override fun createSocket(h: InetAddress, p: Int): Socket = createSocket(h.hostAddress!!, p)
                    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = createSocket(h, p)
                }
                val client = OkHttpClient.Builder().socketFactory(factory).proxy(Proxy.NO_PROXY)
                    // This synthetic address is never dialled: SocksSocket uses the explicit destination.
                    .dns(object : okhttp3.Dns {
                        override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)))
                    })
                    .connectTimeout(TIMEOUT.toLong(), TimeUnit.MILLISECONDS).readTimeout(TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
                    .callTimeout(7, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
                try {
                    client.newCall(Request.Builder().url(resolver.endpoint).header("Accept", "application/dns-message")
                        .post(request.toRequestBody("application/dns-message".toMediaType())).build()).execute().use { response ->
                        require(response.code == 200) { "DoH: HTTP ${response.code}" }
                        require(response.header("Content-Type").orEmpty().substringBefore(';').equals("application/dns-message", true)) { "Ответ не является DNS" }
                        val output = java.io.ByteArrayOutputStream()
                        val input = requireNotNull(response.body).byteStream()
                        val buffer = ByteArray(4096)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 65535) { "Слишком большой DNS-ответ" }; output.write(buffer, 0, count) }
                        output.toByteArray()
                    }
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
            }
        }
    }
    private fun proxy(config: ClientConfig): InetSocketAddress {
        val uri = URI("socks://${config.socksAddr.replace("0.0.0.0:", "127.0.0.1:")}")
        val host = uri.host.removeSurrounding("[", "]")
        require(host in setOf("127.0.0.1", "::1") && uri.port in 1..65535) { "Нет локального SOCKS транспорта" }
        return InetSocketAddress(host, uri.port)
    }
    private fun authenticate(socket: Socket, config: ClientConfig) {
        val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
        val method = if (config.isSocksAuthEnabled) 2 else 0
        output.write(byteArrayOf(5, 1, method.toByte())); output.flush()
        require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == method) { "SOCKS отклонил метод авторизации" }
        if (method == 2) {
            val user = config.socksUser.toByteArray(); val pass = config.socksPass.toByteArray()
            require(user.size in 1..255 && pass.size in 1..255)
            output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass); output.flush()
            require(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) { "SOCKS отклонил авторизацию" }
        }
    }
    private fun address(host: String, port: Int): ByteArray {
        val bytes = if (StaticHosts.isIp(host)) InetAddress.getByName(host).address.let { byteArrayOf(if (it.size == 4) 1 else 4) + it }
            else java.net.IDN.toASCII(host).toByteArray(Charsets.US_ASCII).let { require(it.size in 1..255); byteArrayOf(3, it.size.toByte()) + it }
        return bytes + byteArrayOf((port shr 8).toByte(), port.toByte())
    }
    private fun readAddress(input: DataInputStream): Pair<String, Int> {
        val type = input.readUnsignedByte()
        val length = when(type) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("Некорректный SOCKS-адрес") }
        val bytes = ByteArray(length).also(input::readFully)
        val host = if (type == 3) bytes.toString(Charsets.US_ASCII) else InetAddress.getByAddress(bytes).hostAddress!!
        return host to input.readUnsignedShort()
    }
    private fun command(socket: Socket, command: Int, host: String, port: Int): Pair<String, Int> {
        socket.getOutputStream().apply { write(byteArrayOf(5, command.toByte(), 0) + address(host, port)); flush() }
        val input = DataInputStream(socket.getInputStream())
        require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "Транспорт не поддерживает запрос SOCKS (TCP/UDP)" }
        require(input.readUnsignedByte() == 0)
        return readAddress(input)
    }
    private class SocksSocket(val config: ClientConfig, val host: String, val destinationPort: Int) : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            super.connect(proxy(config), timeout.coerceAtMost(TIMEOUT).takeIf { it > 0 } ?: TIMEOUT)
            soTimeout = TIMEOUT
            authenticate(this, config); command(this, 1, host, destinationPort)
        }
        override fun connect(endpoint: SocketAddress) = connect(endpoint, TIMEOUT)
    }
    private fun udp(config: ClientConfig, host: String, port: Int, request: ByteArray): ByteArray = Socket().use { control ->
        control.connect(proxy(config), TIMEOUT); control.soTimeout = TIMEOUT; authenticate(control, config)
        val (relayHost, relayPort) = command(control, 3, "0.0.0.0", 0)
        val relay = if (relayHost in setOf("0.0.0.0", "0:0:0:0:0:0:0:0", "::")) proxy(config).address else {
            require(StaticHosts.isIp(relayHost)) { "SOCKS вернул нерезолвленный UDP relay" }
            InetAddress.getByName(relayHost)
        }
        require(relay.isLoopbackAddress && relayPort in 1..65535) { "UDP relay должен принадлежать локальному транспорту" }
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT; socket.connect(relay, relayPort)
            val body = byteArrayOf(0, 0, 0) + address(host, port) + request
            socket.send(DatagramPacket(body, body.size))
            val packet = DatagramPacket(ByteArray(65535), 65535); socket.receive(packet)
            val input = DataInputStream(java.io.ByteArrayInputStream(packet.data, 0, packet.length))
            require(input.readUnsignedShort() == 0 && input.readUnsignedByte() == 0) { "Фрагментированный SOCKS UDP не поддержан" }
            readAddress(input)
            ByteArray(input.available()).also(input::readFully)
        }
    }
}
