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
        return openChain(url, proxy, requireTunnel, userAgent) { address, route ->
            if (address.toString() == url) factory(route) else address.openConnection(route) as HttpURLConnection
        }
    }

    internal fun openChain(url: String, proxy: Proxy, requireTunnel: Boolean, userAgent: String,
        factory: (URL, Proxy) -> HttpURLConnection): HttpURLConnection {
        if (requireTunnel && proxy.type() != Proxy.Type.SOCKS) throw IOException("Subscription update requires an active tunnel")
        fun create(target: Proxy): HttpURLConnection {
            var address = URL(url)
            repeat(6) { attempt ->
            ConfigurationUrlPolicy.validate(address)
            val connection = factory(address, target)
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", userAgent)
                if (connection.responseCode !in setOf(301, 302, 303, 307, 308)) return connection
                require(attempt < 5) { "Слишком много перенаправлений подписки" }
                address = ConfigurationUrlPolicy.redirect(address, requireNotNull(connection.getHeaderField("Location")))
                connection.disconnect()
            } catch (failure: Exception) {
                connection.disconnect()
                throw failure
            }
            }
            error("Слишком много перенаправлений подписки")
        }
        return try {
            create(proxy)
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is IllegalArgumentException || failure is IllegalStateException) throw failure
            if (!requireTunnel && proxy != Proxy.NO_PROXY) create(Proxy.NO_PROXY) else throw failure
        }
    }
}
