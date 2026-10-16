package com.wireturn.app.domain

object StaticHosts {
    fun parse(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            val pair = line.trim().split(Regex("\\s+"))
            require(pair.size == 2) { "Одна запись в строке: example.com 192.0.2.1" }
            val host = java.net.IDN.toASCII(pair[0].trimEnd('.')).lowercase()
            require(result.put(host, pair[1]) == null) { "Повтор домена: $host" }
        }
        validate(result)
        return result
    }
    fun validate(hosts: Map<String, String>) {
        require(hosts.size <= 500) { "Не более 500 DNS-записей" }
        hosts.forEach { (host, address) ->
            require(host.length in 1..253 && host.split('.').all { it.matches(Regex("[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?")) } && host.any { it.isLetter() }) { "Некорректный домен: $host" }
            require(isIp(address)) { "Нужен IPv4 или IPv6 для $host" }
        }
    }
    fun isIp(value: String): Boolean = if (':' in value) value.matches(Regex("[0-9a-fA-F:]+")) && runCatching { java.net.InetAddress.getByName(value) is java.net.Inet6Address }.getOrDefault(false)
        else value.split('.').let { it.size == 4 && it.all { n -> n.isNotEmpty() && n.all(Char::isDigit) && n.toIntOrNull() in 0..255 } }
}
