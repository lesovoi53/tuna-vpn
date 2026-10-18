package com.wireturn.app.domain

import kotlinx.coroutines.flow.first
import com.wireturn.app.AppLogsState

import com.google.gson.JsonParser
import com.wireturn.app.R
import com.wireturn.app.data.AppPreferences
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.ProfileBundle
import com.wireturn.app.data.ProfileListEdit
import com.wireturn.app.data.Subscription
import com.wireturn.app.data.SubscriptionEdit
import com.wireturn.app.data.kernel.OlcrtcConfig
import com.wireturn.app.kernel.KernelRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.milliseconds

data class ImportResult(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val total: Int = 0,
    val skipped: Int = 0
)

sealed class ImportStatus {
    data class Success(val id: String? = null, val summary: ImportResult? = null, val message: String? = null) : ImportStatus()
    data class KernelConfigDetected(val type: String, val json: String, val source: String) : ImportStatus()
    object NetworkError : ImportStatus()
    data class ServerError(val code: Int) : ImportStatus()
    object EmptyResponse : ImportStatus()
    object InvalidFormat : ImportStatus()
    data class Rejected(val report: com.wireturn.app.data.SubscriptionImportReport) : ImportStatus()
}

/**
 * What a `wireturn://`/`wt://` link resolves to, without importing anything yet - used to show a
 * confirmation dialog before [ProfileManager.importProfiles]/[ProfileManager.fetchSubscription]
 * actually runs, since deep links can be opened from outside the app (browser, messenger, QR
 * scanner) with no prior user action inside WireTurn.
 */
sealed class DeepLinkPreview {
    data class Routing(val command: TunaRoutingLink.Command) : DeepLinkPreview()
    data class AppExclusions(val packages: Set<String>) : DeepLinkPreview()
    /** [names] may contain blank entries where a bundled profile has no `name` set; [count] never does. */
    data class Profiles(val count: Int, val names: List<String>, val json: String) : DeepLinkPreview()
    data class SubscriptionLink(val url: String) : DeepLinkPreview()
    object Invalid : DeepLinkPreview()
}

/** Shared strict WG/AWG import for files, raw subscription documents and encoded containers. */
internal fun parseWgProfileImport(text: String): ProfileBundle? {
    val source = text.trim()
    if (!com.wireturn.app.data.kernel.AwgUri.recognizes(source)) return null
    val links = if (com.wireturn.app.data.kernel.AwgUri.isIni(source) || source.startsWith("vpn://", true))
        com.wireturn.app.data.kernel.AwgUri.normalizeAll(source) else listOf(source)
    return ProfileBundle(profiles = links.map { value ->
        val config = requireNotNull(com.wireturn.app.data.kernel.VpnConfig.parse(value)) { "Invalid WG/AWG profile" }
        Profile(
            id = UUID.nameUUIDFromBytes(("wg-effective:" + com.wireturn.app.data.kernel.AwgUri.identity(config.uri))
                .toByteArray(Charsets.UTF_8)).toString(),
            name = config.displayName,
            kernelConfig = KernelConfig.Vpn(config),
            subscriptionUriIdentity = true
        )
    })
}

/** Clipboard INI is one complete document, just like a selected .conf file. */
internal inline fun importWgIniDocument(
    text: String,
    importDocument: (String) -> Pair<ImportResult, List<Profile>>
): ImportStatus? {
    val source = text.trim()
    if (!com.wireturn.app.data.kernel.AwgUri.isIni(source)) return null
    LegacyProfileImport.itemOrNull { requireNotNull(parseWgProfileImport(source)) } ?: return ImportStatus.InvalidFormat
    val (result, imported) = importDocument(source)
    if (result.total == 0) return ImportStatus.InvalidFormat
    return ImportStatus.Success(imported.firstOrNull()?.id, result)
}

/**
 * Decodes a `wireturn://`/`wt://` link and classifies its payload. The container itself is
 * scheme-agnostic (see [ProfileEncoder]): if the decoded text is a bare http(s) URL, the link
 * is offered as a subscription source; otherwise it's parsed as a Profile/ProfileBundle JSON.
 */
fun previewDeepLink(link: String): DeepLinkPreview {
    if (TunaRoutingLink.recognizes(link)) return runCatching {
        DeepLinkPreview.Routing(TunaRoutingLink.parse(link))
    }.getOrDefault(DeepLinkPreview.Invalid)
    val trimmed = link.trim()
    if (AppExclusionsLink.recognizes(trimmed)) return runCatching {
        DeepLinkPreview.AppExclusions(AppExclusionsLink.parse(trimmed))
    }.getOrDefault(DeepLinkPreview.Invalid)
    if (com.wireturn.app.data.kernel.AwgUri.isIni(trimmed) || trimmed.substringBefore(':').lowercase() in setOf("wg", "awg", "wireguard", "vpn", "tt", "trusttunnel", "snell")) {
        return runCatching {
            val links = if (com.wireturn.app.data.kernel.AwgUri.isIni(trimmed) || trimmed.startsWith("vpn://", true)) com.wireturn.app.data.kernel.AwgUri.normalizeAll(trimmed) else listOf(trimmed)
            val profiles = links.map { value ->
                val vpn = requireNotNull(com.wireturn.app.data.kernel.VpnConfig.parse(value))
                com.google.gson.JsonObject().apply {
                    addProperty("id", UUID.randomUUID().toString()); addProperty("name", vpn.displayName); addProperty("uri", vpn.uri)
                }
            }.distinctBy { it["uri"].asString.substringBefore('#') }
            val array = com.google.gson.JsonArray().apply { profiles.forEach(::add) }
            DeepLinkPreview.Profiles(profiles.size, profiles.map { it["name"].asString }, array.toString())
        }.getOrDefault(DeepLinkPreview.Invalid)
    }

    val encoded = when {
        trimmed.startsWith("tuna://", ignoreCase = true) -> trimmed.substringAfter("://")
        trimmed.startsWith("wireturn://", ignoreCase = true) -> trimmed.substringAfter("://")
        trimmed.startsWith("wt://", ignoreCase = true) -> trimmed.substringAfter("://")
        else -> return DeepLinkPreview.Invalid
    }
    val decoded = ProfileEncoder.decode(encoded)?.trim()?.takeIf { it.isNotEmpty() } ?: return DeepLinkPreview.Invalid

    if (decoded.startsWith("https://", ignoreCase = true) || decoded.startsWith("http://", ignoreCase = true)) {
        return DeepLinkPreview.SubscriptionLink(decoded)
    }

    return try {
        // Use the same independent-entry validation as the confirmed import.
        // A bundle is not one profile, and one malformed name must not hide valid peers.
        val gson = com.google.gson.GsonBuilder()
            .registerTypeAdapterFactory(com.wireturn.app.data.SafeEnumTypeAdapterFactory())
            .registerTypeAdapter(KernelConfig::class.java, com.wireturn.app.data.KernelConfigAdapter()).create()
        val profiles = LegacyProfileImport.parse(decoded, gson, defaultName = "").profiles
        if (profiles.isEmpty()) return DeepLinkPreview.Invalid
        DeepLinkPreview.Profiles(profiles.size, profiles.map { it.name }, decoded)
    } catch (_: Exception) {
        DeepLinkPreview.Invalid
    }
}

private data class ActiveSocksTarget(val addr: String, val user: String?, val pass: String?)

// Guards the write step in activeLocalSocksProxy() below so two concurrent callers (ping loop,
// subscription auto-update, AppUpdater) can't interleave their System-property/Authenticator writes
// and leave a torn combination (e.g. one caller's username paired with another's password) visible
// to a third party mid-connection. Deliberately NOT cached/skipped when the target is unchanged from
// last time: XrayServiceState.setSession() writes this exact same global state independently and
// reactively (see the class doc below), so re-asserting unconditionally on every call is what lets
// this function self-heal if that other writer left something stale in between polls - caching would
// silently skip the correction whenever this call's target happens to match what *this* function
// applied last, even if setSession() had since overwritten it with something else.
private val socksAuthLock = Any()

/**
 * SOCKS proxy for whichever local core (Xray, or OLCRTC/WEBDAV via CoreService) is currently
 * running, or [java.net.Proxy.NO_PROXY] if none is. Shared by [ProfileManager.fetchSubscription],
 * [AppUpdater] and [com.wireturn.app.viewmodel.MainViewModel]'s ping check so update/subscription/
 * ping requests route through the active tunnel.
 *
 * Also brings the JVM-wide SOCKS5 [java.net.Authenticator] in line with whichever target this
 * resolves to. XrayServiceState.setSession() already does this reactively for the Xray case; the
 * OLCRTC/WEBDAV-direct case has no equivalent hook (CoreServiceState.setSession() only stores the
 * session), so it's covered here at the point of use instead - callers like the ping check that
 * poll this every second keep it correct even as the active source changes underneath them.
 */
fun activeLocalSocksProxy(): java.net.Proxy {
    val target = try {
        val xraySess = com.wireturn.app.XrayServiceState.session.value
        val xrayState = com.wireturn.app.XrayServiceState.state.value
        val coreSess = com.wireturn.app.CoreServiceState.session.value
        val coreIsWorking = com.wireturn.app.CoreServiceState.isWorking.value

        when {
            // Same "is Xray in the picture at all" test CoreService.startVpnSupervisor() uses to pick
            // VPN's target - Xray keeps priority any time it isn't Idle (Starting/Connecting included),
            // not just once fully Running, so ping/subscription requests always follow where traffic
            // is actually routed instead of racing ahead of it during startup.
            xrayState != com.wireturn.app.viewmodel.XrayState.Idle && xraySess != null -> {
                val s = xraySess.settings
                ActiveSocksTarget(
                    s.connectableAddress,
                    s.proxyUser.takeIf { s.isProxyAuthEnabled && it.isNotBlank() },
                    s.proxyPass
                )
            }
            coreIsWorking && coreSess != null && coreSess.clientConfig.kernelVariant.isSocks5Native -> {
                val cc = coreSess.clientConfig
                ActiveSocksTarget(
                    cc.socksAddr.replace("0.0.0.0:", "127.0.0.1:"),
                    cc.socksUser.takeIf { cc.isSocksAuthEnabled && it.isNotBlank() },
                    cc.socksPass
                )
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    } ?: return java.net.Proxy.NO_PROXY

    synchronized(socksAuthLock) {
        if (target.user != null) {
            val user = target.user
            val pass = target.pass ?: ""
            System.setProperty("java.net.socks.username", user)
            System.setProperty("java.net.socks.password", pass)
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication() = java.net.PasswordAuthentication(user, pass.toCharArray())
            })
        } else {
            System.clearProperty("java.net.socks.username")
            System.clearProperty("java.net.socks.password")
            java.net.Authenticator.setDefault(null)
        }
    }

    return try {
        val host = target.addr.substringBeforeLast(':')
        val port = target.addr.substringAfterLast(':').toIntOrNull() ?: 1080
        java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved(host, port))
    } catch (_: Exception) {
        java.net.Proxy.NO_PROXY
    }
}

/** Android 17+ blocks TCP to LAN/loopback addresses without the ACCESS_LOCAL_NETWORK runtime permission. */
fun isLocalNetworkHost(url: String): Boolean {
    val host = try { java.net.URI(url).host } catch (_: Exception) { null } ?: return false
    if (host.equals("localhost", ignoreCase = true)) return true
    val octets = host.split(".")
    if (octets.size != 4) return false
    val nums = octets.map { it.toIntOrNull() ?: return false }
    if (nums.any { it !in 0..255 }) return false
    val (a, b) = nums
    return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

// Production screens use get(); the internal constructor permits isolated audit scopes.
class ProfileManager internal constructor(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope
) {
    companion object {
        @Volatile private var instance: ProfileManager? = null
        fun get(context: android.content.Context): ProfileManager = instance ?: synchronized(this) {
            instance ?: ProfileManager(AppPreferences(context.applicationContext),
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycleScope).also { instance = it }
        }
    }

    private fun activateImportedProfile(expectedCurrentId: String, id: String) {
        scope.launch {
            // The import screen may already be closed. Persist selection from a fresh
            // transactional snapshot; never call a cancelled screen's ViewModel scope.
            prefs.activateImportedProfile(expectedCurrentId, id) ?: return@launch
            ConnectionGroupRunner.get(prefs.context).release()
            val config = prefs.clientConfigFlow.first()
            // Stopping still owns resources, but a refresh must not reverse the
            // user's stop. Read the status directly: the derived flow can lag.
            val status = com.wireturn.app.CoreServiceState.status.value
            if (status !is com.wireturn.app.CoreStatus.Idle && status !is com.wireturn.app.CoreStatus.Stopping) {
                com.wireturn.app.CoreService.start(prefs.context, config)
            }
        }
    }

    val profiles: StateFlow<List<Profile>> = prefs.profilesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val currentProfileId: StateFlow<String> = prefs.currentProfileIdFlow
        .stateIn(scope, SharingStarted.Eagerly, "default")

    val subscriptions: StateFlow<List<Subscription>> = prefs.subscriptionsFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Last known tunnel-exit country (ISO-3166 alpha-2) per profile id, updated after each successful connection. */
    val profileCountries: StateFlow<Map<String, String>> = prefs.profileCountriesFlow
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun saveProfileCountry(profileId: String, countryCode: String) {
        scope.launch { prefs.saveProfileCountry(profileId, countryCode) }
    }

    private val _updatingSubIds = MutableStateFlow<Set<String>>(emptySet())
    val updatingSubIds: StateFlow<Set<String>> = _updatingSubIds.asStateFlow()

    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapterFactory(com.wireturn.app.data.SafeEnumTypeAdapterFactory())
        .registerTypeAdapter(KernelConfig::class.java, com.wireturn.app.data.KernelConfigAdapter())
        .create()

    private val userAgent: String by lazy {
        val version = try {
            val pInfo = prefs.context.packageManager.getPackageInfo(prefs.context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
        "TUNA-VPN/$version"
    }

    init {
        startAutoUpdateLoop()
    }

    private fun startAutoUpdateLoop() {
        scope.launch(Dispatchers.IO) {
            runSubscriptionUpdateSchedule(subscriptions.map { list -> list.any { it.autoUpdate } }) {
                val now = System.currentTimeMillis()
                val curId = currentProfileId.value
                val curProfiles = profiles.value
                
                subscriptions.value.forEach { sub ->
                    if (sub.autoUpdate) {
                        val isSelected = curId != "default" && curProfiles.find { it.id == curId }?.subscriptionId == sub.id
                        val isTunnelActive = activeLocalSocksProxy() != java.net.Proxy.NO_PROXY
                        val shouldUpdate = (!sub.onlyUpdateIfSelected || isSelected) &&
                                           (!sub.requireTunnelForUpdate || isTunnelActive)
                        
                        if (shouldUpdate) {
                            val intervalMs = sub.updateIntervalMinutes.toLong() * 60_000L
                            if (now - sub.updatedAt >= intervalMs) {
                                // Disabling the schedule must not cancel a fetch already in progress.
                                scope.launch(Dispatchers.IO) { coordinatedFetch(sub.url, sub.id, automatic = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    fun nextDefaultProfileName(existing: List<Profile> = profiles.value): String {
        val base = prefs.context.getString(R.string.profile_default_name)
        val names = existing.map { it.name }.toSet()
        var n = 1
        while ("$base #$n" in names) n++
        return "$base #$n"
    }

    fun cloneProfile(id: String, newName: String) {
        val currentList = profiles.value
        val profile = currentList.find { it.id == id } ?: return
        val validatedName = newName.takeIf { it.isNotBlank() } ?: nextDefaultProfileName(currentList)
        val clonedProfile = profile.copy(
            id = UUID.randomUUID().toString(),
            name = validatedName,
            // A clone is a standalone copy, not a member of the source subscription - keeping the
            // subscriptionId would make the next subscription sync silently delete it as a stale entry.
            subscriptionId = null,
            subscriptionSourceId = null, subscriptionKernelBaseline = null, subscriptionAuxBaseline = null, subscriptionOriginalName = null,
            retainedFromSubscription = null, retainedSubscriptionId = null,
            keepSubscriptionAddress = false
        )
        scope.launch { prefs.editProfiles(ProfileListEdit.Append(clonedProfile)) }
    }

    fun deleteProfiles(ids: List<String>, onFallback: (Profile) -> Unit) {
        val currentList = profiles.value
        val currentProfile = currentList.find { it.id == currentProfileId.value }
        val isCurrentDeleted = currentProfile?.id in ids
        val preferredSubId = currentProfile?.subscriptionId

        scope.launch {
            val newList = prefs.editProfiles(ProfileListEdit.Remove(ids.toSet()))
            prefs.removeProfileCountries(ids)
            if (isCurrentDeleted && newList.isNotEmpty()) {
                findBestFallbackProfile(newList, subscriptions.value, preferredSubId)?.let { onFallback(it) }
            }
        }
    }

    fun renameProfile(id: String, newName: String) {
        val validatedName = newName.takeIf { it.isNotBlank() } ?: nextDefaultProfileName()
        scope.launch { prefs.editProfiles(ProfileListEdit.Rename(id, validatedName)) }
    }

    fun reorderProfiles(newList: List<Profile>) {
        val ids = newList.map { it.id }
        scope.launch { prefs.editProfiles(ProfileListEdit.Reorder(ids)) }
    }

    fun updateCurrentProfile(profile: Profile) {
        val defaultName = prefs.context.getString(R.string.profile_default_name)
        scope.launch { prefs.editProfiles(ProfileListEdit.Replace(profile.sanitize(defaultName))) }
    }

    fun getProfilesJson(ids: List<String>): String {
        val selected = profiles.value.filter { it.id in ids }.map { it.copy(subscriptionKernelBaseline = null, subscriptionAuxBaseline = null,
            subscriptionOriginalName = null, retainedFromSubscription = null, retainedSubscriptionId = null) }
        return gson.toJson(selected)
    }

    fun exportProfilesToZip(ids: List<String>?): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            val usedFileNames = mutableSetOf<String>()
            profiles.value.filter { ids == null || it.id in ids }.forEach { profile ->
                val json = gson.toJson(profile.copy(subscriptionKernelBaseline = null, subscriptionAuxBaseline = null,
                    subscriptionOriginalName = null, retainedFromSubscription = null, retainedSubscriptionId = null))
                val safeName = profile.name.replace(Regex("[\\\\/:*?\"<>| ]"), "_")
                var entryName = "tuna_$safeName.json"
                var counter = 1
                while (usedFileNames.contains(entryName)) { entryName = "tuna_${safeName}_$counter.json"; counter++ }
                usedFileNames.add(entryName)
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(json.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    suspend fun importProfilesFromZip(inputStream: java.io.InputStream, onAutoSelect: ((Profile) -> Unit)? = null): ImportResult =
        importProfilesFromZipWithResult(inputStream, onAutoSelect).first

    /** Return committed local IDs just like the plain-document import. */
    suspend fun importProfilesFromZipWithResult(inputStream: java.io.InputStream, onAutoSelect: ((Profile) -> Unit)? = null): Pair<ImportResult, List<Profile>> {
        try {
            val extractedData = readProfileArchive(inputStream)
            if (extractedData.isEmpty()) return ImportResult() to emptyList()
            val (result, imported, _) = importProfiles(extractedData, onAutoSelect = onAutoSelect)
            return result to imported
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            com.wireturn.app.AppLogsState.addLog("ZIP Import Error: архив не прошёл проверку")
            return ImportResult() to emptyList()
        }
    }

    /**
     * @return Triple of result summary, List of Profile objects with FINAL local IDs, and the local ID of the recommended profile.
     */
    suspend fun importProfiles(
        data: List<Pair<String?, String>>, 
        subscriptionId: String? = null, 
        serverActiveId: String? = null,
        onAutoSelect: ((Profile) -> Unit)? = null
    ): Triple<ImportResult, List<Profile>, String?> {
        var skipped = 0
        val parsed = data.flatMap { (fileName, json) ->
            val bundle = LegacyProfileImport.itemOrNull {
                ProfileFileImport.parse(json, gson, prefs.context.getString(R.string.profile_default_name))
            }
            skipped += bundle?.skippedEntries ?: 1
            bundle?.profiles.orEmpty().map { fileName to it }
        }
        return importParsedProfiles(parsed, subscriptionId, serverActiveId, onAutoSelect, skippedEntries = skipped)
    }

    /**
     * Same as [importProfiles] but takes already-deserialized profiles (e.g. from a subscription's
     * JSON bundle), skipping a redundant serialize-to-JSON-then-reparse round trip.
     * (Named distinctly rather than overloaded: List<Pair<String?, String>> and List<Profile> erase
     * to the same JVM signature.)
     */
    suspend fun importProfileObjects(
        importedProfiles: List<Profile>,
        subscriptionId: String? = null,
        serverActiveId: String? = null,
        onAutoSelect: ((Profile) -> Unit)? = null,
        enableRouting: Boolean = false
    ): Triple<ImportResult, List<Profile>, String?> =
        importParsedProfiles(importedProfiles.map { null to it }, subscriptionId, serverActiveId, onAutoSelect, enableRouting)

    private suspend fun importParsedProfiles(
        data: List<Pair<String?, Profile>>,
        subscriptionId: String?,
        serverActiveId: String?,
        onAutoSelect: ((Profile) -> Unit)?,
        enableRouting: Boolean = false,
        skippedEntries: Int = 0
    ): Triple<ImportResult, List<Profile>, String?> {
        try {
            val checked = checkVlessImports(data) { com.wireturn.app.kernel.NativeConfigCheck.check(prefs.context, it) }
            val prepared = prepareProfileImport(checked.profiles, subscriptionId, serverActiveId, onAutoSelect, enableRouting,
                profiles.value, currentProfileId.value, skippedEntries = skippedEntries + checked.rejected.size)
            logSkippedProfiles(prepared.result.first.skipped)
            if (prepared.result.second.isNotEmpty()) {
                if (subscriptionId == null) {
                    prefs.editProfiles(ProfileListEdit.AppendMany(prepared.result.second))
                } else {
                    prefs.saveProfiles(prepared.profilesToSave)
                }
                prepared.onCommitted()
            }
            return prepared.result
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            if (subscriptionId != null) throw e
            return Triple(ImportResult(), emptyList(), null)
        }
    }

    private data class PreparedProfileImport(
        val result: Triple<ImportResult, List<Profile>, String?>,
        val profilesToSave: List<Profile>,
        val onCommitted: () -> Unit
    )

    private fun prepareProfileImport(
        data: List<Pair<String?, Profile>>,
        subscriptionId: String?,
        serverActiveId: String?,
        onAutoSelect: ((Profile) -> Unit)?,
        enableRouting: Boolean,
        currentProfiles: List<Profile>,
        currentSelectedId: String,
        protectedProfileIds: Set<String> = emptySet(),
        allowEmptySnapshot: Boolean = false,
        tolerantEntries: Boolean = true,
        skippedEntries: Int = 0
    ): PreparedProfileImport {
        val defaultName = prefs.context.getString(R.string.profile_default_name)
        val wasSelectedFromThisSub = currentProfiles.find { it.id == currentSelectedId }?.subscriptionId == subscriptionId

        val existingSubProfiles = if (subscriptionId != null) {
            currentProfiles.filter { SubscriptionProfileContinuity.belongs(it, subscriptionId) }
        } else emptyList()

        val accumulating = currentProfiles.toMutableList()
        val importedList = mutableListOf<Profile>()

        var addedCount = 0
        var updatedCount = 0
        var resolvedRecommendedLocalId: String? = null

        val matchedExistingIds = mutableSetOf<String>()
        val usedInThisBatchIds = mutableSetOf<String>()

        val (sanitizedData, skippedDuringPreparation) = if (tolerantEntries) {
            LegacyProfileImport.prepare(data, defaultName)
        } else {
            data.map { (file, profile) -> file to profile.sanitize(defaultName) } to 0
        }
        val skippedCount = skippedEntries + skippedDuringPreparation
        val preparedData = AwgProfileDedup.filter(sanitizedData, { it.second }, serverActiveId) { original, profile -> original.first to profile }
        val identityIndex = SubscriptionIdentity.Index(preparedData.map { it.second }, existingSubProfiles)

        preparedData.forEach { (fileName, p) ->
            val nameFromFile = fileName?.removeSuffix(".json")?.removePrefix("tuna_")?.removePrefix("wt_")
            val name = (p.name as String?)?.takeIf { it.isNotBlank() }
                ?: nameFromFile?.takeIf { it.isNotBlank() }?.take(100)
                ?: nextDefaultProfileName(accumulating)

            // 1. Try to preserve local ID by matching the server's own stable per-profile id first
            // (survives renames and doesn't collide when two entries share a display name). Only
            // fall back to matching by name for local profiles that have no recorded source id -
            // i.e. the subscription didn't send a stable "id" for that entry on a previous sync.
            // A local profile that HAS a source id must never be re-matched by name alone, or a
            // same-named-but-different new entry would silently steal its local id (and with it,
            // "is this still selected" continuity) out from under it.
            val sourceId = p.id.takeIf { it.isNotBlank() }
            val existing = sourceId?.let { identityIndex.match(p.copy(id = it), usedInThisBatchIds) }
                ?: existingSubProfiles.filter { it.subscriptionSourceId == null && it.name == name && it.id !in usedInThisBatchIds }
                    .singleOrNull()?.takeIf { preparedData.count { pair -> pair.second.name == name } == 1 }
            val matchedOldId = existing?.id

            val newId = if (matchedOldId != null && matchedOldId !in usedInThisBatchIds) {
                matchedOldId
            } else {
                UUID.randomUUID().toString()
            }

            // 2. Map ID: check if it's the server's recommendation
            if (p.id == serverActiveId) {
                resolvedRecommendedLocalId = newId
            }

            usedInThisBatchIds.add(newId)

            val profile = if (subscriptionId != null) {
                SubscriptionProfileRefresh.apply(p, existing, newId, subscriptionId, sourceId, name)
            } else {
                VlessFingerprintImport.profile(p.copy(
                    id = newId,
                    name = name,
                    subscriptionId = null,
                    subscriptionSourceId = null,
                    subscriptionKernelBaseline = null,
                    subscriptionAuxBaseline = null,
                    subscriptionOriginalName = null,
                    retainedFromSubscription = null,
                    retainedSubscriptionId = null
                ))
            }
            if (existing != null) {
                matchedExistingIds.add(existing.id)
                if (existing != profile) updatedCount++
            } else {
                addedCount++
            }

            accumulating.add(profile)
            importedList.add(profile)
        }

        if (importedList.isEmpty() && !allowEmptySnapshot) return PreparedProfileImport(Triple(ImportResult(skipped = skippedCount), emptyList(), null), currentProfiles) {}
        val wasEmpty = currentProfiles.isEmpty()

        val newList = if (subscriptionId != null) {
            SubscriptionProfileContinuity.afterRefresh(currentProfiles, importedList, subscriptionId,
                subscriptions.value.find { it.id == subscriptionId }?.name ?: "Подписка",
                protectedProfileIds + currentSelectedId + listOfNotNull(com.wireturn.app.CoreServiceState.session.value?.profileId))
        } else {
            currentProfiles + importedList
        }

        val onCommitted = committed@{
            if (!SubscriptionProfileContinuity.mayActivateAfterRefresh(subscriptionId,
                    com.wireturn.app.CoreServiceState.status.value !is com.wireturn.app.CoreStatus.Idle)) return@committed
            // A refresh must not restore a profile the user switched away from.
            if (currentProfileId.value != currentSelectedId) return@committed

            val callback = onAutoSelect ?: { profile: Profile -> activateImportedProfile(currentSelectedId, profile.id) }
            val oldVersionOfCurrent = currentProfiles.find { it.id == currentSelectedId }
            val updatedVersionOfCurrent = (importedList.find { it.id == currentSelectedId }
                ?: oldVersionOfCurrent.takeIf { enableRouting })
            val isStillSelected = newList.any { it.id == currentSelectedId }

            if (updatedVersionOfCurrent != null) {
                // Only trigger update if content actually changed
                if (enableRouting || updatedVersionOfCurrent != oldVersionOfCurrent) {
                    callback?.invoke(updatedVersionOfCurrent)
                }
            } else if (wasEmpty || (wasSelectedFromThisSub && !isStillSelected)) {
                // Auto-select based on recommendation OR fallback to first
                val bestToSelect = importedList.find { it.id == resolvedRecommendedLocalId }
                    ?: importedList.firstOrNull()
                bestToSelect?.let { callback?.invoke(it) }
            } else if (!isStillSelected && newList.isNotEmpty()) {
                val fallback = findBestFallbackProfile(newList, subscriptions.value)
                fallback?.let { callback?.invoke(it) }
            }
        }

        val removedCount = if (subscriptionId != null) existingSubProfiles.size - matchedExistingIds.size else 0
        return PreparedProfileImport(
            Triple(ImportResult(addedCount, updatedCount, removedCount, importedList.size, skippedCount), importedList, resolvedRecommendedLocalId),
            newList, onCommitted
        )
    }

    private fun logSkippedProfiles(count: Int) {
        if (count > 0) AppLogsState.addLog("Profile import: skipped $count unsupported or invalid entries")
    }

    suspend fun fetchSubscription(url: String, forceId: String? = null, allowDirectUpdate: Boolean = false,
        onAutoSelect: ((Profile) -> Unit)? = null): ImportStatus =
        requireNotNull(coordinatedFetch(url, forceId, onAutoSelect, allowDirectUpdate = allowDirectUpdate))

    private suspend fun coordinatedFetch(url: String, forceId: String? = null,
        onAutoSelect: ((Profile) -> Unit)? = null, automatic: Boolean = false, allowDirectUpdate: Boolean = false): ImportStatus? {
        val sub = prefs.subscriptionsFlow.first().find { it.id == forceId || it.url == url }
        // URL also deduplicates the first import, before a persistent ID exists.
        return subscriptionRefreshCoordinator.run(url, automatic,
            (sub?.updateIntervalMinutes ?: 1440).toLong() * 60_000,
            { it is ImportStatus.Success }) {
            try { fetchSubscriptionOnce(url, forceId, onAutoSelect, sub?.requireTunnelForUpdate == true && !allowDirectUpdate) }
            finally { sub?.id?.let { id -> _updatingSubIds.update { it - id } } }
        }
    }

    private suspend fun fetchSubscriptionOnce(url: String, forceId: String?, onAutoSelect: ((Profile) -> Unit)?, requireTunnel: Boolean): ImportStatus = withContext(Dispatchers.IO) {
        val subIdToMark = forceId ?: subscriptions.value.find { it.url == url }?.id
        subIdToMark?.let { id -> _updatingSubIds.update { it + id } }
        val startTime = System.currentTimeMillis()

        // Defense in depth: the network security config permits cleartext app-wide (it has to, for
        // arbitrary LAN subscription servers), so enforce HTTPS for non-local hosts here instead.
        if (url.startsWith("http://") && !isLocalNetworkHost(url)) {
            com.wireturn.app.AppLogsState.addLog("Subscription Error: refusing cleartext HTTP to non-local host")
            subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
            return@withContext ImportStatus.NetworkError
        }

        // A loopback/LAN subscription belongs to this device/network, not the VPN server's loopback.
        val proxy = if (isLocalNetworkHost(url)) java.net.Proxy.NO_PROXY else activeLocalSocksProxy()

        val response = try {
            SubscriptionDownload.fetch(url, proxy, requireTunnel, userAgent)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Covers both "no proxy, direct attempt failed" and "proxy failed, direct fallback also failed" -
            // previously these could throw past this point uncaught, permanently killing the auto-update loop.
            com.wireturn.app.AppLogsState.addLog("Subscription Error: ${e.javaClass.simpleName} - ${e.message}")
            subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
            return@withContext if (e is IllegalArgumentException || e is IllegalStateException) ImportStatus.InvalidFormat else ImportStatus.NetworkError
        }

        try {
            val result = run {
                val responseCode = response.code
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    com.wireturn.app.AppLogsState.addLog("Subscription HTTP Error: $responseCode")
                    ImportStatus.ServerError(responseCode)
                } else {
                    val contentBytes = response.bytes
                    val appRouting = AppRoutingSubscription.extract(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(contentBytes)).toString())
                    val content = appRouting.content
                    if (content.isBlank() && appRouting.fullRouting != null) {
                        // A command-only URL is a one-shot import, not a server subscription.
                        prefs.importTunaRouting(appRouting.fullRouting.copy(apps = appRouting.command))
                        return@run ImportStatus.Success(message = "Общая маршрутизация и приложения импортированы. Для активного VPN переподключитесь.")
                    }
                    if (content.isBlank() && appRouting.command != null) {
                        prefs.importAppExclusions(appRouting.command)
                        return@run ImportStatus.Success(message = "Режим приложений включён, список дополнен. Для активного VPN переподключитесь.")
                    }
                    if (content.isBlank()) {
                        com.wireturn.app.AppLogsState.addLog("Subscription Error: Empty response")
                        ImportStatus.EmptyResponse
                    } else {
                        kotlinx.coroutines.yield() // Check for cancellation
                        val routingHeader = appRouting.fullRouting?.source
                            ?: response.header("Routing")?.takeIf(String::isNotBlank)
                            ?: appRouting.legacyRouting.orEmpty()
                        val routingRecommended = routingHeader.isNotBlank()
                        if (routingRecommended) SubscriptionPolicyParser.parseHapp(routingHeader)
                        var groupEnvelope = GroupUriTransport.extract(content)
                        val rootObject = groupEnvelope?.document?.source ?: runCatching { JsonParser.parseString(content).asJsonObject }.getOrNull()
                        var tuna = groupEnvelope?.document ?: if (rootObject?.get("schema")?.asString == "tuna.subscription")
                            TunaSubscriptionDocument.parse(content, setOf("happ-routing-v1", "split-dns-v1", SubscriptionGroups.CAPABILITY)) else null
                        // An empty server-authored group snapshot may delete a subscription's entries.
                        // A nonempty snapshot whose endpoints all fail validation must preserve them.
                        val allowEmptyGroupSnapshot = groupEnvelope?.document?.let {
                            it.profiles.isEmpty() && it.source.getAsJsonArray("groups").isEmpty
                        } == true
                        var skippedEntries = 0
                        val importIssues = mutableListOf<com.wireturn.app.data.SubscriptionImportIssue>()
                        if (tuna != null) {
                            val filtered = SubscriptionEntryFilter.filterDocument(tuna.source) { entry, index ->
                                importIssues += com.wireturn.app.data.SubscriptionImportIssue.json(entry, index)
                            }
                            skippedEntries = filtered.second
                            tuna = TunaSubscriptionDocument.parse(filtered.first.toString(), setOf("happ-routing-v1", "split-dns-v1", SubscriptionGroups.CAPABILITY))
                            groupEnvelope = groupEnvelope?.copy(document = tuna)
                        }
                        val ordinaryBundle = groupEnvelope?.let { tryParseTextSubscription(it.ordinaryText) }
                        val parsedBundle = if (tuna != null) ProfileBundle(
                            name = rootObject?.get("name")?.asString,
                            profiles = if (groupEnvelope != null) GroupUriTransport.mergeProfiles(groupEnvelope, ordinaryBundle?.profiles.orEmpty())
                                else tuna.profiles.map { gson.fromJson(it, Profile::class.java).sanitize() },
                            skippedEntries = ordinaryBundle?.skippedEntries ?: 0,
                            importIssues = ordinaryBundle?.importIssues,
                            updateIntervalMinutes = rootObject?.get("updateIntervalSeconds")?.asLong?.let { seconds ->
                                require(seconds in 1200..2592000 && seconds % 60 == 0L) { "Invalid refresh interval" }
                                (seconds / 60).toInt()
                            }
                        ) else if (com.wireturn.app.data.kernel.AwgUri.isIni(content)) {
                            parseWgProfileImport(content)
                        } else if (content.trimStart().let { it.startsWith("{") || it.startsWith("[") }) {
                            if (rootObject?.has("locations") == true && !rootObject.has("schema")) tryParseOlcboxBundle(content)
                            else LegacyProfileImport.parse(content, gson)
                        } else {
                            val decoded = ProfileEncoder.decode(content)
                            if (decoded != null) LegacyProfileImport.parse(decoded, gson)
                            else tryParseTextSubscription(content) ?: tryParseBase64TextSubscription(content)
                        }
                        val structurallyValidBundle = parsedBundle?.let { parsed ->
                            if (tuna != null) parsed else {
                                val (profiles, skipped) = LegacyProfileImport.prepare(parsed.profiles.map { null to it }, "Profile") {
                                    importIssues += com.wireturn.app.data.SubscriptionImportIssue.profile(it)
                                }
                                parsed.copy(profiles = profiles.map { it.second }, skippedEntries = parsed.skippedEntries + skipped)
                            }
                        }

                        val bundle = structurallyValidBundle?.let { parsed ->
                            val checked = checkVlessImports(parsed.profiles.map { null to it }) {
                                com.wireturn.app.kernel.NativeConfigCheck.check(prefs.context, it)
                            }
                            importIssues += checked.rejected
                            parsed.copy(profiles = checked.profiles.map { it.second },
                                skippedEntries = parsed.skippedEntries + checked.rejected.size)
                        }
                        if (bundle != null) {
                            importIssues += bundle.importIssues.orEmpty()
                            if (bundle.profiles.isEmpty() && !allowEmptyGroupSnapshot && importIssues.isNotEmpty()) {
                                // A failed refresh records its diagnostics without replacing profiles,
                                // selection, routing or the last successfully imported server metadata.
                                val snapshot = prefs.readSubscriptionImportSnapshot()
                                val old = snapshot.subscriptions.find { it.id == subIdToMark || it.url == url }
                                val report = com.wireturn.app.data.SubscriptionImportReport(rejected = importIssues.toList())
                                if (old != null) {
                                    prefs.commitSubscriptionImport(snapshot, snapshot.profiles,
                                        snapshot.subscriptions.map { if (it.id == old.id) it.copy(importReport = report) else it }, null)
                                }
                                return@withContext ImportStatus.Rejected(report)
                            }
                            val subName = SubscriptionTitle.decode(bundle.name) ?: SubscriptionTitle.decode(response.header("Profile-Title")) ?: URL(url).host ?: "Подписка"

                            // Fallbacks for panels (e.g. 3x-ui) that convey quota/refresh via response headers
                            // instead of the subscription body - only consulted when the body itself has nothing.
                            val (headerBytesUsed, headerBytesTotal) = parseSubscriptionUserinfo(response.header("Subscription-Userinfo"))
                            val headerUpdateIntervalMinutes = response.header("Profile-Update-Interval")
                                ?.toDoubleOrNull()?.let { hours -> (hours * 60).toInt() }

                            kotlinx.coroutines.yield()

                            val routingSource = routingHeader.ifBlank { tuna?.source?.getAsJsonArray("routingPolicies")?.firstOrNull()?.asJsonObject?.get("source")?.asString.orEmpty() }
                            val priorSubscription = prefs.subscriptionsFlow.first().find { it.id == subIdToMark || it.url == url }
                            val activateRouting = ProfileRouting.afterImport(priorSubscription, routingSource, routingRecommended)
                            if (activateRouting) {
                                require(bundle.profiles.isNotEmpty()) { "Empty subscription" }
                                bundle.profiles.forEach { it.sanitize() }
                            }
                            if (activateRouting) {
                                SubscriptionRoutingAssets.prepare(prefs.context, routingSource,
                                    effective = SubscriptionRuleOverrides.load(priorSubscription?.routingOverridesJson)
                                        .apply(SubscriptionPolicyParser.parseHapp(routingSource).routing))
                            }
                            // Build the complete import from one persisted snapshot after every
                            // suspendable asset download. The commit checks this snapshot again and
                            // writes profiles, metadata and routing in a single DataStore transaction.
                            val snapshot = prefs.readSubscriptionImportSnapshot()
                            val subId = subIdToMark ?: snapshot.subscriptions.find { it.url == url }?.id ?: UUID.randomUUID().toString()
                            val existingSub = snapshot.subscriptions.find { it.id == subId }
                            val enabledFromSnapshot = ProfileRouting.afterImport(existingSub, routingSource, routingRecommended)
                            // The user may have enabled this subscription while the response was downloading.
                            // Prepare this response too; the atomic commit below rejects any further edits.
                            if (enabledFromSnapshot && (!activateRouting || existingSub?.routingOverridesJson != priorSubscription?.routingOverridesJson))
                                SubscriptionRoutingAssets.prepare(prefs.context, routingSource,
                                    effective = SubscriptionRuleOverrides.load(existingSub?.routingOverridesJson)
                                        .apply(SubscriptionPolicyParser.parseHapp(routingSource).routing))
                            if (tuna != null && !existingSub?.tunaDocument.isNullOrBlank()) {
                                val previous = JsonParser.parseString(existingSub.tunaDocument).asJsonObject
                                require(previous["subscriptionId"].asString == tuna.id && previous["revision"].asLong <= tuna.revision) {
                                    "Subscription identity or revision mismatch"
                                }
                                if (groupEnvelope != null && previous["revision"].asLong == tuna.revision) {
                                    require(previous == tuna.source) { "Conflicting group snapshot revision" }
                                }
                            }
                            val prepared = prepareProfileImport(
                                data = bundle.profiles.map { null to it },
                                subscriptionId = subId,
                                serverActiveId = bundle.recommendedProfileId,
                                onAutoSelect = onAutoSelect,
                                enableRouting = enabledFromSnapshot && snapshot.profiles.any {
                                    it.id == snapshot.currentProfileId && it.subscriptionId == subId
                                },
                                currentProfiles = snapshot.profiles,
                                currentSelectedId = snapshot.currentProfileId,
                                protectedProfileIds = snapshot.protectedProfileIds,
                                allowEmptySnapshot = allowEmptyGroupSnapshot,
                                tolerantEntries = tuna == null,
                                skippedEntries = skippedEntries + bundle.skippedEntries
                            )
                            val (importResult, importedProfiles, resolvedActiveId) = prepared.result
                            logSkippedProfiles(importResult.skipped)
                            require(importedProfiles.isNotEmpty() || allowEmptyGroupSnapshot) { "В подписке нет корректных профилей; предыдущие настройки сохранены" }

                            // 2. Determine final active profile ID to save in subscription
                            val bestActiveId = resolvedActiveId ?: 
                                existingSub?.activeProfileId?.takeIf { id -> importedProfiles.any { it.id == id } } ?:
                                importedProfiles.firstOrNull()?.id

                            val newSubscription = Subscription(
                                id = subId,
                                name = subName,
                                url = url,
                                description = bundle.description,
                                updatedAt = System.currentTimeMillis(),
                                bytesUsed = bundle.bytesUsed ?: headerBytesUsed ?: 0,
                                bytesTotal = bundle.bytesTotal ?: headerBytesTotal ?: 0,
                                activeProfileId = bestActiveId,
                                autoUpdate = existingSub?.autoUpdate ?: ((bundle.updateIntervalMinutes ?: headerUpdateIntervalMinutes) != null),
                                updateIntervalMinutes = existingSub?.updateIntervalMinutes
                                    ?: (bundle.updateIntervalMinutes ?: headerUpdateIntervalMinutes)?.coerceAtLeast(20)
                                    ?: 1440,
                                onlyUpdateIfSelected = existingSub?.onlyUpdateIfSelected ?: false,
                                requireTunnelForUpdate = existingSub?.requireTunnelForUpdate ?: false,
                                routingSource = routingSource,
                                routingRecommended = routingRecommended || SubscriptionPolicyParser.activates(routingSource),
                                useSubscriptionRouting = ProfileRouting.afterImport(existingSub, routingSource, routingRecommended),
                                routingOverridesJson = existingSub?.routingOverridesJson,
                                tunaDocument = tuna?.source?.toString() ?: "",
                                importReport = com.wireturn.app.data.SubscriptionImportReport(
                                    rejected = importIssues.toList(),
                                    duplicates = AwgProfileDedup.duplicateIssues(bundle.profiles, bundle.recommendedProfileId)
                                )
                            )

                            // 3. Save subscription info
                            val currentSubs = snapshot.subscriptions
                            val newSubs = if (currentSubs.any { it.id == subId }) {
                                currentSubs.map { if (it.id == subId) newSubscription else it }
                            } else {
                                currentSubs + newSubscription
                            }
                            prefs.commitSubscriptionImport(snapshot, prepared.profilesToSave, newSubs,
                                null, appRouting.command, routingSubscriptionId = subId.takeIf { enabledFromSnapshot })
                            scope.launch { prepared.onCommitted() }
                            
                            ImportStatus.Success(subId, importResult)
                        } else {
                            ImportStatus.InvalidFormat
                        }
                    }
                }
            }
            
            // Ensure minimum visible loading time
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < 300) {
                kotlinx.coroutines.delay((300 - elapsed).milliseconds)
            }
            
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Parser errors can embed the rejected URI or its credentials.
            com.wireturn.app.AppLogsState.addLog("Subscription Error: ${e.javaClass.simpleName}; previous settings preserved")
            if (e is IllegalArgumentException || e is IllegalStateException) ImportStatus.InvalidFormat else ImportStatus.NetworkError
        } finally {
            subIdToMark?.let { id -> _updatingSubIds.update { it - id } }
        }
    }

    fun deleteSubscription(id: String, onFallback: (Profile) -> Unit = {}) {
        scope.launch {
            val removed = prefs.deleteSubscription(id)
            if (removed.removedSelectedId != null) {
                val current = prefs.readSubscriptionImportSnapshot()
                if (current.currentProfileId == removed.removedSelectedId) {
                    findBestFallbackProfile(current.profiles, current.subscriptions)?.let { onFallback(it) }
                }
            }
        }
    }

    private fun findBestFallbackProfile(
        allProfiles: List<Profile>,
        allSubs: List<Subscription> = subscriptions.value,
        preferredSubId: String? = null
    ): Profile? {
        if (allProfiles.isEmpty()) return null

        // 1. If we were in a sub, try to stay in it
        if (preferredSubId != null) {
            val sub = allSubs.find { it.id == preferredSubId }
            if (sub != null) {
                val activeInSub = allProfiles.find { it.id == sub.activeProfileId && it.subscriptionId == sub.id }
                if (activeInSub != null) return activeInSub
                
                val firstInSub = allProfiles.find { it.subscriptionId == sub.id }
                if (firstInSub != null) return firstInSub
            }
        }

        // 2. Try first standalone profile
        val standalone = allProfiles.find { it.subscriptionId == null }
        if (standalone != null) return standalone

        // 3. Try active profile from any subscription
        allSubs.forEach { sub ->
            if (sub.id != preferredSubId) {
                val activeInSub = allProfiles.find { it.id == sub.activeProfileId && it.subscriptionId == sub.id }
                if (activeInSub != null) return activeInSub
                
                val firstInSub = allProfiles.find { it.subscriptionId == sub.id }
                if (firstInSub != null) return firstInSub
            }
        }

        // 4. Just first one ever
        return allProfiles.firstOrNull()
    }

    fun updateSubscription(sub: Subscription) {
        scope.launch { prefs.editSubscription(SubscriptionEdit.Settings(sub)) }
    }

    // Reads olcbox's (github.com/alananisimov/olcbox) native LocationBundleV4 JSON subscription/export
    // format, e.g. {"version":5,"active_location_id":"...","locations":[{"storage_id":"...",
    // "name":"...","endpoint":{"room_id":"...","key":"..."},"auth_provider":"jitsi",
    // "transport":{"type":"vp8channel","vp8":{"fps":60,"batch":64}},"dns_server":"..."}]}.
    // "transport" may also be a bare string (its legacy compact form). Unlike WireTurn's own
    // ProfileBundle, every field here is olcbox-specific, so this is a distinct entry point rather
    // than something the OlcrtcConfig/Profile Gson adapters could be taught to also accept.
    private fun tryParseOlcboxBundle(content: String): ProfileBundle? {
        val root = try { JsonParser.parseString(content).asJsonObject } catch (_: Exception) { return null }
        val locationsArray = try { root.getAsJsonArray("locations") } catch (_: Exception) { null } ?: return null

        val profiles = locationsArray.mapNotNull { element ->
            val item = try { element.asJsonObject } catch (_: Exception) { return@mapNotNull null }
            parseOlcboxLocationEntry(item)
        }
        if (profiles.isEmpty()) return null

        val activeStorageId = (root.get("active_location_id") ?: root.get("activeLocationId"))
            ?.takeIf { it.isJsonPrimitive }?.asString

        return ProfileBundle(profiles = profiles, recommendedProfileId = activeStorageId)
    }

    private fun parseOlcboxLocationEntry(item: com.google.gson.JsonObject): Profile? {
        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = item.get(k)
                if (v != null && v.isJsonPrimitive) return v.asString
            }
            return null
        }
        fun obj(key: String): com.google.gson.JsonObject? =
            item.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        val endpoint = obj("endpoint")
        val provider = str("auth_provider", "authProvider", "carrier", "bypass_provider", "bypassProvider", "provider")
            ?: "wbstream"
        val roomId = endpoint?.get("room_id")?.takeIf { it.isJsonPrimitive }?.asString
            ?: str("id", "room_id", "server")
        val key = endpoint?.get("key")?.takeIf { it.isJsonPrimitive }?.asString
            ?: str("key", "password")
        if (roomId.isNullOrBlank() || key.isNullOrBlank()) return null

        val transportElement = item.get("transport")
        val transportObj = transportElement?.takeIf { it.isJsonObject }?.asJsonObject
        val transport = when {
            transportElement != null && transportElement.isJsonPrimitive -> transportElement.asString
            transportObj != null -> transportObj.get("type")?.takeIf { it.isJsonPrimitive }?.asString
            else -> null
        } ?: "datachannel"
        val vp8 = transportObj?.get("vp8")?.takeIf { it.isJsonObject }?.asJsonObject
        val vp8Fps = (vp8?.get("fps")?.takeIf { it.isJsonPrimitive }?.asInt)
            ?: str("vp8_fps", "vp8Fps")?.toIntOrNull()
        val vp8Batch = (vp8?.get("batch")?.takeIf { it.isJsonPrimitive }?.asInt)
            ?: str("vp8_batch", "vp8Batch")?.toIntOrNull()
        val dns = str("dns_server", "dnsServer")

        val storageId = str("storage_id", "storageId") ?: ""
        val metadataName = obj("metadata")?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        val name = str("name")?.takeIf { it.isNotBlank() }
            ?: metadataName?.takeIf { it.isNotBlank() }
            ?: roomId

        var config = OlcrtcConfig(provider = provider, transport = transport, id = roomId, key = key)
        vp8Fps?.let { config = config.copy(vp8Fps = it) }
        vp8Batch?.let { config = config.copy(vp8Batch = it) }
        dns?.takeIf { it.isNotBlank() }?.let { config = config.copy(dns = it) }

        return Profile(id = storageId, name = name, kernelConfig = KernelConfig.Olcrtc(config))
    }

    // Text-subscription entries have no server-supplied stable id the way JSON ProfileBundle entries
    // do, so importParsedProfiles' id-based re-matching (subscriptionSourceId) would otherwise never
    // work past the very first sync - a fresh UUID.randomUUID() here would look "new" on every single
    // refresh, permanently losing the local profile's identity (and with it, any locally-configured
    // Xray settings, see subscriptionHasXrayConfig above) starting from the second refresh onward.
    // Deriving a deterministic id from the entry's own raw URI line instead keeps the same identity
    // across refreshes as long as the URI itself doesn't change, matching the "survives renames"
    // intent of the id-matching comment in importParsedProfiles (renames go through name-only fallback
    // matching, unrelated to this).
    private fun stableTextSubEntryId(rawUriLine: String): String =
        UUID.nameUUIDFromBytes(rawUriLine.toByteArray(Charsets.UTF_8)).toString()

    private fun tryParseTextSubscription(text: String): ProfileBundle? {
        if (com.wireturn.app.data.kernel.AwgUri.isIni(text)) return parseWgProfileImport(text)
        if (!SubscriptionTextFormat.recognizes(text)) return null

        val lines = text.lines().flatMap { line ->
            if (line.trim().startsWith("vpn://", true)) runCatching {
                com.wireturn.app.data.kernel.AwgUri.normalizeAll(line.trim())
            }.getOrElse { listOf(line) } else listOf(line)
        }
        var skippedEntries = 0
        val importIssues = mutableListOf<com.wireturn.app.data.SubscriptionImportIssue>()
        var subName: String? = null
        var subDescription: String? = null
        var subInterval: Int? = null
        var subBytesUsed: Long? = null
        var subBytesTotal: Long? = null

        val profiles = mutableListOf<Profile>()
        var currentProfile: Profile? = null
        var currentKernelConfig: KernelConfig? = null

        fun parseInterval(v: String): Int? {
            val num = v.filter { it.isDigit() }.toIntOrNull() ?: return null
            return when {
                v.endsWith("s") -> num / 60
                v.endsWith("m") -> num
                v.endsWith("h") -> num * 60
                v.endsWith("d") -> num * 1440
                else -> num
            }
        }

        // Parses sizes like "10mb", "1.1gb", "500 KB" (binary units: 1kb = 1024b) as used by the
        // olcRTC/free-turn-proxy sub.md convention's #used/#available fields.
        fun parseByteSize(v: String): Long? {
            val match = Regex("(?i)^\\s*([0-9]*\\.?[0-9]+)\\s*(b|kb|mb|gb|tb)?\\s*$").find(v) ?: return null
            val num = match.groupValues[1].toDoubleOrNull() ?: return null
            val multiplier = when (match.groupValues[2].lowercase()) {
                "", "b" -> 1.0
                "kb" -> 1024.0
                "mb" -> 1024.0 * 1024
                "gb" -> 1024.0 * 1024 * 1024
                "tb" -> 1024.0 * 1024 * 1024 * 1024
                else -> return null
            }
            return (num * multiplier).toLong()
        }

        fun flush() {
            val p = currentProfile ?: return
            val kc = currentKernelConfig ?: return
            profiles.add(p.copy(kernelConfig = kc))
            currentProfile = null
            currentKernelConfig = null
        }

        for ((lineIndex, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val kernelMatch = runCatching { KernelRegistry.decodeUri(trimmed) }.getOrNull()
            if (kernelMatch == null && trimmed.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) &&
                !trimmed.startsWith("tuna://") && !trimmed.startsWith("wireturn://") && !trimmed.startsWith("wt://")) {
                com.wireturn.app.AppLogsState.addLog("Subscription: unsupported or invalid ${trimmed.substringBefore(':').take(20)} entry")
                flush()
                skippedEntries++
                importIssues += com.wireturn.app.data.SubscriptionImportIssue.uri(trimmed, index = lineIndex)
                continue
            }
            if (kernelMatch != null) {
                flush()
                val (kernel, kernelConfig) = kernelMatch
                val nameFromUri = kernel.displayNameFromUri(trimmed)?.takeIf { it.isNotBlank() }

                currentKernelConfig = kernelConfig
                currentProfile = Profile(
                    id = (kernelConfig as? KernelConfig.Vpn)?.config?.takeIf { it.protocol in setOf("awg", "wireguard") }
                            ?.let { stableTextSubEntryId("wg-effective:" + com.wireturn.app.data.kernel.AwgUri.identity(it.uri)) }
                        ?: stableTextSubEntryId(trimmed),
                    name = nameFromUri ?: kernel.defaultProfileName,
                    kernelConfig = kernelConfig,
                    subscriptionUriIdentity = true
                )
            } else if (trimmed.startsWith("tuna://") || trimmed.startsWith("wireturn://") || trimmed.startsWith("wt://")) {
                // A wireturn:// container carries a full Profile (or several) as-is - kernelConfig,
                // xrayEnabled/xrayProtocol, vlessConfig/wgConfig and its own stable `id`, all together.
                // Unlike the other schemes above, there's nothing to reconstruct: just decode and use
                // it directly, which also means its own id (not a hash of this line) drives matching
                // on refresh, and any Xray config it carries takes precedence the same way a JSON
                // subscription entry's would (see subscriptionHasXrayConfig in importParsedProfiles).
                flush()
                val encoded = trimmed.substringAfter("://")
                val decodedJson = ProfileEncoder.decode(encoded)
                val decodedBundle = decodedJson?.let {
                    LegacyProfileImport.itemOrNull {
                        LegacyProfileImport.parse(it, gson, missingId = { index, total ->
                            stableTextSubEntryId(if (total == 1) trimmed else "$trimmed#$index")
                        })
                    }
                }
                skippedEntries += decodedBundle?.skippedEntries ?: 1
                if (decodedBundle == null) importIssues += com.wireturn.app.data.SubscriptionImportIssue.uri(trimmed)
                else importIssues += decodedBundle.importIssues.orEmpty()
                val decodedProfiles = decodedBundle?.profiles
                if (decodedProfiles.isNullOrEmpty()) continue

                if (decodedProfiles.size == 1) {
                    // Keep it as the "current" profile so any ##tags on following lines can still
                    // adjust it, same as every other scheme above.
                    val p = decodedProfiles[0]
                    currentKernelConfig = p.kernelConfig
                    currentProfile = p
                } else {
                    // Multiple profiles in one blob - push them all now, nothing left to be "current".
                    // Missing IDs were derived from the original indices before invalid entries
                    // were removed, so a skipped neighbor cannot change the remaining identities.
                    profiles.addAll(decodedProfiles)
                }
            } else if (trimmed.startsWith("##")) {
                if (currentProfile == null) continue
                val parts = trimmed.substring(2).split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when (key) {
                        "name" -> currentProfile = currentProfile?.copy(name = value)
                        "id" -> {
                            // Overrides the default identity (a hash of the entry's own raw URI line,
                            // see stableTextSubEntryId) with an author-supplied stable id - needed when
                            // the URI's own content is expected to change between refreshes (rotating
                            // peer/port/obfuscation etc.) but it's still logically the same entry and
                            // should keep its locally-configured Xray settings across that change.
                            if (value.isNotBlank()) {
                                currentProfile = currentProfile?.copy(id = value)
                            }
                        }
                        "ip" -> {
                            val kc = currentKernelConfig
                            if (kc is KernelConfig.FreeTurn) {
                                currentKernelConfig = KernelConfig.FreeTurn(kc.config.copy(peer = value))
                            }
                        }
                        "provider" -> {
                            val kc = currentKernelConfig
                            if (kc is KernelConfig.FreeTurn) {
                                currentKernelConfig = KernelConfig.FreeTurn(kc.config.copy(provider = value))
                            } else if (kc is KernelConfig.Olcrtc) {
                                currentKernelConfig = KernelConfig.Olcrtc(kc.config.copy(provider = value))
                            }
                        }
                        "comment" -> {
                            if (currentProfile?.name?.contains("Server") == true) {
                                currentProfile = currentProfile?.copy(name = value)
                            }
                        }
                        "xray" -> {
                            // A raw VLESS/Trojan/Hysteria2 link for the current profile's Xray leg.
                            if (com.wireturn.app.ui.ValidatorUtils.isValidVlessLink(value)) {
                                currentProfile = currentProfile?.copy(
                                    xrayEnabled = true,
                                    xrayProtocol = com.wireturn.app.data.XrayConfiguration.VLESS,
                                    vlessConfig = com.wireturn.app.data.VlessConfig(vlessLink = value)
                                )
                            }
                        }
                        "xray-wg" -> {
                            // WireGuard is a multi-line [Interface]/[Peer] config, so it can't be a bare
                            // link like ##xray - the value is that config text, base64-encoded to fit on
                            // one line (plain, not URL-safe - matches how panels commonly wrap wg-quick).
                            val decoded = try {
                                String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8)
                            } catch (_: Exception) { null }
                            val wg = decoded?.let { com.wireturn.app.data.WgConfig.parse(it) }
                            if (wg != null && wg.isValid()) {
                                currentProfile = currentProfile?.copy(
                                    xrayEnabled = true,
                                    xrayProtocol = com.wireturn.app.data.XrayConfiguration.WIREGUARD,
                                    wgConfig = wg
                                )
                            }
                        }
                    }
                }
            } else if (trimmed.startsWith("#")) {
                val parts = trimmed.substring(1).split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when (key) {
                        "name" -> subName = value
                        "description" -> subDescription = value
                        "refresh" -> subInterval = parseInterval(value)
                        "comment" -> if (subDescription == null) subDescription = value
                        "used" -> {
                            // "10mb/10gb" (used/total) or a bare "10mb" (used only)
                            val parts = value.split("/", limit = 2)
                            parseByteSize(parts[0])?.let { subBytesUsed = it }
                            if (parts.size == 2) parseByteSize(parts[1])?.let { subBytesTotal = it }
                        }
                        "available" -> {
                            // Remaining quota - total = whatever's used so far (0 if #used wasn't
                            // given yet) + what's left, regardless of which field comes first in the file.
                            parseByteSize(value)?.let { subBytesTotal = (subBytesUsed ?: 0L) + it }
                        }
                    }
                }
            }
        }
        flush()

        if (profiles.isEmpty() && importIssues.isEmpty()) return null
        return ProfileBundle(
            skippedEntries = skippedEntries,
            importIssues = importIssues,
            name = subName,
            description = subDescription,
            profiles = profiles,
            updateIntervalMinutes = subInterval,
            bytesUsed = subBytesUsed,
            bytesTotal = subBytesTotal
        )
    }

    /**
     * Some panels (e.g. 3x-ui) base64-encode a plain line-based subscription body directly,
     * unlike `wireturn://`'s zlib-deflated + URL-safe encoding ([ProfileEncoder]). Tried only
     * after the body fails as-is, since real line-based subscriptions are never valid base64.
     */
    private fun tryParseBase64TextSubscription(content: String): ProfileBundle? {
        val decoded = try {
            val cleaned = content.trim().replace(Regex("\\s"), "")
            String(android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        return tryParseTextSubscription(decoded)
    }

    /**
     * Parses the "Subscription-Userinfo" header some panels (e.g. 3x-ui) send for traffic quota:
     * "upload=X; download=Y; total=Z; expire=E" (bytes). Returns (used, total) - either half may
     * be null if that number wasn't present. "used" is upload+download combined, matching how
     * this header is interpreted across the V2Ray/Xray-adjacent subscription ecosystem.
     */
    private fun parseSubscriptionUserinfo(header: String?): Pair<Long?, Long?> {
        if (header == null) return null to null
        val fields = header.split(";").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2) kv[0].trim().lowercase() to kv[1].trim().toLongOrNull() else null
        }.toMap()
        val upload = fields["upload"]
        val download = fields["download"]
        val used = if (upload != null || download != null) (upload ?: 0L) + (download ?: 0L) else null
        return used to fields["total"]
    }
}
