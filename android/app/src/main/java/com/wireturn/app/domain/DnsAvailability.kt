package com.wireturn.app.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

object DnsAvailability {
    suspend fun check(context: Context, resolver: SubscriptionPolicyParser.Resolver, throughTunnel: Boolean = false): String = withContext(Dispatchers.IO) {
        try {
            require(resolver.transport != "DOT") { "Проверка DoT этой кнопкой пока недоступна; конфигурация проверяется ядром при запуске" }
            if (throughTunnel) {
                val session = requireNotNull(com.wireturn.app.CoreServiceState.session.value) { "Сначала подключите VPN-профиль" }
                require(com.wireturn.app.CoreServiceState.status.value is com.wireturn.app.CoreStatus.Connected) { "Дождитесь подключения транспорта профиля" }
                require(session.clientConfig.kernelVariant.isSocks5Native) { "Нет SOCKS транспорта" }
                val query = DnsWire.query(java.security.SecureRandom().nextInt(65536))
                val start = System.nanoTime()
                val reply = TunnelDnsProbe.query(session.clientConfig, resolver, query)
                ensureActive()
                require(com.wireturn.app.CoreServiceState.session.value === session) { "Подключение изменилось; повторите проверку" }
                DnsWire.validate(query, reply)
                return@withContext "Доступен через транспорт VPN · ${(System.nanoTime() - start) / 1_000_000} мс"
            }
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val networks = listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()
            val network = networks.firstOrNull { n -> manager.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true } ?: error("Нет доступной сети без VPN")
            val query = DnsWire.query(java.security.SecureRandom().nextInt(65536))
            val uri = URI(if (resolver.transport == "DOH") resolver.endpoint else "dns://${resolver.endpoint}")
            val start = System.nanoTime()
            val checked = if (resolver.transport == "DOH" && resolver.bootstrapIp.isNullOrBlank() && !uri.host.isNullOrBlank())
                resolver.copy(bootstrapIp = network.getAllByName(uri.host).first().hostAddress) else resolver
            when (checked.route) {
                com.wireturn.app.data.RouteAction.VPN -> SplitDnsSettings(remote = checked)
                com.wireturn.app.data.RouteAction.DIRECT -> SplitDnsSettings(domestic = checked)
                else -> error("Неподдерживаемый маршрут DNS")
            }.validate()
            val reply = when (resolver.transport) {
                "UDP" -> DatagramSocket(null).use { socket ->
                    network.bindSocket(socket); socket.soTimeout = 3500
                    socket.connect(InetSocketAddress(uri.host, if (uri.port < 0) 53 else uri.port))
                    socket.send(DatagramPacket(query, query.size))
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(packet); packet.data.copyOf(packet.length)
                }
                "TCP" -> network.socketFactory.createSocket().use { socket ->
                    socket.soTimeout = 3500
                    socket.connect(InetSocketAddress(uri.host, if (uri.port < 0) 53 else uri.port), 3500)
                    DataOutputStream(socket.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
                    val input = DataInputStream(socket.getInputStream())
                    ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
                }
                else -> {
                    val client = OkHttpClient.Builder().socketFactory(network.socketFactory)
                        .proxy(Proxy.NO_PROXY).dns(object : okhttp3.Dns { override fun lookup(host: String): List<InetAddress> =
                            if (host == uri.host && !checked.bootstrapIp.isNullOrBlank()) listOf(InetAddress.getByName(checked.bootstrapIp))
                            else network.getAllByName(host).toList()
                        }).connectTimeout(3500, TimeUnit.MILLISECONDS).readTimeout(3500, TimeUnit.MILLISECONDS)
                        .callTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
                    try {
                        val request = Request.Builder().url(resolver.endpoint).header("Accept", "application/dns-message")
                            .post(query.toRequestBody("application/dns-message".toMediaType())).build()
                        client.newCall(request).execute().use { response ->
                            require(response.code == 200) { "DoH: HTTP ${response.code}" }
                            require(response.header("Content-Type").orEmpty().substringBefore(';').equals("application/dns-message", true)) { "Сервер вернул не DNS-ответ" }
                            val input = response.body!!.byteStream()
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 65535) { "Слишком большой DNS-ответ" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                }
            }
            ensureActive()
            DnsWire.validate(query, reply)
            "Доступен · ${(System.nanoTime() - start) / 1_000_000} мс"
        } catch (e: Exception) {
            ensureActive()
            when (e) {
                is SocketTimeoutException -> "Нет ответа: таймаут"
                is javax.net.ssl.SSLException -> "Ошибка TLS-сертификата или соединения"
                is IllegalArgumentException, is IllegalStateException -> e.message ?: "Некорректная настройка DNS"
                else -> "Проверка не удалась (${e.javaClass.simpleName})"
            }
        }
    }
}
