package com.wireturn.app.domain

import android.content.Context
import com.wireturn.app.AppLogsState
import com.wireturn.app.viewmodel.GeoAssetsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** geoip.dat/geosite.dat variants offered to the user, mirroring what v2ray-family clients (v2rayNG etc.) ship. */
enum class GeoResourceSet(
    val id: String,
    val geoipUrl: String,
    val geositeUrl: String
) {
    // Default: RU-focused ruleset (blocked-by-Roskomnadzor domains/IPs), most relevant for
    // this app's typical audience - see runetfreedom/russia-v2ray-rules-dat.
    RUNETFREEDOM(
        id = "runetfreedom",
        geoipUrl = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat",
        geositeUrl = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat"
    ),
    V2FLY(
        id = "v2fly",
        geoipUrl = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
        geositeUrl = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
    ),
    ROSCOMVPN(
        id = "roscomvpn",
        geoipUrl = "https://github.com/hydraponique/roscomvpn-geoip/releases/latest/download/geoip.dat",
        geositeUrl = "https://github.com/hydraponique/roscomvpn-geosite/releases/latest/download/geosite.dat"
    ),
    LOYALSOLDIER(
        id = "loyalsoldier",
        geoipUrl = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat",
        geositeUrl = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
    );

    companion object {
        fun fromId(id: String): GeoResourceSet = entries.find { it.id == id } ?: RUNETFREEDOM
    }
}

/**
 * Downloads geoip.dat/geosite.dat for [-route-direct]/[-route-block] geosite:/geoip: entries
 * (see external/vless-client's -assets-path flag). Installed once, shared by every profile -
 * see XraySettings.geoVariant and the -assets-path wiring in XrayService.
 */
class GeoAssetsManager(private val context: Context) {

    val state: StateFlow<GeoAssetsState> = _state.asStateFlow()
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    // Bumped whenever the installed files change (download success, delete) so observers
    // relying on the plain installedFilesExist()/installedUpdatedAt() reads below - which
    // aren't themselves observable - know to re-read them.
    val installedTick: StateFlow<Int> = _installedTick.asStateFlow()

    fun installedFilesExist(): Boolean = filesExist(context)

    fun installedUpdatedAt(): Long {
        if (!installedFilesExist()) return 0L
        return File(assetsDir(context), GEOIP_FILE).lastModified()
    }

    fun cancelDownload() {
        cancelOngoingWork()
        _state.value = GeoAssetsState.Idle
    }

    suspend fun delete() {
        if (_state.value is GeoAssetsState.Downloading) return
        withContext(Dispatchers.IO) {
            val root = File(context.filesDir, "geo_assets")
            RoutingAssetStore.publish(root, RoutingAssetStore.stage(root))
        }
        AppLogsState.addLog("Geo resources deleted")
        _installedTick.value++
    }

    suspend fun download(variant: GeoResourceSet) {
        mutex.lock()
        try { withWorker {
            AppLogsState.addLog("Downloading geo resources (${variant.id})...")
            _state.value = GeoAssetsState.Downloading(variant)
            _downloadProgress.value = 0
            try {
                withContext(Dispatchers.IO) {
                    pruneCatalogLocked(context)
                    val root = File(context.filesDir, "geo_assets")
                    val stage = RoutingAssetStore.stage(root)
                    var published = false
                    try {
                        val cached = RoutingAssetStore.current(File(context.filesDir, "geo_catalog/${variant.id}"))
                        val cachedIp = File(cached, GEOIP_FILE)
                        val cachedSite = File(cached, GEOSITE_FILE)
                        val fresh = cachedIp.isFile && cachedSite.isFile &&
                            System.currentTimeMillis() - cachedIp.lastModified() < RoutingUpdates.intervalHours(context) * 3600000L
                        if (fresh) {
                            cachedIp.copyTo(File(stage, GEOIP_FILE), overwrite = true)
                            cachedSite.copyTo(File(stage, GEOSITE_FILE), overwrite = true)
                            _downloadProgress.value = 100
                        } else {
                            RoutingAssetStore.download(variant.geoipUrl, File(stage, GEOIP_FILE)) { _downloadProgress.value = it / 2 }
                            RoutingAssetStore.download(variant.geositeUrl, File(stage, GEOSITE_FILE)) { _downloadProgress.value = 50 + it / 2 }
                        }
                        require(GeoCategoryIndex.read(File(stage, GEOIP_FILE), "geoip:").isNotEmpty()) { "Некорректная база GeoIP" }
                        require(GeoCategoryIndex.read(File(stage, GEOSITE_FILE), "geosite:").isNotEmpty()) { "Некорректная база GeoSite" }
                        File(stage, "variant").writeText(variant.id)
                        RoutingAssetStore.publish(root, stage)
                        published = true
                    } finally { if (!published) stage.deleteRecursively() }
                }
                AppLogsState.addLog("Geo resources downloaded successfully")
                _installedTick.value++
                _state.value = GeoAssetsState.Success
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogsState.addLog("Geo resources download failed: ${e.message}")
                _state.value = GeoAssetsState.Error(e.message ?: "")
            }
        }
        } finally { mutex.unlock() }
    }

    private fun cancelOngoingWork() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend inline fun withWorker(crossinline block: suspend () -> Unit) {
        val job = currentCoroutineContext()[Job]
        activeJob = job
        try {
            block()
        } finally {
            if (activeJob == job) activeJob = null
        }
    }

    companion object {
        /** Offline maintenance also runs when the update interval has not elapsed. */
        suspend fun maintainCatalog(context: Context) {
            mutex.lock()
            try { withContext(Dispatchers.IO) { pruneCatalogLocked(context) } }
            finally { mutex.unlock() }
        }

        private fun pruneCatalogLocked(context: Context) {
            for (variant in GeoResourceSet.entries) {
                RoutingAssetStore.pruneCatalog(File(context.filesDir, "geo_catalog/${variant.id}"))
            }
        }

        /** Cache every offered provider independently; never overwrite the selected routing data. */
        fun allProvidersCached(context: Context): Boolean = GeoResourceSet.entries.all { variant ->
            val dir = RoutingAssetStore.current(File(context.filesDir, "geo_catalog/${variant.id}"))
            File(dir, GEOIP_FILE).isFile && File(dir, GEOSITE_FILE).isFile
        }

        suspend fun prefetch(context: Context, variant: GeoResourceSet, maxAgeMillis: Long) {
            mutex.lock()
            try { withContext(Dispatchers.IO) {
                val root = File(context.filesDir, "geo_catalog/${variant.id}")
                RoutingAssetStore.pruneCatalog(root)
                val cached = RoutingAssetStore.current(root)
                val ip = File(cached, GEOIP_FILE)
                val site = File(cached, GEOSITE_FILE)
                if (ip.isFile && site.isFile && System.currentTimeMillis() - ip.lastModified() < maxAgeMillis) return@withContext
                val stage = RoutingAssetStore.stage(root)
                var published = false
                try {
                    RoutingAssetStore.download(variant.geoipUrl, File(stage, GEOIP_FILE))
                    RoutingAssetStore.download(variant.geositeUrl, File(stage, GEOSITE_FILE))
                    require(GeoCategoryIndex.read(File(stage, GEOIP_FILE), "geoip:").isNotEmpty())
                    require(GeoCategoryIndex.read(File(stage, GEOSITE_FILE), "geosite:").isNotEmpty())
                    RoutingAssetStore.publish(root, stage)
                    published = true
                    RoutingAssetStore.pruneCatalog(root)
                } finally { if (!published) stage.deleteRecursively() }
            } } finally { mutex.unlock() }
        }

        private const val GEOIP_FILE = "geoip.dat"
        private const val GEOSITE_FILE = "geosite.dat"

        private val _state = MutableStateFlow<GeoAssetsState>(GeoAssetsState.Idle)
        private val _downloadProgress = MutableStateFlow(0)
        private val _installedTick = MutableStateFlow(0)
        private var activeJob: Job? = null
        private val mutex = kotlinx.coroutines.sync.Mutex()

        fun assetsDir(context: Context): File = RoutingAssetStore.current(File(context.filesDir, "geo_assets"))

        fun filesExist(context: Context): Boolean {
            val dir = assetsDir(context)
            return File(dir, GEOIP_FILE).exists() && File(dir, GEOSITE_FILE).exists()
        }
    }
}
