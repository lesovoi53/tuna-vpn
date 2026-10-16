package com.wireturn.app.data.kernel

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

object MieruUri {
    val lowEntropyModes = listOf("OFF", "32", "40", "48", "56").map { "LOW_ENTROPY_MODE_$it" }
    val rotations = listOf("LOW_ENTROPY_MASK_NO_ROTATION") + listOf("RIGHT", "LEFT").flatMap { direction ->
        (1..15).map { "LOW_ENTROPY_MASK_ROTATE_${direction}_$it" }
    }
    fun query(uri: URI) = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
        require('=' in it)
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }.groupBy({ it.first }, { it.second })

    fun validate(text: String) {
        require(text.length in 1..32768)
        val uri = URI(text)
        require(uri.scheme == "mierus" && !uri.host.isNullOrBlank() && uri.port == -1)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        val credentials = uri.rawUserInfo.orEmpty().split(':', limit = 2)
        require(credentials.size == 2 && credentials.all { it.isNotBlank() })
        val q = query(uri)
        val allowed = setOf("profile", "port", "protocol", "multiplexing", "traffic-pattern", "mtu", "handshake-mode", "low-entropy-mode", "low-entropy-mask-rotation")
        require(q.keys.all { it in allowed })
        require(q.all { (key, value) -> key in setOf("port", "protocol") || value.size == 1 })
        require(!q["profile"]?.singleOrNull().isNullOrBlank())
        val ports = q["port"].orEmpty(); val transports = q["protocol"].orEmpty()
        require(ports.isNotEmpty() && ports.size == transports.size && ports.size <= 64)
        require(transports.all { it in setOf("TCP", "UDP") })
        ports.forEach {
            val bounds = it.split('-'); require(bounds.size in 1..2)
            val numbers = bounds.map { n -> n.toInt().also { value -> require(value in 1..65535) } }
            require(numbers.first() <= numbers.last())
        }
        fun one(key: String) = q[key]?.single()
        one("multiplexing")?.let { require(it in listOf("DEFAULT", "OFF", "LOW", "MIDDLE", "HIGH").map { level -> "MULTIPLEXING_$level" }) }
        one("handshake-mode")?.let { require(it in setOf("HANDSHAKE_DEFAULT", "HANDSHAKE_STANDARD", "HANDSHAKE_NO_WAIT")) }
        one("mtu")?.let { require(it.toInt() == 0 || it.toInt() in 1280..1500) }
        one("low-entropy-mode")?.let { require(it in lowEntropyModes) }
        one("low-entropy-mask-rotation")?.let { require(it in rotations) }
        one("traffic-pattern")?.let { require(Base64.getDecoder().decode(it).size in 1..8192) }
    }
    fun name(text: String): String = runCatching { query(URI(text))["profile"]?.single() }.getOrNull().orEmpty()
}
