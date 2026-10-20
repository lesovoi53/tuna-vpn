package com.wireturn.app.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wireturn.app.data.kernel.MasterDnsPoolSelection

/** Device-local references only: credentials and subscription profiles stay in their original store. */
data class MasterDnsGroup(
    val id: String,
    val name: String,
    val memberIds: List<String>,
    val strategy: String = "least_connections",
    val subscriptionId: String? = null,
) {
    fun validate() {
        require(!(id as String?).isNullOrBlank()) { "Не задан идентификатор группы DNS" }
        require(!(name as String?).isNullOrBlank()) { "Введите название группы DNS" }
        selection().validate()
    }

    fun selection() = MasterDnsPoolSelection(memberIds, strategy)

    fun members(profiles: List<Profile>): List<Profile> {
        validate()
        return memberIds.map { id ->
            val profile = requireNotNull(profiles.singleOrNull { it.id == id }) {
                "Сервер группы DNS удалён или неоднозначен: $id"
            }
            require(profile.kernelConfig is KernelConfig.MasterDns) {
                "Сервер «${profile.name}» больше не использует Master DNS"
            }
            require(profile.subscriptionId == subscriptionId) {
                "Серверы группы DNS должны принадлежать одной подписке либо все быть собственными"
            }
            profile
        }
    }
}

internal object MasterDnsGroups {
    val groupsKey = stringPreferencesKey("master_dns_groups_v1")
    val activeKey = stringPreferencesKey("active_master_dns_group_v1")

    fun read(p: Preferences, gson: Gson): List<MasterDnsGroup> =
        gson.fromJson<List<MasterDnsGroup>>(p[groupsKey] ?: "[]",
            object : TypeToken<List<MasterDnsGroup>>() {}.type) ?: emptyList()

    /** Atomic rc7 upgrade; remove only the local membership field, preserving all server settings. */
    fun migrate(p: MutablePreferences, profiles: List<Profile>, gson: Gson) {
        val groups = read(p, gson).toMutableList()
        val selectedId = p[AppPreferences.CURRENT_PROFILE_ID]
        val activeJson = p[AppPreferences.ACTIVE_KERNEL_JSON]?.let { JsonParser.parseString(it).asJsonObject }
        val activePool = activeJson?.getAsJsonObject("masterdns")?.get("localPool")
            ?.takeUnless { it.isJsonNull }?.let { gson.fromJson(it, MasterDnsPoolSelection::class.java) }
        val migrations = profiles.mapNotNull { profile ->
            val config = (profile.kernelConfig as? KernelConfig.MasterDns)?.config ?: return@mapNotNull null
            val selection = if (profile.id == selectedId) activePool ?: config.localPool else config.localPool
            selection?.let { profile to it }
        }
        if (migrations.isEmpty()) return
        for ((profile, selection) in migrations) {
            val id = "masterdns-legacy-${profile.id}"
            if (groups.none { it.id == id }) {
                // Preserve even a now-unavailable member; runtime reports the problem for repair.
                groups += MasterDnsGroup(id, "${profile.name} · DNS", selection.memberIds,
                    selection.strategy, profile.subscriptionId)
            }
            if (profile.id == selectedId && p[activeKey] == null) p[activeKey] = id
        }
        val rawProfiles = JsonParser.parseString(p[AppPreferences.PROFILES_JSON] ?: "[]").asJsonArray
        for (element in rawProfiles) {
            val kernel = element.asJsonObject.getAsJsonObject("kernelConfig")
            if (kernel?.get("type")?.asString == "masterdnsvpn") kernel.getAsJsonObject("config")?.remove("localPool")
        }
        activeJson?.getAsJsonObject("masterdns")?.remove("localPool")
        p[groupsKey] = gson.toJson(groups)
        p[AppPreferences.PROFILES_JSON] = rawProfiles.toString()
        if (activeJson != null) p[AppPreferences.ACTIVE_KERNEL_JSON] = activeJson.toString()
    }
}
