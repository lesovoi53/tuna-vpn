package com.wireturn.app.domain

import com.google.gson.JsonObject
import com.wireturn.app.data.*
import java.util.UUID

object SubscriptionGroups {
    const val CAPABILITY = "connection-groups-v1"
    internal fun localId(subscriptionId: String, externalId: String): String =
        UUID.nameUUIDFromBytes("tuna-sub-group:$subscriptionId:$externalId".toByteArray()).toString()

    fun parse(document: JsonObject, subscriptionId: String, profiles: List<Profile>): List<ConnectionGroup> {
        val entries = document.getAsJsonArray("groups") ?: return emptyList()
        require(entries.size() <= 100) { "Too many groups (maximum 100)" }
        if (entries.size() == 0) return emptyList()
        val subscriptionProfiles = profiles.filter { it.subscriptionId == subscriptionId && it.subscriptionSourceId != null }
        require(subscriptionProfiles.map { it.subscriptionSourceId }.distinct().size == subscriptionProfiles.size) {
            "Ambiguous group member ID"
        }
        val mapping = subscriptionProfiles.associateBy { it.subscriptionSourceId!! }.toMutableMap()
        document.getAsJsonArray("profiles")?.forEach { item ->
            val entry = item.asJsonObject
            val externalId = entry["id"].asString
            val uri = entry["uri"].asString
            if (uri.substringBefore(':').lowercase() in setOf("wg", "awg", "wireguard", "vpn")) {
                val vpn = com.wireturn.app.data.kernel.VpnConfig.parse(uri)
                val key = vpn?.let { com.wireturn.app.data.kernel.AwgUri.accountIdentity(it.uri) }
                val matches = subscriptionProfiles.filter { p -> (p.kernelConfig as? KernelConfig.Vpn)?.config?.let {
                    it.protocol in setOf("awg", "wireguard") && com.wireturn.app.data.kernel.AwgUri.accountIdentity(it.uri) == key
                } == true }
                if (matches.size == 1) mapping[externalId] = matches.single()
            }
        }
        val ids = mutableSetOf<String>()
        return entries.mapNotNull { entry ->
            val o = entry.asJsonObject
            fun str(key: String): String { val v = o[key]; require(v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString); return v.asString }
            fun num(key: String, default: Int): Int = o[key]?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.toString().matches(Regex("[0-9]+")))
                it.asString.toInt()
            } ?: default
            fun bool(key: String, default: Boolean): Boolean = o[key]?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean } ?: default
            // Retired test groups are ignored before resolving members or legacy parameters.
            val type = GroupTestType.valueOf(str("type"))
            if (type != GroupTestType.URL_TEST) return@mapNotNull null
            val externalId = str("id"); require(externalId.isNotBlank() && ids.add(externalId))
            val memberIds = o.getAsJsonArray("memberIds").map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Invalid group member ID" }
                requireNotNull(mapping[it.asString]) { "Group member unavailable" }.id
            }
            val distinctMemberIds = memberIds.distinct()
            // Two wire formats of one AWG account are not two failover candidates.
            // Retain the group visibly for repair instead of rejecting the entire subscription.
            val repair = bool("repairRequired", false) || (distinctMemberIds.size < 2 && memberIds.size > distinctMemberIds.size)
            val anchor = o.get("routingProfileId")?.let {
                val source = str("routingProfileId")
                if (repair && source.isEmpty()) "" else requireNotNull(mapping[source]).id
            } ?: memberIds.firstOrNull().orEmpty()
            ConnectionGroup(
                id = localId(subscriptionId, externalId),
                name = str("name"), category = ConnectionCategory.valueOf(str("category")), type = type, memberIds = distinctMemberIds,
                intervalSeconds = num("intervalSeconds", 300), timeoutSeconds = num("timeoutSeconds", 8),
                holdSeconds = num("holdSeconds", 120), confirmations = num("confirmations", 2), improvementMs = num("improvementMs", 50),
                improvementPercent = num("improvementPercent", 20), maxBytesPerCandidate = num("maxBytesPerCandidate", 10485760).toLong(),
                automatic = bool("automatic", false) && !repair, repairRequired = repair, testUrl = str("testUrl"), expectedStatus = num("expectedStatus", if (type == GroupTestType.URL_TEST) 204 else 200),
                samples = num("samples", 3), durationSeconds = num("durationSeconds", 5), allowMobile = bool("allowMobile", false),
                testOnConnect = bool("testOnConnect", type == GroupTestType.URL_TEST) && !repair, freshnessSeconds = num("freshnessSeconds", 600),
                selectionMode = o.get("selectionMode")?.let { GroupSelectionMode.valueOf(str("selectionMode")) },
                family = o.get("family")?.let {
                    val family = GroupFamily.entries.firstOrNull { it.name == str("family") }
                    require(family != null || repair) { "Unsupported group family" }
                    // Infer a repair-required group's family from supported members only.
                    family ?: profiles.filter { it.id in distinctMemberIds }
                        .mapNotNull { it.groupFamily }.distinct().singleOrNull()
                },
                routingProfileId = anchor, probeMethod = o.get("probeMethod")?.let { GroupProbeMethod.valueOf(str("probeMethod")) } ?: GroupProbeMethod.GET
            ).also { group ->
                group.validate(profiles, allowRepair = true)
            }
        }
    }
}
