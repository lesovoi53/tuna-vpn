package com.wireturn.app.domain

import java.math.BigInteger
import java.net.InetAddress
import kotlin.random.Random

/** Numeric-only parsing: never resolve a custom candidate as a hostname. */
object CfAddress {
    const val MAX_CANDIDATES = 5000
    fun literal(text: String): InetAddress? {
        val s = text.trim().removeSurrounding("[", "]")
        if (s.contains(':')) {
            if (!Regex("[0-9a-fA-F:]+").matches(s)) return null
            return runCatching { InetAddress.getByName(s) }.getOrNull()?.takeIf { it.address.size == 16 }
        }
        val octets = s.split('.')
        if (octets.size != 4 || octets.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) || (it.toIntOrNull() ?: 256) > 255 }) return null
        return InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
    }
    data class Range(val base: BigInteger, val bits: Int, val prefix: Int) {
        fun contains(ip: InetAddress): Boolean = ip.address.size * 8 == bits &&
            BigInteger(1, ip.address).shiftRight(bits - prefix) == base.shiftRight(bits - prefix)
        fun sample(random: Random): String {
            val bytes = random.nextBytes(bits / 8)
            val value = base.or(BigInteger(1, bytes).and(BigInteger.ONE.shiftLeft(bits - prefix).subtract(BigInteger.ONE)))
            val raw = value.toByteArray().takeLast(bits / 8).toByteArray()
            return InetAddress.getByAddress(ByteArray(bits / 8 - raw.size) + raw).hostAddress!!
        }
    }
    fun range(cidr: String): Range? = runCatching {
        val ip = literal(cidr.substringBefore('/')) ?: return null
        val prefix = cidr.substringAfter('/', "").toInt()
        val bits = ip.address.size * 8
        require(prefix in 1..bits)
        val base = BigInteger(1, ip.address).shiftRight(bits - prefix).shiftLeft(bits - prefix)
        require(base == BigInteger(1, ip.address))
        Range(base, bits, prefix)
    }.getOrNull()
    // Official fallback, https://www.cloudflare.com/ips/ . Online refresh is best effort.
    val bundled = """
        173.245.48.0/20 103.21.244.0/22 103.22.200.0/22 103.31.4.0/22
        141.101.64.0/18 108.162.192.0/18 190.93.240.0/20 188.114.96.0/20
        197.234.240.0/22 198.41.128.0/17 162.158.0.0/15 104.16.0.0/13
        104.24.0.0/14 172.64.0.0/13 131.0.72.0/22
        2400:cb00::/32 2606:4700::/32 2803:f800::/32 2405:b500::/32
        2405:8100::/32 2a06:98c0::/29 2c0f:f248::/32
    """.trimIndent().split(Regex("\\s+")).map { requireNotNull(range(it)) }

    fun candidates(count: Int, ranges: List<Range> = bundled, priority: List<String> = emptyList(),
                   ipv6: Boolean = false, random: Random = Random.Default): List<String> {
        require(count in 1..MAX_CANDIDATES)
        val eligible = ranges.filter { ipv6 || it.bits == 32 }
        require(eligible.isNotEmpty())
        val out = linkedSetOf<String>()
        priority.forEach { value ->
            val ip = literal(value) ?: return@forEach
            if (out.size < count && eligible.any { it.contains(ip) }) out += ip.hostAddress!!
        }
        repeat(count * 32) { n ->
            if (out.size >= count) return out.toList()
            out += eligible[n % eligible.size].sample(random)
        }
        return out.toList()
    }
}
