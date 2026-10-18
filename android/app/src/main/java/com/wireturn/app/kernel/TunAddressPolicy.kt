package com.wireturn.app.kernel

import java.net.InetAddress
import java.security.SecureRandom

/** Ephemeral interface addresses, unrelated to profile/server addresses or installation ID. */
object TunAddressPolicy {
    data class Prefix(val bytes: ByteArray, val length: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != bytes.size || length !in 0..bytes.size * 8) return false
            for (bit in 0 until length) {
                val mask = 1 shl (7 - bit % 8)
                if ((address[bit / 8].toInt() and mask) != (bytes[bit / 8].toInt() and mask)) return false
            }
            return true
        }
    }
    data class Addresses(val ipv4: String, val ipv6: String)
    fun choose(occupied: List<Prefix>, random: SecureRandom = SecureRandom()): Addresses {
        // Default routes do not claim an interface subnet. Specific physical routes do.
        val conflicts = occupied.filter { it.length > 0 }
        fun available(bytes: ByteArray) = conflicts.none { it.contains(bytes) }
        var ipv4: ByteArray? = null
        repeat(512) { index ->
            if (ipv4 == null) {
                val candidate = when (index % 3) {
                    0 -> byteArrayOf(10, random.nextInt(256).toByte(), random.nextInt(256).toByte(), (1 + random.nextInt(254)).toByte())
                    1 -> byteArrayOf(172.toByte(), (16 + random.nextInt(16)).toByte(), random.nextInt(256).toByte(), (1 + random.nextInt(254)).toByte())
                    else -> byteArrayOf(192.toByte(), 168.toByte(), random.nextInt(256).toByte(), (1 + random.nextInt(254)).toByte())
                }
                if (available(candidate)) ipv4 = candidate
            }
        }
        val v4 = requireNotNull(ipv4) { "Нет свободного частного IPv4-адреса для VPN" }
        var ipv6: ByteArray? = null
        repeat(128) {
            if (ipv6 == null) {
                val candidate = ByteArray(16).also(random::nextBytes).apply { this[0] = 0xfd.toByte() }
                if (available(candidate)) ipv6 = candidate
            }
        }
        return Addresses(InetAddress.getByAddress(v4).hostAddress!!,
            InetAddress.getByAddress(requireNotNull(ipv6) { "Нет свободного ULA IPv6-адреса для VPN" }).hostAddress!!)
    }
}
