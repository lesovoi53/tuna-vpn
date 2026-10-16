package com.wireturn.app.kernel

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Readiness checks the authenticated listener, without contacting any remote server. */
object RouterSocksProbe {
    fun verify(listener: SingBoxNetworkCompiler.LocalSocks) {
        val endpoint = listener.endpoint()
        val user = listener.username.toByteArray(Charsets.UTF_8)
        val password = listener.password.toByteArray(Charsets.UTF_8)
        require(user.size in 1..255 && password.size in 1..255)
        Socket().use { socket ->
            socket.soTimeout = 1500
            socket.connect(InetSocketAddress(endpoint.host.removeSurrounding("[", "]"), endpoint.port), 1500)
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            output.write(byteArrayOf(5, 1, 2)); output.flush()
            require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2) { "Маршрутизатор не требует SOCKS-авторизацию" }
            output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
            output.flush()
            require(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) { "Не прошла авторизация маршрутизатора" }
        }
    }
}
