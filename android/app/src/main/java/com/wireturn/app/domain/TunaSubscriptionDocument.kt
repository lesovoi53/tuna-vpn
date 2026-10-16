package com.wireturn.app.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Proposed v1 envelope validation only. Never feed unvalidated fields into DataStore. */
data class TunaSubscriptionDocument(
    val id: String,
    val revision: Long,
    val profiles: List<JsonObject>,
    val routing: Map<String, SubscriptionPolicyParser.HappPolicy>,
    val source: JsonObject,
    val unsupportedSettings: Set<String>
) {
    companion object {
        private val implementedSettings get() = SubscriptionSettingsRegistry.fields.keys

        fun parse(text: String, capabilities: Set<String> = emptySet()): TunaSubscriptionDocument {
            require(text.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "Subscription too large" }
            val root = JsonParser.parseString(text).asJsonObject
            fun requiredString(o: JsonObject, key: String): String {
                val v = o.get(key)
                require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.isNotBlank()) { "Missing or invalid $key" }
                return v.asString
            }
            fun integer(o: JsonObject, key: String): Long {
                val v = o.get(key)
                require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isNumber && v.toString().matches(Regex("[0-9]+"))) { "Invalid $key" }
                return v.asString.toLong()
            }
            require(requiredString(root, "schema") == "tuna.subscription") { "Unsupported schema" }
            require(integer(root, "schemaVersion") == 1L) { "Unsupported schema version" }
            val id = requiredString(root, "subscriptionId")
            val revision = integer(root, "revision")
            require(root.get("complete")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } == true) { "Complete snapshot required" }
            val required = root.get("requiredCapabilities")?.asJsonArray?.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString
            }.orEmpty()
            require(capabilities.containsAll(required)) { "Required capabilities not supported" }
            val entries = root.getAsJsonArray("profiles") ?: error("Missing profiles")
            require(entries.size() <= 1000) { "Too many profiles" }
            val profiles = entries.map { item -> item.asJsonObject.also {
                requiredString(it, "id"); requiredString(it, "name"); requiredString(it, "uri")
            }.deepCopy() }
            require(profiles.map { it["id"].asString }.distinct().size == profiles.size) { "Duplicate profile IDs" }
            val policies = linkedMapOf<String, SubscriptionPolicyParser.HappPolicy>()
            root.get("routingPolicies")?.asJsonArray?.forEach { item ->
                val p = item.asJsonObject
                val policyId = requiredString(p, "id")
                require(requiredString(p, "format") == "happ-routing-v1") { "Unsupported policy format" }
                val enabled = p.get("enabled") ?: error("Missing policy enabled")
                require(enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean)
                require(policyId !in policies) { "Duplicate policy ID" }
                policies[policyId] = SubscriptionPolicyParser.parseHapp(requiredString(p, "source"), enabled.asBoolean)
            }
            profiles.forEach { p -> p.get("routingPolicyId")?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString in policies) { "Unknown policy reference" }
            } }
            val unsupported = root.getAsJsonObject("settings")?.let { settings ->
                require(integer(settings, "schemaVersion") == 1L)
                require(requiredString(settings, "scope") == "SUBSCRIPTION")
                require(requiredString(settings, "applyMode") == "MERGE")
                val values = settings.getAsJsonObject("values")?.keySet().orEmpty()
                values.filter { it in implementedSettings }.forEach { path ->
                    SubscriptionSettingsRegistry.validate(path, settings.getAsJsonObject("values")[path])
                }
                val resets = settings.get("resetPaths")?.asJsonArray?.map {
                    require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                    it.asString
                }.orEmpty()
                require(values.intersect(resets.toSet()).isEmpty()) { "Conflicting reset and value" }
                // Resets need ownership tracking; report them until that layer is available.
                values.filter { it !in implementedSettings }.toSet() + resets
            }.orEmpty()
            return TunaSubscriptionDocument(id, revision, profiles, policies, root.deepCopy(), unsupported)
        }
    }
}
