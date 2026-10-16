package com.wireturn.app.domain

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.VpnConfig
import java.util.UUID

/** Full Xray documents are opaque source configs, not flattened URI profiles. */
internal object XrayJsonImport {
    fun recognizes(fields: JsonObject): Boolean = fields.has("outbounds") &&
        !fields.has("kernelConfig") && !fields.has("uri") && !fields.has("profiles") && !fields.has("schema")

    private fun name(fields: JsonObject): String = fields["remarks"]?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString?.trim().orEmpty().take(512)

    private fun balanced(fields: JsonObject): Boolean = fields["routing"]?.takeIf { it.isJsonObject }
        ?.asJsonObject?.get("balancers")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } == true

    private fun nameKey(fields: JsonObject): String = "${balanced(fields)}|${name(fields)}"

    private fun canonical(value: JsonElement): JsonElement = when {
        value.isJsonObject -> JsonObject().apply {
            value.asJsonObject.keySet().sorted().forEach { add(it, canonical(value.asJsonObject[it])) }
        }
        value.isJsonArray -> com.google.gson.JsonArray().apply { value.asJsonArray.forEach { add(canonical(it)) } }
        else -> value.deepCopy()
    }

    fun profile(fields: JsonObject, siblings: List<JsonElement>): Profile {
        val config = VpnConfig(rawXrayJson = fields.toString()).sanitize().also { it.validate() }
        val label = name(fields).ifBlank { if (config.isXrayBalancer) "Балансировщик Xray" else "Xray JSON" }
        val key = nameKey(fields)
        // Unique source labels survive address/credential updates and list reordering. Ambiguous
        // or unnamed entries use complete canonical content: never coalesce different configs.
        val uniqueName = name(fields).isNotBlank() && siblings.count {
            it.isJsonObject && recognizes(it.asJsonObject) && nameKey(it.asJsonObject) == key
        } == 1
        val identity = if (uniqueName) "name:$key" else "content:${canonical(fields)}"
        val id = UUID.nameUUIDFromBytes("tuna:xray-json:$identity".toByteArray(Charsets.UTF_8)).toString()
        return Profile(id = id, name = label, kernelConfig = KernelConfig.Vpn(config))
    }
}
