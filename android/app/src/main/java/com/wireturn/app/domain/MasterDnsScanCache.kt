package com.wireturn.app.domain

import com.google.gson.Gson
import com.wireturn.app.data.Profile
import com.wireturn.app.data.KernelConfig
import java.io.File
import java.security.MessageDigest

/** A measurement belongs to a profile configuration and one physical-network incarnation. */
data class MasterDnsSavedScan(
    val version: Int = 1,
    val profileStamp: String,
    val networkStamp: String,
    val measuredAt: Long,
    val results: List<MasterDnsResolverResult>,
    val complete: Boolean = true,
    // Nullable for old Gson snapshots. Shares the scan's profile/network/expiry boundary.
    val poolMeasurements: List<MasterDnsPoolMeasurement>? = null,
    val domainMeasuredAt: Map<String, Long>? = null
) {
    fun measurementTimes(): Map<String, Long> = domainMeasuredAt ?: results.map { it.domain }.distinct().associateWith { measuredAt }

    fun accepts(profile: Profile, network: String, now: Long = System.currentTimeMillis()): Boolean =
        version == 1 && profileStamp == MasterDnsScanCache.profileStamp(profile) &&
            networkStamp == network && measuredAt > 0 && now >= measuredAt && now - measuredAt <= MasterDnsScanCache.MAX_AGE_MS
}

object MasterDnsScanCache {
    const val MAX_AGE_MS = 30L * 60 * 1000
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun profileStamp(profile: Profile): String {
        val config = (profile.kernelConfig as? KernelConfig.MasterDns)?.config
            ?: error("Нужен профиль MasterDnsVPN")
        // The local choice does not change the tunnel being measured. Never store its key.
        return digest(profile.id + "\n" + Gson().toJson(config.copy(
            localResolvers = null, localDomainResolvers = null, useLocalResolvers = null, originalJson = ""
        )))
    }

    fun load(directory: File, profile: Profile, network: String, now: Long = System.currentTimeMillis()): MasterDnsSavedScan? {
        val file = File(directory, digest(profile.id + network) + ".json")
        return runCatching {
            require(file.isFile && file.length() <= 1024 * 1024)
            val record = Gson().fromJson(file.readText(), MasterDnsSavedScan::class.java)
            require(record.accepts(profile, network, now))
            validateResults(profile, record.results)
            validatePools(record)
            validateTimes(profile, record)
            val times = record.measurementTimes().filterValues { it > 0 && now >= it && now - it <= MAX_AGE_MS }
            if (times.size == record.measurementTimes().size) record else record.copy(results = record.results.filter { it.domain in times }, domainMeasuredAt = times,
                poolMeasurements = record.poolMeasurements.takeIf { times.keys.containsAll(record.results.map { it.domain }) })
        }.getOrNull()
    }

    fun save(directory: File, profile: Profile, record: MasterDnsSavedScan) {
        require(record.accepts(profile, record.networkStamp))
        validateResults(profile, record.results)
        validatePools(record)
        validateTimes(profile, record)
        directory.mkdirs()
        val file = File(directory, digest(profile.id + record.networkStamp) + ".json")
        val temporary = File.createTempFile("scan-", ".tmp", directory)
        try {
            temporary.writeText(Gson().toJson(record))
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            // Network incarnations have distinct keys. Bound old result storage.
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
                ?.drop(32)?.forEach { it.delete() }
        } finally {
            temporary.delete()
        }
    }

    private fun validateResults(profile: Profile, results: List<MasterDnsResolverResult>) {
        require(results.size <= 2048)
        val domains = (profile.kernelConfig as KernelConfig.MasterDns).config.domains.map { it.lowercase(java.util.Locale.ROOT) }
        val keys = HashSet<String>()
        for (result in results) {
            // Gson can place null entries/fields in non-null Kotlin types; each dereference is
            // inside the load boundary's runCatching, never in UI rendering.
            require(MasterDnsEndpoint.parse(result.resolver) != null && result.domain.lowercase(java.util.Locale.ROOT) in domains)
            require(keys.add(result.resolver + "\n" + result.domain))
            require(result.upload in 0..65535 && result.download in 0..65535)
            require(result.attempts in 0..5 && result.successes in 0..result.attempts)
            require(result.latencyMs in 0..180_000 && result.mtuCheckMs in 0..180_000)
            if (result.valid) require(result.attempts == 5 && result.successes > 0 && result.upload > 0 && result.download > 0)
        }
    }

    private fun validateTimes(profile: Profile, record: MasterDnsSavedScan) {
        val times = record.domainMeasuredAt ?: return
        val domains = (profile.kernelConfig as KernelConfig.MasterDns).config.domains
        require(times.keys.all { it in domains })
        require(times.values.all { it > 0 && it <= record.measuredAt })
    }

    private fun validatePools(record: MasterDnsSavedScan) {
        val pools = record.poolMeasurements ?: return
        require(pools.size <= MasterDnsResolverRecommendation.MAX_POOLS)
        if (pools.isNotEmpty()) require(record.results.map { it.domain }.distinct().size == 1)
        val valid = record.results.filter { it.valid }
            .map { requireNotNull(MasterDnsEndpoint.parse(it.resolver)).canonical }.toSet()
        val seen = HashSet<Set<String>>()
        for (pool in pools) {
            require(pool.resolvers.size in 1..128 && pool.resolvers.distinct().size == pool.resolvers.size)
            require(pool.resolvers.all { it in valid } && seen.add(pool.resolvers.toSet()))
            require(pool.payloadBytes in 0..262144 && pool.elapsedMs in 0..210000)
            require(pool.wireBytes in 0..16L * 1024 * 1024)
            require(pool.effectiveParameters == null || pool.effectiveParameters.length <= 4096)
            require(pool.error == null || pool.error.length <= 200)
            require(pool.error != null || pool.successful)
        }
    }
}
