package com.wireturn.app.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.wireturn.app.CoreService
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Only inputs that affect a probe; excludes unrelated subscription refresh timestamps. */
data class GroupProbeSettings(
    val common: SharedNetworkSettings = SharedNetworkSettings(),
    val subscriptions: Map<String, Pair<Boolean, String?>> = emptyMap(),
    val fragmentation: com.wireturn.app.data.kernel.VpnFragmentation? = null,
    val subscriptionRuleOverrides: Map<String, String?> = emptyMap()
)

/** Android boundary for the group coordinator. Tests exercise the real coordinator with fake tunnels. */
interface GroupRuntime {
    val status: StateFlow<CoreStatus>
    suspend fun profiles(): List<Profile>
    suspend fun groups(): List<ConnectionGroup>
    suspend fun currentId(): String
    suspend fun activeGroupId(): String?
    suspend fun selectGroup(id: String)
    fun network(allowMobile: Boolean): Set<String>
    suspend fun probeSettings(profiles: List<Profile>): GroupProbeSettings = GroupProbeSettings()
    suspend fun activate(profile: Profile, routing: Profile): ClientConfig
    suspend fun <T> withTestTunnel(profile: Profile, block: suspend (ClientConfig) -> T): T
}

class AndroidGroupRuntime(private val context: Context) : GroupRuntime {
    private val prefs = AppPreferences(context)
    override val status = CoreServiceState.status
    override suspend fun profiles() = prefs.profilesFlow.first()
    override suspend fun groups() = prefs.connectionGroupsFlow.first()
    override suspend fun currentId() = prefs.currentProfileIdFlow.first()
    override suspend fun activeGroupId() = prefs.activeConnectionGroupFlow.first()
    override suspend fun selectGroup(id: String) = prefs.setActiveConnectionGroup(id)
    override suspend fun probeSettings(profiles: List<Profile>) = prefs.groupProbeSettings(profiles)
    override suspend fun <T> withTestTunnel(profile: Profile, block: suspend (ClientConfig) -> T): T =
        IsolatedProfileTest(context).withTunnel(profile, block)
    override fun network(allowMobile: Boolean): Set<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val physical = cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        require(physical.isNotEmpty()) { "Нет сети для теста" }
        val active = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        require(allowMobile || (active?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true && physical.any { (_, c) ->
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        })) { "Тесты по мобильной сети отключены в настройках группы" }
        return physical.map { it.first.toString() }.toSet()
    }

    override suspend fun activate(profile: Profile, routing: Profile): ClientConfig {
        val before = CoreServiceState.session.value
        prefs.saveFullProfile(profile.id, ProfileActivation.forGroup(profile, routing))
        val cfg = prefs.clientConfigFlow.first()
        return awaitGroupActivation(profile.id, before, CoreServiceState.session, status) {
            CoreService.start(context, cfg)
        }
    }
}
