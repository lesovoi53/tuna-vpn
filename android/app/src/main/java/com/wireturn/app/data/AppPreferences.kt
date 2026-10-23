package com.wireturn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.wireturn.app.R
import com.wireturn.app.data.kernel.FreeTurnConfig
import com.wireturn.app.data.kernel.OlcrtcConfig
import com.wireturn.app.data.kernel.QwdttConfig
import com.wireturn.app.data.kernel.TurnableConfig
import com.wireturn.app.data.kernel.WebdavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import java.io.StringReader

private val Context.rawAppDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_prefs",
    produceMigrations = { listOf(com.wireturn.app.data.security.EncryptedPreferencesMigration(
        com.wireturn.app.data.security.AppSecretPreferences.codec)) }
)
private val Context.internalDataStore: DataStore<Preferences>
    get() = com.wireturn.app.data.security.AppSecretPreferences.wrap(rawAppDataStore)

class SafeEnumTypeAdapterFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val rawType = type.rawType
        if (!rawType.isEnum) return null
        val constants = rawType.enumConstants as Array<T>
        val delegate = gson.getDelegateAdapter(this, type)
        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) = delegate.write(out, value)
            override fun read(reader: JsonReader): T? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                if (reader.peek() != JsonToken.STRING) {
                    reader.skipValue()
                    return constants.firstOrNull()
                }
                val name = reader.nextString()
                return try {
                    delegate.read(JsonReader(StringReader("\"$name\"")))
                } catch (_: Exception) {
                    constants.firstOrNull()
                }
            }
        }.nullSafe()
    }
}

class AppPreferences(val context: Context) {
    val tunMtuFlow: Flow<Int> = context.applicationContext.internalDataStore.data
        .map(com.wireturn.app.domain.TunMtuPolicy::read).distinctUntilChanged()
    suspend fun setTunMtu(value: Int) {
        context.applicationContext.internalDataStore.edit { com.wireturn.app.domain.TunMtuPolicy.write(it, value) }
    }

    private val defaultFragmentationKey = stringPreferencesKey("vpn_default_fragmentation_v1")
    val defaultFragmentationFlow: Flow<com.wireturn.app.data.kernel.VpnFragmentation?> = context.applicationContext.internalDataStore.data
        .map { p -> p[defaultFragmentationKey]?.let { json ->
            Gson().fromJson(json, com.wireturn.app.data.kernel.VpnFragmentation::class.java).also { it.validate() }
        } }.distinctUntilChanged()
    suspend fun setDefaultFragmentation(value: com.wireturn.app.data.kernel.VpnFragmentation?) {
        value?.validate()
        appCtx.internalDataStore.edit { p -> if (value == null) p.remove(defaultFragmentationKey) else p[defaultFragmentationKey] = Gson().toJson(value) }
    }
    private val interfaceStyleKey = stringPreferencesKey("interface_style_v1")
    val interfaceStyleFlow: Flow<InterfaceStyle> = context.applicationContext.internalDataStore.data
        .map { InterfaceStyle.fromStored(it[interfaceStyleKey]) }.distinctUntilChanged()
    suspend fun setInterfaceStyle(style: InterfaceStyle) {
        appCtx.internalDataStore.edit { it[interfaceStyleKey] = style.name }
    }
    private val favoriteProfilesKey = stringSetPreferencesKey("profile_favorites_v1")
    private val profileSortKey = stringPreferencesKey("profile_sort_v1")
    val favoriteProfilesFlow: Flow<Set<String>> = context.applicationContext.internalDataStore.data.map { it[favoriteProfilesKey] ?: emptySet() }
    val profileSortFlow: Flow<com.wireturn.app.domain.ProfileSortOrder> = context.applicationContext.internalDataStore.data.map {
        runCatching { com.wireturn.app.domain.ProfileSortOrder.valueOf(it[profileSortKey].orEmpty()) }
            .getOrDefault(com.wireturn.app.domain.ProfileSortOrder.ORIGINAL)
    }
    suspend fun toggleFavoriteProfile(profile: Profile) {
        appCtx.internalDataStore.edit { p ->
            val favorites = p[favoriteProfilesKey].orEmpty()
            val current = readProfiles(p).singleOrNull { it.id == profile.id }
            p[favoriteProfilesKey] = com.wireturn.app.domain.ProfileListOrder.toggled(current, profile, favorites)
        }
    }
    suspend fun setProfileSort(order: com.wireturn.app.domain.ProfileSortOrder) {
        appCtx.internalDataStore.edit { it[profileSortKey] = order.name }
    }
    private val groupsKey = stringPreferencesKey("connection_groups_v1")
    private val activeGroupKey = stringPreferencesKey("active_connection_group_v1")
    val activeConnectionGroupFlow = context.applicationContext.internalDataStore.data.map { it[activeGroupKey] }
    suspend fun setActiveConnectionGroup(id: String?) {
        appCtx.internalDataStore.edit {
            if (id == null) it.remove(activeGroupKey) else {
                it[activeGroupKey] = id
                it.remove(MasterDnsGroups.activeKey)
            }
        }
    }
    val connectionGroupsFlow: Flow<List<ConnectionGroup>> = context.applicationContext.internalDataStore.data.map { p ->
        decodeStoredGroups(p[groupsKey] ?: "[]", readProfiles(p))
    }
    suspend fun saveConnectionGroups(groups: List<ConnectionGroup>) {
        val profiles = profilesFlow.first()
        require(groups.map { it.id }.distinct().size == groups.size)
        val previous = connectionGroupsFlow.first().associateBy { it.id }
        groups.filter { previous[it.id] != it }.forEach { it.validate(profiles) }
        appCtx.internalDataStore.edit { it[groupsKey] = Gson().toJson(groups) }
    }
    private val appCtx = context.applicationContext
    private val gson = GsonBuilder()
        .registerTypeAdapterFactory(SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter())
        .create()

    companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val PROFILES_JSON = stringPreferencesKey("profiles_json")
        val SUBSCRIPTIONS_JSON = stringPreferencesKey("subscriptions_json")
        val PROFILE_COUNTRIES_JSON = stringPreferencesKey("profile_countries_json")
        val CURRENT_PROFILE_ID = stringPreferencesKey("current_profile_id")
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val VPN_ENABLED = booleanPreferencesKey("proxy_vpn_mode")
        val VPN_HIDE_SYSTEM_APPS = booleanPreferencesKey("vpn_hide_system_apps")
        val VPN_BYPASS_MODE = booleanPreferencesKey("vpn_bypass_mode")
        val VPN_FILTERING_ENABLED = booleanPreferencesKey("vpn_filtering_enabled")
        val VPN_GROUP_APPS_BY_LETTER = booleanPreferencesKey("vpn_group_apps_by_letter")
        val VPN_EXCLUDED_APPS = stringSetPreferencesKey("proxy_excluded_apps")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val PING_URL = stringPreferencesKey("ping_url")
        val COUNTRY_DETECTION_METHOD = stringPreferencesKey("country_detection_method")
        val AUTO_LAUNCH_ENABLED = booleanPreferencesKey("auto_launch_enabled")
        val AUTO_LAUNCH_URL = stringPreferencesKey("auto_launch_url")
        val AUTO_LAUNCH_INTERVAL = intPreferencesKey("auto_launch_interval")
        val VLESS_LINK_HISTORY = stringPreferencesKey("vless_link_history")
        // Legacy key retained across upgrades. Dismisses battery advice only;
        // Android notification permission is checked independently.
        val BATTERY_NOTIFICATION_DISMISSED = booleanPreferencesKey("battery_notification_dismissed")
        val APPS_EXCLUSION_HINT_SHOWN = booleanPreferencesKey("apps_exclusion_hint_shown")
        val ALLOW_UNSTABLE_UPDATES = booleanPreferencesKey("allow_unstable_updates")
        val WAIT_FOR_NETWORK = booleanPreferencesKey("wait_for_network")
        val RESTART_ON_NETWORK_CHANGE = booleanPreferencesKey("restart_on_network_change")
        val KEEP_CPU_AWAKE = booleanPreferencesKey("keep_cpu_awake")
        val CAPTCHA_STYLE_MOD = booleanPreferencesKey("captcha_style_mod")
        val CAPTCHA_FORCE_TINT = booleanPreferencesKey("captcha_force_tint")
        val PRIVACY_MODE = booleanPreferencesKey("privacy_mode")
        val PROFILES_GESTURE_ENABLED = booleanPreferencesKey("profiles_gesture_enabled")
        val GO_DNS_GO = booleanPreferencesKey("go_dns_go")
        val USE_CUSTOM_CERTS = booleanPreferencesKey("use_custom_certs")

        val CLIENT_LISTEN_ADDR = stringPreferencesKey("client_listen_addr")
        val CLIENT_DNS = stringPreferencesKey("client_dns")
        val CLIENT_SOCKS_ADDR = stringPreferencesKey("client_socks_addr")
        val CLIENT_SOCKS_AUTH_ENABLED = booleanPreferencesKey("client_socks_auth_enabled_v2")
        val CLIENT_SOCKS_USER = stringPreferencesKey("client_socks_user")
        val CLIENT_SOCKS_PASS = stringPreferencesKey("client_socks_pass")
        // Legacy keys — shared by every SOCKS5-native kernel (OLCRTC/WEBDAV/QWDTT), not OLCRTC-only;
        // renamed to CLIENT_SOCKS_* above. Used only for migration on first launch after update.
        private val LEGACY_OLCRTC_SOCKS_ADDR = stringPreferencesKey("olcrtc_socks_addr")
        private val LEGACY_OLCRTC_SOCKS_AUTH_ENABLED = booleanPreferencesKey("olcrtc_socks_auth_enabled")
        private val LEGACY_OLCRTC_SOCKS_USER = stringPreferencesKey("olcrtc_socks_user")
        private val LEGACY_OLCRTC_SOCKS_PASS = stringPreferencesKey("olcrtc_socks_pass")
        val XRAY_SOCKS_BIND = stringPreferencesKey("xray_socks_bind")
        val XRAY_HTTP_BIND = stringPreferencesKey("xray_http_bind")
        val XRAY_AUTH_ENABLED = booleanPreferencesKey("xray_auth_enabled_v2")
        val XRAY_USER = stringPreferencesKey("xray_user")
        val XRAY_PASS = stringPreferencesKey("xray_pass")
        val XRAY_DNS = stringPreferencesKey("xray_dns")
        val XRAY_ROUTING_POLICY = stringPreferencesKey("xray_routing_policy")
        val XRAY_BYPASS_LAN = booleanPreferencesKey("xray_bypass_lan")
        private val ROUTING_DISABLED_BY_USER = booleanPreferencesKey("routing_disabled_after_confirmation_v1")
        val XRAY_HAPP_SOURCE = stringPreferencesKey("xray_happ_source")
        val XRAY_SPLIT_DNS = stringPreferencesKey("xray_split_dns")
        val XRAY_ROUTE_DIRECT = stringPreferencesKey("xray_route_direct")
        val XRAY_ROUTE_BLOCK = stringPreferencesKey("xray_route_block")
        val XRAY_FAKEDNS = booleanPreferencesKey("xray_fakedns")
        val XRAY_GEO_VARIANT = stringPreferencesKey("xray_geo_variant")

        val ACTIVE_KERNEL_JSON = stringPreferencesKey("active_kernel_json")
        val ACTIVE_XRAY_CONFIG_TYPE = stringPreferencesKey("active_xray_config_type")
        val ACTIVE_XRAY_ENABLED = booleanPreferencesKey("active_xray_enabled")
        val ACTIVE_WG_JSON = stringPreferencesKey("active_wg_json")
        val ACTIVE_VLESS_JSON = stringPreferencesKey("active_vless_json")
    }

    private fun <T> Flow<Preferences>.mapPref(key: Preferences.Key<T>, def: T): Flow<T> =
        this.map { it[key] ?: def }.distinctUntilChanged()

    val onboardingDoneFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(ONBOARDING_DONE, false)

    suspend fun hasActiveProfile(): Boolean =
        appCtx.internalDataStore.data.map { it[ACTIVE_KERNEL_JSON] != null }.first()

    val themeModeFlow: Flow<ThemeMode> = appCtx.internalDataStore.data
        .map { ThemeMode.valueOf(it[THEME_MODE] ?: ThemeMode.SYSTEM.name) }
        .distinctUntilChanged()

    val dynamicThemeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(DYNAMIC_THEME, true)
    val vpnSettingsFlow: Flow<VpnSettings> = appCtx.internalDataStore.data
        .map {
            VpnSettings(
                enabled = it[VPN_ENABLED] ?: true,
                hideSystemApps = it[VPN_HIDE_SYSTEM_APPS] ?: false,
                bypassMode = it[VPN_BYPASS_MODE] ?: true,
                filteringEnabled = it[VPN_FILTERING_ENABLED] ?: true,
                groupAppsByLetter = it[VPN_GROUP_APPS_BY_LETTER] ?: true,
                excludedApps = it[VPN_EXCLUDED_APPS] ?: emptySet()
            )
        }.distinctUntilChanged()

    val batteryNotificationDismissedFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(BATTERY_NOTIFICATION_DISMISSED, false)
    val appsExclusionHintShownFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(APPS_EXCLUSION_HINT_SHOWN, false)
    val allowUnstableUpdatesFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(ALLOW_UNSTABLE_UPDATES, false)
    val waitForNetworkFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(WAIT_FOR_NETWORK, true)
    val restartOnNetworkChangeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(RESTART_ON_NETWORK_CHANGE, false)
    val keepCpuAwakeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(KEEP_CPU_AWAKE, false)
    suspend fun setKeepCpuAwake(value: Boolean) {
        appCtx.internalDataStore.edit { it[KEEP_CPU_AWAKE] = value }
    }
    val captchaStyleModFlow: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)
    val captchaForceTintFlow: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)
    val privacyModeFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(PRIVACY_MODE, false)
    val profilesGestureEnabledFlow: Flow<Boolean> = appCtx.internalDataStore.data.mapPref(PROFILES_GESTURE_ENABLED, true)
    val appLanguageFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(APP_LANGUAGE, "system")
    // Blank = the built-in default (see MainViewModel.DEFAULT_PING_URL) - kept blank here rather
    // than baking the URL into this default, same fallback shape as ClientConfig.dns/hcDestination.
    val pingUrlFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(PING_URL, "")
    // "auto" (default), "ipwhois", "ipsb", "ipapico", "ipinfo", or "cloudflare" - a real setting
    // (not hardcoded) so more methods can be added later without a migration.
    val countryDetectionMethodFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(COUNTRY_DETECTION_METHOD, "auto")

    private fun readProfiles(p: Preferences): List<Profile> {
        val json = p[PROFILES_JSON] ?: "[]"
        return try {
            val defaultName = appCtx.getString(R.string.profile_default_name)
            decodeStoredProfiles(json, gson, defaultName)
        } catch (e: Exception) {
            com.wireturn.app.AppLogsState.addLog("Error loading profiles: ${e.message}")
            emptyList()
        }
    }

    private fun readSubscriptions(p: Preferences): List<Subscription> {
        val json = p[SUBSCRIPTIONS_JSON] ?: "[]"
        return try {
            (gson.fromJson<List<Subscription>>(json, object : TypeToken<List<Subscription>>() {}.type) ?: emptyList())
                .map { it.copy(name = com.wireturn.app.domain.SubscriptionTitle.decode(it.name) ?: "Подписка") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val profilesFlow: Flow<List<Profile>> = appCtx.internalDataStore.data
        .map(::readProfiles).distinctUntilChanged()

    val subscriptionsFlow: Flow<List<Subscription>> = appCtx.internalDataStore.data
        .map(::readSubscriptions).distinctUntilChanged()

    internal suspend fun readSubscriptionImportSnapshot(): SubscriptionImportSnapshot {
        return subscriptionImportSnapshot(appCtx.internalDataStore.data.first())
    }

    private fun subscriptionImportSnapshot(preferences: Preferences): SubscriptionImportSnapshot {
        return SubscriptionImportSnapshot(
            profiles = readProfiles(preferences),
            subscriptions = readSubscriptions(preferences),
            currentProfileId = preferences[CURRENT_PROFILE_ID] ?: "default",
            profilesJson = preferences[PROFILES_JSON] ?: "[]",
            subscriptionsJson = preferences[SUBSCRIPTIONS_JSON] ?: "[]",
            protectedProfileIds = SubscriptionImportCommit.protectedIds(preferences, readProfiles(preferences), gson) +
                setOfNotNull(com.wireturn.app.CoreServiceState.session.value?.profileId),
        )
    }

    internal suspend fun commitSubscriptionImport(
        snapshot: SubscriptionImportSnapshot,
        profiles: List<Profile>,
        subscriptions: List<Subscription>,
        routingSource: String?,
        appRouting: com.wireturn.app.domain.AppExclusionsLink.Command? = null,
        routingSubscriptionId: String? = null,
    ) {
        appCtx.internalDataStore.edit { preferences ->
            SubscriptionImportCommit.write(preferences, snapshot, profiles, subscriptions, routingSource, gson, routingSubscriptionId)
            appRouting?.let { AppRoutingLists.apply(preferences, it) }
            // Keep the group's shared proxy chain in the same committed revision as its
            // refreshed real anchor. Missing members remain an explicit runtime error.
            if (preferences[MasterDnsGroups.activeKey] != null) {
                readProfiles(preferences).singleOrNull { it.id == preferences[CURRENT_PROFILE_ID] }?.let { anchor ->
                    ProfileActivation.write(preferences, anchor.id, anchor, kernelSnapshotOf(anchor.kernelConfig), gson)
                }
            }
        }
    }

    /** Last known tunnel-exit country (ISO-3166 alpha-2) per profile id, from the most recent successful connection. */
    val profileCountriesFlow: Flow<Map<String, String>> = appCtx.internalDataStore.data
        .map { p ->
            try {
                gson.fromJson<Map<String, String>>(
                    p[PROFILE_COUNTRIES_JSON] ?: "{}",
                    object : TypeToken<Map<String, String>>() {}.type
                ) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }.distinctUntilChanged()

    val currentProfileIdFlow: Flow<String> = appCtx.internalDataStore.data.mapPref(CURRENT_PROFILE_ID, "default")

    val masterDnsGroupsFlow: Flow<List<MasterDnsGroup>> = appCtx.internalDataStore.data
        .map { MasterDnsGroups.read(it, gson) }.distinctUntilChanged()
    val activeMasterDnsGroupIdFlow: Flow<String?> = appCtx.internalDataStore.data
        .map { it[MasterDnsGroups.activeKey] }.distinctUntilChanged()

    suspend fun saveMasterDnsGroup(group: MasterDnsGroup) {
        group.validate()
        appCtx.internalDataStore.edit { p ->
            val groups = MasterDnsGroups.read(p, gson)
            val replacement = groups.filterNot { it.id == group.id } + group.copy(name = group.name.trim())
            val snapshot = runtimeSnapshotForMasterDnsGroup(p, group, replacement)
            snapshot.runtimeConfig()
            p[MasterDnsGroups.groupsKey] = gson.toJson(replacement)
            if (p[MasterDnsGroups.activeKey] == group.id) {
                val anchor = group.members(readProfiles(p)).first()
                ProfileActivation.write(p, anchor.id, anchor, kernelSnapshotOf(anchor.kernelConfig), gson)
            }
        }
    }

    suspend fun deleteMasterDnsGroup(id: String) {
        appCtx.internalDataStore.edit { p ->
            val groups = MasterDnsGroups.read(p, gson)
            p[MasterDnsGroups.groupsKey] = gson.toJson(groups.filterNot { it.id == id })
            if (p[MasterDnsGroups.activeKey] == id) {
                p.remove(MasterDnsGroups.activeKey)
                val anchor = readProfiles(p).singleOrNull { it.id == p[CURRENT_PROFILE_ID] }
                if (anchor != null) {
                    ProfileActivation.write(p, anchor.id, anchor, kernelSnapshotOf(anchor.kernelConfig), gson)
                } else {
                    p.remove(CURRENT_PROFILE_ID)
                    p.remove(ACTIVE_KERNEL_JSON)
                    p.remove(ACTIVE_XRAY_CONFIG_TYPE)
                    p.remove(ACTIVE_XRAY_ENABLED)
                    p.remove(ACTIVE_WG_JSON)
                    p.remove(ACTIVE_VLESS_JSON)
                }
            }
        }
    }

    suspend fun activateMasterDnsGroup(id: String): Profile {
        var activated: Profile? = null
        appCtx.internalDataStore.edit { p ->
            val groups = MasterDnsGroups.read(p, gson)
            val group = requireNotNull(groups.singleOrNull { it.id == id }) { "Группа DNS удалена или неоднозначна" }
            runtimeSnapshotForMasterDnsGroup(p, group, groups).runtimeConfig()
            val anchor = group.members(readProfiles(p)).first()
            ProfileActivation.write(p, anchor.id, anchor, kernelSnapshotOf(anchor.kernelConfig), gson)
            p[MasterDnsGroups.activeKey] = group.id
            p.remove(activeGroupKey)
            anchor.subscriptionId?.let { SubscriptionEdit.ActiveProfile(it, anchor.id).commit(p) }
            activated = anchor
        }
        return requireNotNull(activated)
    }

    suspend fun runtimeSnapshotForMasterDnsGroup(id: String): com.wireturn.app.domain.RuntimeClientSnapshot {
        val p = appCtx.internalDataStore.data.first()
        val groups = MasterDnsGroups.read(p, gson)
        val group = requireNotNull(groups.singleOrNull { it.id == id }) { "Группа DNS удалена или неоднозначна" }
        return runtimeSnapshotForMasterDnsGroup(p, group, groups)
    }

    private fun runtimeSnapshotForMasterDnsGroup(p: Preferences, group: MasterDnsGroup,
                                                groups: List<MasterDnsGroup>): com.wireturn.app.domain.RuntimeClientSnapshot =
        com.wireturn.app.domain.RuntimeClientSnapshot.resolve(
            readClientConfig(p), group.memberIds.firstOrNull().orEmpty(), readProfiles(p), readSubscriptions(p),
            NetworkPreferences.read(p), p[defaultFragmentationKey]?.let {
                Gson().fromJson(it, com.wireturn.app.data.kernel.VpnFragmentation::class.java).also { it.validate() }
            }, groups, group.id)

    suspend fun migrateRoutingOwnership() {
        appCtx.internalDataStore.edit { p ->
            MasterDnsGroups.migrate(p, readProfiles(p), gson)
            val subscriptions = readSubscriptions(p)
            val original = NetworkPreferences.read(p)
            val common = com.wireturn.app.domain.ProfileRouting.common(original, subscriptions)
            if (common != original) NetworkPreferences.write(p, common)
            p.remove(androidx.datastore.preferences.core.stringPreferencesKey("network_router"))
            if (subscriptions.any { it.useSubscriptionRouting == null }) {
                p[SUBSCRIPTIONS_JSON] = gson.toJson(subscriptions.map { it.copy(useSubscriptionRouting = com.wireturn.app.domain.ProfileRouting.enabled(it)) })
            }
        }
    }

    suspend fun setSubscriptionRouting(id: String, enabled: Boolean) {
        val before = readSubscriptionImportSnapshot()
        val sub = requireNotNull(before.subscriptions.find { it.id == id })
        if (enabled) {
            require(!sub.routingSource.isNullOrBlank()) { "Подписка не содержит правил маршрутизации" }
            com.wireturn.app.domain.SubscriptionRoutingAssets.prepare(appCtx, sub.routingSource,
                effective = com.wireturn.app.domain.SubscriptionRuleOverrides.load(sub.routingOverridesJson)
                    .apply(com.wireturn.app.domain.SubscriptionPolicyParser.parseHapp(sub.routingSource).routing))
        }
        appCtx.internalDataStore.edit { p ->
            check((p[SUBSCRIPTIONS_JSON] ?: "[]") == before.subscriptionsJson) { "Подписка обновилась. Повторите действие" }
            p[SUBSCRIPTIONS_JSON] = gson.toJson(before.subscriptions.map {
                if (it.id == id) it.copy(useSubscriptionRouting = enabled) else it
            })
        }
    }
    suspend fun setSubscriptionRuleOverrides(id: String, expectedSource: String, expectedOverrides: String?, overrides: com.wireturn.app.domain.SubscriptionRuleOverrides) {
        val json = overrides.toJson()
        com.wireturn.app.domain.SubscriptionRuleOverrides.load(json)
        val policy = overrides.apply(com.wireturn.app.domain.SubscriptionPolicyParser.parseHapp(expectedSource).routing)
        com.wireturn.app.domain.SubscriptionRoutingAssets.prepare(appCtx, expectedSource, effective = policy)
        val directory = com.wireturn.app.domain.SubscriptionRoutingAssets.directory(appCtx, expectedSource)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for ((file, prefix) in listOf("geosite.dat" to "geosite:", "geoip.dat" to "geoip:")) {
                val needed = policy.activeRules().filter { it.match.startsWith(prefix) }.map { com.wireturn.app.domain.GeoCategoryIndex.categoryOf(it.match) }
                if (needed.isNotEmpty()) require(com.wireturn.app.domain.GeoCategoryIndex.read(java.io.File(directory, file), prefix).containsAll(needed)) {
                    "Категория отсутствует в базе подписки. Сначала обновите базы или выберите другое правило."
                }
            }
        }
        appCtx.internalDataStore.edit { p ->
            val subscriptions = readSubscriptions(p)
            val current = requireNotNull(subscriptions.find { it.id == id }) { "Подписка удалена" }
            check(current.routingSource == expectedSource && current.routingOverridesJson == expectedOverrides) { "Правила обновились. Повторите действие" }
            p[SUBSCRIPTIONS_JSON] = gson.toJson(subscriptions.map { if (it.id == id) it.copy(routingOverridesJson = json) else it })
        }
    }

    val currentProfileNameFlow: Flow<String?> = appCtx.internalDataStore.data.map { p ->
        p[MasterDnsGroups.activeKey]?.let { id -> MasterDnsGroups.read(p, gson).singleOrNull { it.id == id }?.name }
            ?: readProfiles(p).find { it.id == p[CURRENT_PROFILE_ID] }?.name
    }.distinctUntilChanged()

    val vlessLinkHistoryFlow: Flow<List<String>> = appCtx.internalDataStore.data
        .map { p ->
            (p[VLESS_LINK_HISTORY] ?: "").split("|").filter { it.isNotBlank() }
        }

    val autoLaunchSettingsFlow: Flow<AutoLaunchSettings> = appCtx.internalDataStore.data
        .map(AutoLaunchPreferences::read).distinctUntilChanged()

    val clientConfigFlow: Flow<ClientConfig> = appCtx.internalDataStore.data
        .map(::readClientConfig).distinctUntilChanged()

    private fun readClientConfig(p: Preferences): ClientConfig {
        val kernelConfig = decodeStoredKernelSnapshot(p[ACTIVE_KERNEL_JSON], gson)
        return ClientConfig(
            listenAddr = p[CLIENT_LISTEN_ADDR] ?: ClientConfig.DEFAULT_LISTEN_ADDR,
            socksAddr = p[CLIENT_SOCKS_ADDR] ?: p[LEGACY_OLCRTC_SOCKS_ADDR] ?: ClientConfig.DEFAULT_SOCKS_ADDR,
            isSocksAuthEnabled = p[CLIENT_SOCKS_AUTH_ENABLED] ?: true,
            socksUser = p[CLIENT_SOCKS_USER] ?: p[LEGACY_OLCRTC_SOCKS_USER] ?: "",
            socksPass = p[CLIENT_SOCKS_PASS] ?: p[LEGACY_OLCRTC_SOCKS_PASS] ?: "",
            dns = p[CLIENT_DNS] ?: "",
            goDnsGo = p[GO_DNS_GO] ?: false,
            useCustomCerts = p[USE_CUSTOM_CERTS] ?: true,
            kernelConfig = kernelConfig
        )
    }

    /** Membership, credentials, subscription policies and anchor edits share one DataStore revision. */
    val runtimeClientSnapshotFlow: Flow<com.wireturn.app.domain.RuntimeClientSnapshot> =
        appCtx.internalDataStore.data.map { p ->
            com.wireturn.app.domain.RuntimeClientSnapshot.resolve(
                config = readClientConfig(p),
                profileId = p[CURRENT_PROFILE_ID] ?: "default",
                profiles = readProfiles(p),
                subscriptions = readSubscriptions(p),
                shared = NetworkPreferences.read(p),
                fragmentation = p[defaultFragmentationKey]?.let { json ->
                    Gson().fromJson(json, com.wireturn.app.data.kernel.VpnFragmentation::class.java).also { it.validate() }
                },
                masterDnsGroups = MasterDnsGroups.read(p, gson),
                activeMasterDnsGroupId = p[MasterDnsGroups.activeKey],
            )
        }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default)

    val sharedNetworkSettingsFlow: Flow<SharedNetworkSettings> = appCtx.internalDataStore.data
        .map { p -> effectiveNetwork(p) }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default)

    private fun effectiveNetwork(p: Preferences): SharedNetworkSettings = com.wireturn.app.domain.ProfileRouting.resolve(
        com.wireturn.app.domain.ProfileRouting.common(NetworkPreferences.read(p), readSubscriptions(p)),
        readProfiles(p).find { it.id == p[CURRENT_PROFILE_ID] }, readSubscriptions(p))

    /** Isolated probes use their own subscription, not whichever profile is active. */
    suspend fun networkSettingsForProfile(profile: Profile): SharedNetworkSettings {
        val p = appCtx.internalDataStore.data.first()
        val subscriptions = readSubscriptions(p)
        val common = com.wireturn.app.domain.ProfileRouting.common(NetworkPreferences.read(p), subscriptions)
        return com.wireturn.app.domain.ProfileRouting.resolve(common, profile, subscriptions)
    }

    /** One persisted snapshot, without recompiling every member's policy on every comparison. */
    suspend fun groupProbeSettings(profiles: List<Profile>): com.wireturn.app.domain.GroupProbeSettings {
        val p = appCtx.internalDataStore.data.first()
        val subscriptions = readSubscriptions(p)
        val ids = profiles.mapNotNull { it.subscriptionId }.toSet()
        return com.wireturn.app.domain.GroupProbeSettings(
            com.wireturn.app.domain.ProfileRouting.common(NetworkPreferences.read(p), subscriptions),
            subscriptions.filter { it.id in ids }.associate { it.id to (com.wireturn.app.domain.ProfileRouting.enabled(it) to it.routingSource) },
            fragmentation = p[defaultFragmentationKey]?.let { json ->
                Gson().fromJson(json, com.wireturn.app.data.kernel.VpnFragmentation::class.java).also { it.validate() }
            },
            subscriptionRuleOverrides = subscriptions.filter { it.id in ids }.associate { it.id to it.routingOverridesJson })
    }

    val effectiveXraySettingsFlow: Flow<XraySettings> = appCtx.internalDataStore.data.map { p ->
        effectiveNetwork(p).applyToLegacy(XraySettings(
            socksBindAddress = p[XRAY_SOCKS_BIND] ?: XraySettings.DEFAULT_SOCKS_BIND_ADDRESS,
            httpBindAddress = p[XRAY_HTTP_BIND] ?: "",
            isProxyAuthEnabled = p[XRAY_AUTH_ENABLED] ?: true,
            proxyUser = p[XRAY_USER] ?: "", proxyPass = p[XRAY_PASS] ?: ""))
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default)

    val xraySettingsFlow: Flow<XraySettings> = appCtx.internalDataStore.data
        .map { p ->
            // Read listeners and network policy from the same atomic snapshot.
            com.wireturn.app.domain.ProfileRouting.common(NetworkPreferences.read(p), readSubscriptions(p)).applyToLegacy(XraySettings(
                socksBindAddress = p[XRAY_SOCKS_BIND] ?: XraySettings.DEFAULT_SOCKS_BIND_ADDRESS,
                httpBindAddress = p[XRAY_HTTP_BIND] ?: "",
                isProxyAuthEnabled = p[XRAY_AUTH_ENABLED] ?: true,
                proxyUser = p[XRAY_USER] ?: "",
                proxyPass = p[XRAY_PASS] ?: "",
            ))
        }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default)

    val xrayConfigFlow: Flow<XrayConfig> = appCtx.internalDataStore.data
        .map { p ->
            XrayConfig(
                enabled = p[ACTIVE_XRAY_ENABLED] ?: false,
                routingEnabled = p[ROUTING_DISABLED_BY_USER] != true,
                protocol = try {
                    XrayConfiguration.valueOf(p[ACTIVE_XRAY_CONFIG_TYPE] ?: XrayConfiguration.WIREGUARD.name)
                } catch (_: Exception) {
                    XrayConfiguration.WIREGUARD
                }
            )
        }.distinctUntilChanged()

    val wgConfigFlow: Flow<WgConfig> = appCtx.internalDataStore.data
        .map { (gson.fromJson(it[ACTIVE_WG_JSON] ?: "{}", WgConfig::class.java) ?: WgConfig()) }
        .distinctUntilChanged()

    val vlessConfigFlow: Flow<VlessConfig> = appCtx.internalDataStore.data
        .map { (gson.fromJson(it[ACTIVE_VLESS_JSON] ?: "{}", VlessConfig::class.java) ?: VlessConfig()) }
        .distinctUntilChanged()

    private fun kernelSnapshotOf(kernelConfig: KernelConfig): KernelSnapshot = when (kernelConfig) {
        is KernelConfig.MasterDns -> KernelSnapshot(variant = KernelVariant.MASTERDNS.name, masterdns = kernelConfig.config)
        is KernelConfig.Vpn -> KernelSnapshot(variant = KernelVariant.VPN.name, vpn = kernelConfig.config)
        is KernelConfig.Turnable -> KernelSnapshot(variant = KernelVariant.TURNABLE.name, turnable = kernelConfig.config)
        is KernelConfig.Olcrtc -> KernelSnapshot(variant = KernelVariant.OLCRTC.name, olcrtc = kernelConfig.config)
        is KernelConfig.Webdav -> KernelSnapshot(variant = KernelVariant.WEBDAV.name, webdav = kernelConfig.config)
        is KernelConfig.FreeTurn -> KernelSnapshot(variant = KernelVariant.FREETURN.name, freeturn = kernelConfig.config)
        is KernelConfig.Qwdtt -> KernelSnapshot(variant = KernelVariant.QWDTT.name, qwdtt = kernelConfig.config)
    }

    suspend fun saveFullProfile(id: String, profile: Profile) {
        appCtx.internalDataStore.edit { p ->
            ProfileActivation.write(p, id, profile, kernelSnapshotOf(profile.kernelConfig), gson)
            p.remove(MasterDnsGroups.activeKey)
        }
    }

    internal suspend fun activateSelectedProfile(snapshot: Profile): Profile? =
        activateSelectedProfileState(appCtx.internalDataStore, snapshot, ::readProfiles) { p, profile ->
            ProfileActivation.write(p, profile.id, profile, kernelSnapshotOf(profile.kernelConfig), gson)
            p.remove(activeGroupKey)
            p.remove(MasterDnsGroups.activeKey)
            profile.subscriptionId?.let { SubscriptionEdit.ActiveProfile(it, profile.id).commit(p) }
        }

    suspend fun saveProfiles(list: List<Profile>) {
        appCtx.internalDataStore.edit { p ->
            p[PROFILES_JSON] = gson.toJson(list)
            // Group membership is user-owned. Missing members remain visible for repair.
            // Do not rewrite groups or active group selection during a profile refresh.
        }
    }

    internal suspend fun activateImportedProfile(expectedCurrentId: String, id: String): Profile? =
        activateImportedProfileState(appCtx.internalDataStore, expectedCurrentId, id, ::readProfiles) { p, profile ->
            ProfileActivation.write(p, profile.id, profile, kernelSnapshotOf(profile.kernelConfig), gson)
            p.remove(activeGroupKey)
            p.remove(MasterDnsGroups.activeKey)
            profile.subscriptionId?.let { subscriptionId ->
                SubscriptionEdit.ActiveProfile(subscriptionId, profile.id).commit(p)
            }
        }

    internal suspend fun deleteSubscription(id: String): SubscriptionDeletionResult =
        deleteSubscriptionState(appCtx.internalDataStore, id, ::subscriptionImportSnapshot, gson)

    internal suspend fun editProfiles(edit: ProfileListEdit): List<Profile> {
        var result = emptyList<Profile>()
        appCtx.internalDataStore.edit { p -> result = edit.commit(p, gson) }
        return result
    }

    private val cfAddressesKey = stringPreferencesKey("cf_addresses_v1")
    suspend fun setCloudflareKeepAddress(expected: Profile, enabled: Boolean) {
        appCtx.internalDataStore.edit { p ->
            val current = readProfiles(p).singleOrNull { it.id == expected.id }
            check(current != null && com.wireturn.app.domain.CloudflareProfile.snapshot(current) ==
                com.wireturn.app.domain.CloudflareProfile.snapshot(expected))
            val records = runCatching {
                gson.fromJson<MutableMap<String, com.wireturn.app.domain.CfSavedAddresses>>(p[cfAddressesKey] ?: "{}",
                    object : TypeToken<MutableMap<String, com.wireturn.app.domain.CfSavedAddresses>>() {}.type)
            }.getOrNull() ?: mutableMapOf()
            val (_, record) = com.wireturn.app.domain.CfProfileUpdate.keepAddress(current, enabled, records[current.id])
            val array = com.google.gson.JsonParser.parseString(p[PROFILES_JSON] ?: "[]").asJsonArray
            val index = array.indexOfFirst { it.asJsonObject.get("id")?.asString == current.id }
            check(index >= 0)
            array[index].asJsonObject.addProperty("keepSubscriptionAddress", enabled)
            p[PROFILES_JSON] = array.toString()
            if (record != null) records[current.id] = record else records.remove(current.id)
            p[cfAddressesKey] = gson.toJson(records)
        }
    }
    fun cloudflareProfileFlow(id: String): Flow<Pair<Profile?, com.wireturn.app.domain.CfSavedAddresses?>> = appCtx.internalDataStore.data.map { p ->
        val records = runCatching {
            gson.fromJson<Map<String, com.wireturn.app.domain.CfSavedAddresses>>(p[cfAddressesKey] ?: "{}",
                object : TypeToken<Map<String, com.wireturn.app.domain.CfSavedAddresses>>() {}.type) ?: emptyMap()
        }.getOrDefault(emptyMap())
        val current = readProfiles(p).find { it.id == id }
        current to current?.let { records[id]?.forCurrent(it) ?: com.wireturn.app.domain.CfSavedAddresses.fromPinned(it) }
    }

    /** Optimistic transaction: never overwrite an intervening edit or subscription refresh. */
    suspend fun saveCloudflareAddresses(expected: Profile, record: com.wireturn.app.domain.CfSavedAddresses,
                                       applySelection: Boolean) {
        appCtx.internalDataStore.edit { p ->
            val current = readProfiles(p).singleOrNull { it.id == expected.id }
            check(current != null && com.wireturn.app.domain.CloudflareProfile.snapshot(current) ==
                com.wireturn.app.domain.CloudflareProfile.snapshot(expected))
            check(record.results.size <= 256 && record.results.all { it.usable })
            val records = runCatching {
                gson.fromJson<MutableMap<String, com.wireturn.app.domain.CfSavedAddresses>>(p[cfAddressesKey] ?: "{}",
                    object : TypeToken<MutableMap<String, com.wireturn.app.domain.CfSavedAddresses>>() {}.type)
            }.getOrNull() ?: mutableMapOf()
            check(record.source == com.wireturn.app.domain.CloudflareProfile.snapshot(current) ||
                (records[expected.id]?.forCurrent(current) ?: com.wireturn.app.domain.CfSavedAddresses.fromPinned(current))
                    ?.let { it.source == record.source && it.accepts(current) } == true)
            if (applySelection) {
                val before = records[expected.id]?.forCurrent(current)
                    ?: com.wireturn.app.domain.CfSavedAddresses.fromPinned(current)
                    ?: record.copy(selectedIp = null)
                val replacement = com.wireturn.app.domain.CfProfileUpdate.selection(current, before, record.selectedIp)
                check(replacement.id == expected.id)
                // Change exactly one object in the raw array. Unknown fields on other profiles survive.
                val array = com.google.gson.JsonParser.parseString(p[PROFILES_JSON] ?: "[]").asJsonArray
                val index = array.indexOfFirst { it.asJsonObject.get("id")?.asString == expected.id }
                check(index >= 0)
                array[index].asJsonObject.add("kernelConfig", gson.toJsonTree(replacement.kernelConfig, KernelConfig::class.java))
                p[PROFILES_JSON] = array.toString()
                if (p[CURRENT_PROFILE_ID] == expected.id) {
                    ProfileActivation.write(p, expected.id, replacement, kernelSnapshotOf(replacement.kernelConfig), gson)
                }
            }
            records[expected.id] = record
            val ids = readProfiles(p).map { it.id }.toSet()
            p[cfAddressesKey] = gson.toJson(records.filterKeys { it in ids })
        }
    }

    /** Save only this profile's resolver choice, rejecting results from a stale editor/scan. */
    suspend fun saveMasterDnsResolverSelection(expected: Profile, localResolvers: String?,
                                              useLocalResolvers: Boolean, domainResolvers: Map<String, String>? = null) {
        appCtx.internalDataStore.edit { p ->
            MasterDnsResolverSelection.commit(p, readProfiles(p), expected, localResolvers, useLocalResolvers, gson, domainResolvers)
        }
    }

    suspend fun saveSubscriptions(list: List<Subscription>) {
        appCtx.internalDataStore.edit { it[SUBSCRIPTIONS_JSON] = gson.toJson(list) }
    }

    internal suspend fun editSubscription(edit: SubscriptionEdit) {
        appCtx.internalDataStore.edit { edit.commit(it) }
    }

    suspend fun setVpnEnabled(v: Boolean) {
        appCtx.internalDataStore.edit { it[VPN_ENABLED] = v }
    }

    suspend fun saveExcludedApps(s: Set<String>) {
        appCtx.internalDataStore.edit { AppRoutingLists.saveActive(it, s) }
    }

    suspend fun setDynamicTheme(v: Boolean) {
        appCtx.internalDataStore.edit { it[DYNAMIC_THEME] = v }
    }

    suspend fun setThemeMode(m: ThemeMode) {
        appCtx.internalDataStore.edit { it[THEME_MODE] = m.name }
    }

    suspend fun setOnboardingDone(v: Boolean) {
        appCtx.internalDataStore.edit { it[ONBOARDING_DONE] = v }
    }

    suspend fun setAppLanguage(l: String) {
        appCtx.internalDataStore.edit { it[APP_LANGUAGE] = l }
    }

    suspend fun setPingUrl(url: String) {
        if (url.isNotBlank()) com.wireturn.app.domain.ConfigurationUrlPolicy.validate(java.net.URL(url))
        appCtx.internalDataStore.edit { it[PING_URL] = url }
    }

    suspend fun setCountryDetectionMethod(method: String) {
        appCtx.internalDataStore.edit { it[COUNTRY_DETECTION_METHOD] = method }
    }

    suspend fun setBatteryNotificationDismissed(v: Boolean) {
        appCtx.internalDataStore.edit { it[BATTERY_NOTIFICATION_DISMISSED] = v }
    }

    suspend fun setAppsExclusionHintShown(v: Boolean) {
        appCtx.internalDataStore.edit { it[APPS_EXCLUSION_HINT_SHOWN] = v }
    }

    suspend fun setAllowUnstableUpdates(v: Boolean) {
        appCtx.internalDataStore.edit { it[ALLOW_UNSTABLE_UPDATES] = v }
    }

    suspend fun setWaitForNetwork(v: Boolean) {
        appCtx.internalDataStore.edit { it[WAIT_FOR_NETWORK] = v }
    }

    suspend fun setRestartOnNetworkChange(v: Boolean) {
        appCtx.internalDataStore.edit { it[RESTART_ON_NETWORK_CHANGE] = v }
    }

    suspend fun setCaptchaStyleMod(v: Boolean) {
        appCtx.internalDataStore.edit { it[CAPTCHA_STYLE_MOD] = v }
    }

    suspend fun setCaptchaForceTint(v: Boolean) {
        appCtx.internalDataStore.edit { it[CAPTCHA_FORCE_TINT] = v }
    }

    suspend fun setPrivacyMode(v: Boolean) {
        appCtx.internalDataStore.edit { it[PRIVACY_MODE] = v }
    }

    suspend fun setProfilesGestureEnabled(v: Boolean) {
        appCtx.internalDataStore.edit { it[PROFILES_GESTURE_ENABLED] = v }
    }

    suspend fun saveProfileCountry(profileId: String, countryCode: String) {
        appCtx.internalDataStore.edit { p ->
            val current = try {
                gson.fromJson<Map<String, String>>(
                    p[PROFILE_COUNTRIES_JSON] ?: "{}",
                    object : TypeToken<Map<String, String>>() {}.type
                ) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            p[PROFILE_COUNTRIES_JSON] = gson.toJson(current + (profileId to countryCode))
        }
    }

    suspend fun removeProfileCountries(profileIds: List<String>) {
        if (profileIds.isEmpty()) return
        appCtx.internalDataStore.edit { p ->
            val current = try {
                gson.fromJson<Map<String, String>>(
                    p[PROFILE_COUNTRIES_JSON] ?: "{}",
                    object : TypeToken<Map<String, String>>() {}.type
                ) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            p[PROFILE_COUNTRIES_JSON] = gson.toJson(current - profileIds.toSet())
        }
    }

    suspend fun addVlessLinkToHistory(l: String) {
        appCtx.internalDataStore.edit { p ->
            val h = p[VLESS_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            p[VLESS_LINK_HISTORY] = (listOf(l) + h.filter { it != l }).take(3).joinToString("|")
        }
    }

    suspend fun removeVlessLinkFromHistory(l: String) {
        appCtx.internalDataStore.edit { p ->
            p[VLESS_LINK_HISTORY] = (p[VLESS_LINK_HISTORY]?.split("|")?.filter { it.isNotBlank() && it != l } ?: emptyList())
                .joinToString("|")
        }
    }

    suspend fun importAppExclusions(command: com.wireturn.app.domain.AppExclusionsLink.Command) {
        appCtx.internalDataStore.edit { p ->
            AppRoutingLists.apply(p, command)
        }
    }

    suspend fun importTunaRouting(command: com.wireturn.app.domain.TunaRoutingLink.Command) {
        val before = NetworkPreferences.read(appCtx.internalDataStore.data.first())
        val after = TunaRoutingImport.plan(before, command)
        val effective = com.wireturn.app.domain.NetworkPolicyResolver.resolve(after).routing
        com.wireturn.app.domain.SubscriptionRoutingAssets.prepare(appCtx, command.source,
            effective = com.wireturn.app.domain.RoutingOverrides.withLan(effective, after.routing.bypassLan))
        appCtx.internalDataStore.edit { TunaRoutingImport.write(it, before, after, command) }
    }

    suspend fun saveVpnSettings(s: VpnSettings) {
        appCtx.internalDataStore.edit {
            it[VPN_ENABLED] = s.enabled
            it[VPN_HIDE_SYSTEM_APPS] = s.hideSystemApps
            val modeChanged = (it[VPN_BYPASS_MODE] ?: true) != s.bypassMode
            AppRoutingLists.select(it, s.bypassMode)
            it[VPN_FILTERING_ENABLED] = s.filteringEnabled
            it[VPN_GROUP_APPS_BY_LETTER] = s.groupAppsByLetter
            if (!modeChanged) AppRoutingLists.saveActive(it, s.excludedApps)
        }
    }

    suspend fun updateAutoLaunchSettings(s: AutoLaunchSettings) {
        appCtx.internalDataStore.edit { AutoLaunchPreferences.write(it, s) }
    }

    suspend fun saveWgConfig(c: WgConfig) {
        appCtx.internalDataStore.edit { it[ACTIVE_WG_JSON] = gson.toJson(c) }
    }

    suspend fun enableRouting() {
        appCtx.internalDataStore.edit { it.remove(ROUTING_DISABLED_BY_USER) }
    }

    suspend fun disableRouting(consent: RoutingDisableConsent) {
        if (consent.consume()) appCtx.internalDataStore.edit { it[ROUTING_DISABLED_BY_USER] = true }
    }

    suspend fun saveXrayConfig(c: XrayConfig) {
        appCtx.internalDataStore.edit {
            it[ACTIVE_XRAY_ENABLED] = c.enabled
            it[ACTIVE_XRAY_CONFIG_TYPE] = c.protocol.name
        }
    }

    suspend fun saveVlessConfig(c: VlessConfig) {
        appCtx.internalDataStore.edit { it[ACTIVE_VLESS_JSON] = gson.toJson(c) }
    }

    suspend fun activateSubscriptionRouting(source: String) {
        appCtx.internalDataStore.edit { SubscriptionImportCommit.activateRouting(it, source) }
    }

    suspend fun saveXraySettings(s: XraySettings) {
        appCtx.internalDataStore.edit {
            it[XRAY_SOCKS_BIND] = s.socksBindAddress
            it[XRAY_HTTP_BIND] = s.httpBindAddress
            it[XRAY_AUTH_ENABLED] = s.isProxyAuthEnabled
            it[XRAY_USER] = s.proxyUser
            it[XRAY_PASS] = s.proxyPass
            NetworkPreferences.write(it, SharedNetworkSettings.fromLegacy(s))
        }
    }

    suspend fun saveSharedNetworkSettings(settings: SharedNetworkSettings) {
        appCtx.internalDataStore.edit { NetworkPreferences.write(it, settings) }
    }

    suspend fun applySubscriptionSettings(values: Map<String, com.google.gson.JsonElement>) {
        val registry = com.wireturn.app.domain.SubscriptionSettingsRegistry
        require(values.isNotEmpty())
        values.forEach { (path, value) -> registry.validate(path, value) }
        appCtx.internalDataStore.edit { p ->
            val backup = com.google.gson.JsonObject()
            values.filterKeys(registry::isApplicable).forEach { (path, value) ->
                val f = registry.fields.getValue(path)
                val previous: Any? = when (f.type) {
                    "boolean" -> p[booleanPreferencesKey(f.storageKey)]
                    "integer" -> p[intPreferencesKey(f.storageKey)]
                    "packages" -> p[stringSetPreferencesKey(f.storageKey)]
                    else -> p[stringPreferencesKey(f.storageKey)]
                }
                backup.add(path, Gson().toJsonTree(previous))
                when (f.type) {
                    "boolean" -> p[booleanPreferencesKey(f.storageKey)] = value.asBoolean
                    "integer" -> p[intPreferencesKey(f.storageKey)] = value.asInt
                    "packages" -> p[stringSetPreferencesKey(f.storageKey)] = value.asJsonArray.map { it.asString }.toSet()
                    else -> p[stringPreferencesKey(f.storageKey)] = value.asString
                }
            }
            p[stringPreferencesKey("subscription_settings_last_backup")] = backup.toString()
        }
    }

    suspend fun undoSubscriptionSettings() {
        appCtx.internalDataStore.edit { p ->
            val key = stringPreferencesKey("subscription_settings_last_backup")
            val raw = p[key] ?: return@edit
            val backup = com.google.gson.JsonParser.parseString(raw).asJsonObject
            backup.entrySet().forEach { (path, v) ->
                if (!com.wireturn.app.domain.SubscriptionSettingsRegistry.isApplicable(path)) return@forEach
                val f = com.wireturn.app.domain.SubscriptionSettingsRegistry.fields[path] ?: return@forEach
                when (f.type) {
                    "boolean" -> { val k = booleanPreferencesKey(f.storageKey); if (v.isJsonNull) p.remove(k) else p[k] = v.asBoolean }
                    "integer" -> { val k = intPreferencesKey(f.storageKey); if (v.isJsonNull) p.remove(k) else p[k] = v.asInt }
                    "packages" -> { val k = stringSetPreferencesKey(f.storageKey); if (v.isJsonNull) p.remove(k) else p[k] = v.asJsonArray.map { it.asString }.toSet() }
                    else -> { val k = stringPreferencesKey(f.storageKey); if (v.isJsonNull) p.remove(k) else p[k] = v.asString }
                }
            }
            p.remove(key)
        }
    }

    suspend fun saveClientConfig(c: ClientConfig) {
        appCtx.internalDataStore.edit {
            it[CLIENT_LISTEN_ADDR] = c.listenAddr
            it[CLIENT_SOCKS_ADDR] = c.socksAddr
            it[CLIENT_SOCKS_AUTH_ENABLED] = c.isSocksAuthEnabled
            it[CLIENT_SOCKS_USER] = c.socksUser
            it[CLIENT_SOCKS_PASS] = c.socksPass
            it.remove(LEGACY_OLCRTC_SOCKS_ADDR); it.remove(LEGACY_OLCRTC_SOCKS_AUTH_ENABLED)
            it.remove(LEGACY_OLCRTC_SOCKS_USER); it.remove(LEGACY_OLCRTC_SOCKS_PASS)
            it[CLIENT_DNS] = c.dns
            it[GO_DNS_GO] = c.goDnsGo
            it[USE_CUSTOM_CERTS] = c.useCustomCerts
            it[ACTIVE_KERNEL_JSON] = gson.toJson(kernelSnapshotOf(c.kernelConfig))
        }
    }

    suspend fun resetAll() {
        appCtx.internalDataStore.edit { it.clear() }
    }

    suspend fun saveActiveProfilePart(profile: Profile) {
        appCtx.internalDataStore.edit { p ->
            p[ACTIVE_KERNEL_JSON] = gson.toJson(kernelSnapshotOf(profile.kernelConfig))
            p[ACTIVE_XRAY_CONFIG_TYPE] = profile.xrayProtocol.name
            p[ACTIVE_XRAY_ENABLED] = profile.xrayEnabled
            p[ACTIVE_WG_JSON] = gson.toJson(profile.wgConfig)
            p[ACTIVE_VLESS_JSON] = gson.toJson(profile.vlessConfig)
        }
    }

    suspend fun clearActiveProfile() {
        appCtx.internalDataStore.edit { p ->
            p.remove(ACTIVE_KERNEL_JSON)
            p.remove(ACTIVE_XRAY_CONFIG_TYPE)
            p.remove(ACTIVE_XRAY_ENABLED)
            p.remove(ACTIVE_WG_JSON)
            p.remove(ACTIVE_VLESS_JSON)
            p.remove(CURRENT_PROFILE_ID)
            p.remove(MasterDnsGroups.activeKey)
        }
    }
}
