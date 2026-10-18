package com.wireturn.app.domain

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Immutable per-policy asset directories prevent an update from replacing active databases. */
object SubscriptionRoutingAssets {
    private fun root(context: Context, source: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "subscription-routing/$hash")
    }

    fun directory(context: Context, source: String): File = RoutingAssetStore.current(root(context, source))

    fun validate(context: Context, source: String, effective: com.wireturn.app.data.RoutingPolicy? = null) {
        validateDirectory(directory(context, source), source, effective)
    }

    internal fun validateDirectory(dir: File, source: String, effective: com.wireturn.app.data.RoutingPolicy? = null) {
        val policy = SubscriptionPolicyParser.parseHapp(source)
        require(!policy.fakeDns) { "FakeDNS in subscription routing is not supported yet" }
        require(policy.warnings.none { it.startsWith("Unknown Happ field:") }) { "Unknown routing fields require review" }
        val needed = (effective ?: policy.routing).activeRules().map { it.match.substringBefore('@') }.filter { it.startsWith("geosite:") || it.startsWith("geoip:") }
        for ((file, prefix) in listOf("geosite.dat" to "geosite:", "geoip.dat" to "geoip:")) {
            // The matcher expression keeps its spelling and inversion. Only the lookup
            // into the database index uses the underlying normalized category name.
            val categories = needed.filter { it.startsWith(prefix) }.map(GeoCategoryIndex::categoryOf)
            if (categories.isNotEmpty()) {
                val available = GeoCategoryIndex.read(File(dir, file), prefix)
                require(available.containsAll(categories)) { "Missing categories in $file" }
            }
        }
    }

    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun prepare(context: Context, source: String, force: Boolean = false, effective: com.wireturn.app.data.RoutingPolicy? = null) = withContext(Dispatchers.IO) {
        mutex.lock()
        try { prepareLocked(context, source, force, effective) } finally { mutex.unlock() }
    }

    private suspend fun prepareLocked(context: Context, source: String, force: Boolean, effective: com.wireturn.app.data.RoutingPolicy?) {
        if (!force && runCatching { validateDirectory(directory(context, source), source, effective) }.isSuccess) {
            com.wireturn.app.kernel.RoutingConfigCheck.policy(context, nativePolicy(source, effective), directory(context, source))
            return
        }
        val p = SubscriptionPolicyParser.parseHapp(source)
        val root = root(context, source)
        val dir = RoutingAssetStore.stage(root)
        var published = false
        try {
        for ((name, address, prefix) in listOf(Triple("geoip.dat", p.geoipUrl, "geoip:"), Triple("geosite.dat", p.geositeUrl, "geosite:"))) {
            // Keep advertised databases available for local rules, even if the source
            // currently has no rule using them. Directories remain immutable per revision.
            if (address == null && (effective ?: p.routing).activeRules().none { it.match.startsWith(prefix) }) continue
            val target = File(dir, name)
            require(address != null) { "Missing $name URL" }
            com.wireturn.app.AppLogsState.addLog("Routing: downloading $name")
            RoutingAssetStore.download(address, target)
            require(GeoCategoryIndex.read(target, prefix).isNotEmpty()) { "Invalid $name" }
        }
        validateDirectory(dir, source, effective)
        com.wireturn.app.kernel.RoutingConfigCheck.policy(context, nativePolicy(source, effective), dir)
        RoutingAssetStore.publish(root, dir)
        published = true
        com.wireturn.app.AppLogsState.addLog("Routing: required databases validated")
        } finally { if (!published) dir.deleteRecursively() }
    }

    fun nativePolicy(source: String, effective: com.wireturn.app.data.RoutingPolicy? = null): String {
        val p = SubscriptionPolicyParser.parseHapp(source)
        require(!p.fakeDns) { "Subscription FakeDNS is not supported yet" }
        val root = Gson().toJsonTree(effective ?: p.routing).asJsonObject
        root.addProperty("domainStrategy", p.domainStrategy)
        // Materialize the complete subscription policy, including the explicitly
        // documented system emergency default. Local overrides remain separate.
        val dns = Gson().toJsonTree(SplitDnsSettings.fromPolicy(p))
        root.add("splitDns", dns)
        return root.toString()
    }
}
