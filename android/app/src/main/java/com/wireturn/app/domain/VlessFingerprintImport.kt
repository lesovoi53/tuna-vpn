package com.wireturn.app.domain

import com.google.gson.JsonPrimitive
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.*
import java.net.URI
import java.util.Locale

/** Runs at import/save boundaries, never while loading or starting a stored profile. */
object VlessFingerprintImport {
    private val excluded = setOf("chrome", "random", "randomized")
    private fun excluded(value: String) = value.isBlank() || value.trim().lowercase(Locale.ROOT) in excluded
    private fun pick(engine: String, preferred: String?, choose: (List<String>) -> String): String {
        val pool = VlessFingerprintChoices.forEngine(engine)
        require(pool.isNotEmpty()) { "Список рабочих fingerprint ещё не определён" }
        return preferred?.takeIf { it in pool } ?: choose(pool).also { require(it in pool) }
    }

    @Suppress("UNUSED_PARAMETER")
    fun forEditor(value: VpnConfig, explicitChoice: Boolean): VpnConfig = vpn(value)

    fun uri(value: String, engine: String = "xray", preferred: String? = null,
            choose: (List<String>) -> String = { it.random() }): String {
        if (!value.startsWith("vless://", true)) return value
        val head = value.substringBefore('#')
        val parsed = URI(head)
        val parts = parsed.rawQuery?.split('&') ?: return value
        fun key(part: String) = VpnConfig.decode(part.substringBefore('='))
        fun content(part: String) = VpnConfig.decode(part.substringAfter('=', ""))
        if (parts.any { key(it) == "security" && content(it) == "none" }) return value
        val fps = parts.filter { key(it) == "fp" }
        val implicitChrome = fps.isEmpty() && parts.any { key(it) == "security" && content(it) in setOf("tls", "reality") }
        if (!implicitChrome && fps.none { excluded(content(it)) }) return value
        val replacement = pick(engine, preferred, choose)
        val updated = parts.map { if (key(it) == "fp" && excluded(content(it))) it.substringBefore('=') + "=" + replacement else it }.toMutableList()
        if (implicitChrome) updated += "fp=$replacement"
        return head.substringBefore('?') + "?" + updated.joinToString("&") + value.removePrefix(head)
    }

    fun vpn(value: VpnConfig, previous: VpnConfig? = null,
            choose: (List<String>) -> String = { it.random() }): VpnConfig {
        if (value.protocol != "vless") return value
        val engine = CoreCapabilities.resolve(value)
        val preferred = previous?.takeIf { it.protocol == "vless" }?.let(VlessFingerprintChoices::effective)
        val link = uri(value.uri, engine, preferred, choose)
        var options = value.options
        for (target in listOf("sing-box", "xray")) {
            val currentOptions = options ?: continue
            var patch = currentOptions.forEngine(target)
            var changed = false
            val paths = if (target == "sing-box") listOf("tls.utls.fingerprint")
                else listOf("streamSettings.tlsSettings.fingerprint", "streamSettings.realitySettings.fingerprint")
            for (path in paths) {
                val fp = NativeOptions.get(patch, path)
                if (fp?.isJsonPrimitive == true && fp.asJsonPrimitive.isString && excluded(fp.asString)) {
                    val fallback = VlessFingerprintChoices.effective(value.copy(uri = link, engine = target, options = null))
                    patch = NativeOptions.put(patch, path, JsonPrimitive(pick(target, preferred ?: fallback, choose)))
                    changed = true
                }
            }
            if (changed) options = currentOptions.withEngine(target, patch)
        }
        return value.copy(uri = link, options = options)
    }

    fun profile(value: Profile, previous: Profile? = null, normalizeChain: Boolean = true): Profile {
        val kernel = (value.kernelConfig as? KernelConfig.Vpn)?.let {
            KernelConfig.Vpn(vpn(it.config, (previous?.kernelConfig as? KernelConfig.Vpn)?.config))
        } ?: value.kernelConfig
        val oldChain = previous?.vlessConfig?.vlessLink?.takeIf { it.startsWith("vless://", true) }
            ?.let { VlessFingerprintChoices.effective(VpnConfig(it, "xray")) }
        return value.copy(kernelConfig = kernel, vlessConfig = if (!normalizeChain) value.vlessConfig else value.vlessConfig.copy(
            vlessLink = uri(value.vlessConfig.vlessLink, preferred = oldChain)))
    }
}
