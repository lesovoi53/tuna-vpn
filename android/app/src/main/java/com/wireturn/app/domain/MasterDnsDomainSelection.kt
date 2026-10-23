package com.wireturn.app.domain

import com.wireturn.app.data.kernel.MasterDnsConfig

/** Draft choices and persisted choices remain keyed by domain, never by resolver alone. */
object MasterDnsDomainSelection {
    fun profileDomain(config: MasterDnsConfig, domain: String): String? =
        config.domains.firstOrNull { it.equals(domain, ignoreCase = true) }

    fun saved(config: MasterDnsConfig): Map<String, Set<String>> =
        config.domains.associateWith { domain ->
            config.localResolverList(domain).mapNotNull { MasterDnsEndpoint.parse(it)?.canonical }.toSet()
        }

    fun working(results: List<MasterDnsResolverResult>, domain: String): Set<String> = results.asSequence()
        .filter { it.domain.equals(domain, ignoreCase = true) && it.valid && it.attempts == 5 }
        .mapNotNull { MasterDnsEndpoint.parse(it.resolver)?.canonical }
        .distinct().sorted().take(128).toSet()

    fun merge(config: MasterDnsConfig, domain: String, selected: Set<String>): Map<String, String> {
        require(domain in config.domains)
        require(selected.size <= 128)
        return config.domains.associateWith { other ->
            when {
                other == domain -> selected.joinToString("\n")
                config.localDomainResolvers != null -> config.localResolverList(other).joinToString("\n")
                else -> config.localResolverList(other).ifEmpty { config.profileResolverList() }.joinToString("\n")
            }
        }.also { require(it.values.any(String::isNotBlank)) { "Оставьте хотя бы один домен с резолверами" } }
    }
}
