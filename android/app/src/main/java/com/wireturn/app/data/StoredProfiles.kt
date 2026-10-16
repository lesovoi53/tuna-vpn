package com.wireturn.app.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.wireturn.app.data.kernel.CoreCapabilities
import com.wireturn.app.data.kernel.NativeOptions

/** Decode independent persisted records before sanitising their typed configuration. */
internal fun decodeStoredProfiles(json: String, gson: Gson, defaultName: String): List<Profile> {
    val tree = JsonParser.parseString(json)
    if (tree.isJsonNull) return emptyList()
    return tree.asJsonArray.mapNotNull { item ->
        if (!item.isJsonObject) return@mapNotNull null
        try { gson.fromJson(item, Profile::class.java)?.sanitize(defaultName) }
        catch (_: Exception) { null }
    }
}

/** Older reads through Map<String, Any> wrote Double tokens such as 8.0 back to disk.
 * Repair only schema-declared integer fields with an exact, in-range value. Unknown
 * JSON, genuine fractions and out-of-range values stay untouched for validation. */
internal fun Profile.repairStoredIntegerOptions(): Profile {
    val vpn = (kernelConfig as? KernelConfig.Vpn)?.config ?: return this
    var options = vpn.options ?: return this
    for (engine in listOf("xray", "sing-box")) {
        var patch = options.forEngine(engine)
        var changed = false
        for (field in CoreCapabilities.fields(engine, vpn.protocol).filter { it.kind == "int" }) {
            val value = NativeOptions.get(patch, field.path) ?: continue
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber || value.toString().toLongOrNull() != null) continue
            val integer = runCatching { value.asBigDecimal.longValueExact() }.getOrNull() ?: continue
            if (integer !in field.min..field.max) continue
            patch = NativeOptions.put(patch, field.path, JsonPrimitive(integer))
            changed = true
        }
        if (changed) options = options.withEngine(engine, patch)
    }
    return copy(kernelConfig = KernelConfig.Vpn(vpn.copy(options = options)))
}
