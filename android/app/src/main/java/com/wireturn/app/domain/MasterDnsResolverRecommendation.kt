package com.wireturn.app.domain

import kotlin.math.ceil
import kotlin.math.sqrt

/** A real transfer through a complete pool. Never attribute pool throughput to one resolver. */
data class MasterDnsPoolMeasurement(
    val resolvers: List<String>,
    val payloadBytes: Long,
    val elapsedMs: Long,
    val wireBytes: Long = 0,
    val effectiveParameters: String? = null,
    val error: String? = null
) {
    val successful: Boolean get() = error == null && payloadBytes >= 16 * 1024 && elapsedMs > 0
    val bytesPerSecond: Double get() = if (successful) payloadBytes * 1000.0 / elapsedMs else 0.0
}

/**
 * MTU/RTT ranking is only a shortlist heuristic. The runtime synchronizes MTU to the
 * weakest retained path, so a pool cannot be assembled from RTT alone.
 */
object MasterDnsResolverRecommendation {
    const val MAX_POOLS = 4
    private data class Path(val resolver: String, val upload: Int, val download: Int,
                            val latency: Long, val loss: Double) {
        val score get() = sqrt(upload.toDouble() * download) / latency.coerceAtLeast(1) *
            (1 - loss) * (1 - loss)
    }

    private fun paths(results: List<MasterDnsResolverResult>): List<Path> =
        results.filter { it.valid && it.attempts == 5 && it.successes in 1..5 &&
            it.upload > 0 && it.download > 0 && it.latencyMs >= 0 }
            .mapNotNull { result -> MasterDnsEndpoint.parse(result.resolver)?.canonical?.let { it to result } }
            .groupBy({ it.first }, { it.second })
            .map { (resolver, measurements) ->
                // A multi-domain profile may use every healthy domain/resolver pair.
                Path(resolver, measurements.minOf { it.upload }, measurements.minOf { it.download },
                    measurements.maxOf { it.latencyMs },
                    measurements.maxOf { 1.0 - it.successes.toDouble() / it.attempts })
            }.sortedWith(compareByDescending<Path> { it.score }.thenBy { it.loss }
                .thenBy { it.latency }.thenBy { it.resolver })

    /** Preliminary recommendation, explicitly not a measured speed claim. No fixed top-three cap. */
    fun recommend(results: List<MasterDnsResolverResult>): List<String> {
        val ranked = paths(results)
        val reference = ranked.firstOrNull() ?: return emptyList()
        return ranked.filter {
            it.upload >= reference.upload * 0.8 && it.download >= reference.download * 0.8 &&
                it.loss <= reference.loss + 0.200001 &&
                it.latency <= maxOf(reference.latency * 3, reference.latency + 100)
        }.distinctBy { MasterDnsEndpoint.parse(it.resolver)!!.host }.take(128).map { it.resolver }
    }

    /** Baseline, best single path, middle-sized pool, full compatible pool: at most four trials. */
    fun candidatePools(results: List<MasterDnsResolverResult>, baseline: List<String>): List<List<String>> {
        val recommended = recommend(results)
        if (recommended.isEmpty()) return emptyList()
        val valid = paths(results).map { it.resolver }.toSet()
        val original = baseline.mapNotNull { MasterDnsEndpoint.parse(it)?.canonical }
            .distinctBy { MasterDnsEndpoint.parse(it)!!.host }.filter { it in valid }.take(128)
        return listOf(original, recommended.take(1),
            recommended.take(ceil(sqrt(recommended.size.toDouble())).toInt()), recommended)
            .filter { it.isNotEmpty() }.distinctBy { it.toSet() }.take(MAX_POOLS)
    }

    /** Keep the baseline unless another complete trial improves useful throughput by over 10%. */
    fun winner(measurements: List<MasterDnsPoolMeasurement>): MasterDnsPoolMeasurement? {
        var best: MasterDnsPoolMeasurement? = null
        for (measurement in measurements.filter { it.successful }) {
            val previous = best
            if (previous == null || measurement.bytesPerSecond > previous.bytesPerSecond * 1.10)
                best = measurement
        }
        return best
    }
}
