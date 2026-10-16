package com.wireturn.app.domain

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Connection establishment is shared by manual and scheduled subscription refresh. */
internal object SubscriptionHttpTransport {
    fun open(url: String, proxy: Proxy, requireTunnel: Boolean, userAgent: String,
             factory: (Proxy) -> HttpURLConnection = { URL(url).openConnection(it) as HttpURLConnection }): HttpURLConnection {
        if (requireTunnel && proxy.type() != Proxy.Type.SOCKS) throw IOException("Subscription update requires an active tunnel")
        fun create(target: Proxy, connect: Boolean): HttpURLConnection {
            val connection = factory(target)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", userAgent)
                if (connect) connection.responseCode
                return connection
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
        }
        return try {
            create(proxy, connect = true)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (!requireTunnel && proxy != Proxy.NO_PROXY) create(Proxy.NO_PROXY, connect = false) else throw failure
        }
    }
}
