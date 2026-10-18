package com.wireturn.app.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.wireturn.app.AppLogsState
import com.wireturn.app.R
import com.wireturn.app.viewmodel.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AppUpdater(private val context: Context) {

    val state: StateFlow<UpdateState> = _state.asStateFlow()
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private fun getCurrentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: PackageManager.NameNotFoundException) {
        "0.0.0"
    }

    /**
     * @param silent true — при ошибке сети остаёмся в [UpdateState.Idle] (автопроверка при запуске).
     *               false — показываем [UpdateState.Error] (ручная проверка из UI).
     * @param force  true — игнорировать минутный кулдаун (при смене настроек).
     */
    suspend fun checkForUpdate(silent: Boolean = false, allowUnstable: Boolean = false, force: Boolean = false) {
        // Private development releases require browser authentication; no GitHub token is embedded in the APK.
        if (com.wireturn.app.BuildConfig.PRIVATE_DISTRIBUTION) {
            _state.value = if (silent) UpdateState.Idle else UpdateState.Error(context.getString(R.string.tunaturn_private_updates))
            return
        }
        if (!force && (_state.value == UpdateState.Checking || _state.value == UpdateState.Downloading)) return
        if (!force && _state.value == UpdateState.ReadyToInstall) return
        
        if (force) cancelOngoingWork()

        val now = System.currentTimeMillis()
        if (!force && silent && now - lastCheckTime < 60_000) return
        
        lastCheckTime = now
        
        withWorker {
            AppLogsState.addLog("Checking for updates...")
            _state.value = UpdateState.Checking
            try {
                var release = withContext(Dispatchers.IO) { 
                    if (allowUnstable) fetchLatestUnstableRelease() else fetchLatestRelease() 
                }
                
                if (release == null) {
                    // Try again via the active local proxy, if any is running
                    val proxy = activeLocalSocksProxy()
                    if (proxy != java.net.Proxy.NO_PROXY) {
                        release = withContext(Dispatchers.IO) {
                            if (allowUnstable) fetchLatestUnstableRelease(proxy) else fetchLatestRelease(proxy)
                        }
                    }
                }

                if (release == null) {
                    AppLogsState.addLog("Failed to fetch release info from GitHub")
                    _state.value = if (silent) UpdateState.Idle
                    else UpdateState.Error(context.getString(R.string.error_release_info_failed))
                    return@withWorker
                }

                val remoteTag = release.getString("tag_name")
                val remoteName = release.optString("name", "")
                
                // Извлекаем версию и хеш из названия: "Unstable Build v6.1.1-a1b2c3d" -> "6.1.1-unstable-a1b2c3d"
                val remoteVersion = if (remoteTag == "unstable-latest" && remoteName.isNotBlank()) {
                    val versionRegex = """v(\d+(?:\.\d+)*)(?:-([a-f0-9]+))?""".toRegex()
                    val match = versionRegex.find(remoteName)
                    if (match != null) {
                        val ver = match.groupValues[1]
                        val hash = match.groupValues[2]
                        if (hash.isNotBlank()) "$ver-unstable-$hash" else "$ver-unstable"
                    } else {
                        "unstable-latest"
                    }
                } else {
                    remoteTag.removePrefix("v")
                }

                val remoteBody = release.optString("body", "")

                if (isNewer(remoteVersion, getCurrentVersion(), remoteBody)) {
                    latestApkUrl = findApkUrl(release)
                    if (latestApkUrl != null) {
                        AppLogsState.addLog("Update available: $remoteVersion")
                        val changelog = release.optString("body", "").trim()
                        _state.value = UpdateState.Available(remoteVersion, changelog)
                    } else {
                        AppLogsState.addLog("Update available ($remoteVersion), but no suitable APK found")
                        _state.value = if (silent) UpdateState.Idle
                        else UpdateState.Error(context.getString(R.string.error_apk_not_found))
                    }
                } else {
                    AppLogsState.addLog("No update found (current: ${getCurrentVersion()}, remote: $remoteVersion)")
                    _state.value = UpdateState.NoUpdate
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogsState.addLog("Update check failed")
                _state.value = if (silent) UpdateState.Idle
                else UpdateState.Error(context.getString(R.string.error_no_connection))
            }
        }
    }

    suspend fun downloadUpdate() {
        if (com.wireturn.app.BuildConfig.PRIVATE_DISTRIBUTION) return
        val url = latestApkUrl ?: run {
            _state.value = UpdateState.Error(context.getString(R.string.error_update_url_not_found))
            return
        }

        cancelOngoingWork()
        
        withWorker {
            AppLogsState.addLog("Downloading update...")
            _state.value = UpdateState.Downloading
            _downloadProgress.value = 0
            var staged: File? = null
            var completed: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val directory = File(context.filesDir, "updates").apply { check(isDirectory || mkdirs()) }
                    val partial = File.createTempFile("update-", ".part", directory)
                    staged = partial
                    val proxy = activeLocalSocksProxy()
                    val connection = UpdateSecurity.open(url, proxy)
                    val totalSize = connection.contentLengthLong
                    val digest = try {
                        connection.inputStream.use { input -> partial.outputStream().use { output ->
                            UpdateSecurity.copyBounded(input, output, UpdateSecurity.MAX_APK_BYTES, totalSize) { count ->
                                ensureActive()
                                if (totalSize > 0) _downloadProgress.value = (count * 100 / totalSize).toInt().coerceAtMost(100)
                            }
                            .also { output.fd.sync() }
                        } }
                    } finally { connection.disconnect() }
                    ensureActive()
                    UpdateApkValidator.verify(context, partial)
                    require(UpdateSecurity.sha256(partial) == digest) { "Файл изменился после загрузки" }
                    check(partial.setReadOnly()) { "Не удалось защитить файл обновления" }
                    val ready = File(directory, partial.nameWithoutExtension + ".apk")
                    java.nio.file.Files.move(partial.toPath(), ready.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                    completed = ready
                    ensureActive()
                    synchronized(artifactLock) { readyApk = VerifiedApk(ready, digest) }
                }
                AppLogsState.addLog("Update downloaded and signature verified")
                _state.value = UpdateState.ReadyToInstall
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogsState.addLog("Update download or verification failed")
                _state.value = UpdateState.Error(context.getString(R.string.error_download_failed, "Проверка обновления не пройдена"))
            } finally {
                staged?.delete()
                completed?.let { file -> synchronized(artifactLock) { if (readyApk?.file != file) file.delete() } }
            }
        }
    }

    fun installUpdate() {
        if (com.wireturn.app.BuildConfig.PRIVATE_DISTRIBUTION) return
        AppLogsState.addLog("Launching APK installer")

        if (!context.packageManager.canRequestPackageInstalls()) {
            AppLogsState.addLog("Requesting unknown sources permission")
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return
        }

        if (!installing.compareAndSet(false, true)) return
        installScope.launch {
            try {
                val intent = synchronized(artifactLock) {
                    val artifact = requireNotNull(readyApk) { "Нет проверенного обновления" }
                    require(artifact.file.parentFile?.canonicalFile == File(context.filesDir, "updates").canonicalFile)
                    require(UpdateSecurity.sha256(artifact.file) == artifact.digest) { "Файл обновления изменился" }
                    UpdateApkValidator.verify(context, artifact.file)
                    require(UpdateSecurity.sha256(artifact.file) == artifact.digest) { "Файл обновления изменился" }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", artifact.file)
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        clipData = android.content.ClipData.newRawUri("Verified update", uri)
                    }
                }
                withContext(Dispatchers.Main) { context.startActivity(intent) }
            } catch (_: Exception) {
                _state.value = UpdateState.Error("Проверка обновления не пройдена. Скачайте обновление заново.")
            } finally { installing.set(false) }
        }
    }

    // Private

    private fun fetchLatestRelease(proxy: java.net.Proxy? = null): JSONObject? {
        val json = fetchString(RELEASES_URL, proxy) ?: return null
        return try { JSONObject(json) } catch (_: Exception) { null }
    }

    private fun fetchLatestUnstableRelease(proxy: java.net.Proxy? = null): JSONObject? {
        val url = "https://api.github.com/repos/lesovoi53/tuna-vpn/releases"
        val json = fetchString(url, proxy) ?: return null
        
        return try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val release = jsonArray.getJSONObject(i)
                val tagName = release.getString("tag_name")
                if (tagName.contains("unstable", ignoreCase = true) || release.getBoolean("prerelease")) {
                    return release
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchString(url: String, proxy: java.net.Proxy? = null): String? {
        return try {
            val connection = UpdateSecurity.open(url, proxy ?: java.net.Proxy.NO_PROXY, metadata = true)
            try {
                val output = java.io.ByteArrayOutputStream()
                connection.inputStream.use { UpdateSecurity.copyBounded(it, output, UpdateSecurity.MAX_METADATA_BYTES,
                    connection.contentLengthLong, timeoutMs = 30_000) }
                output.toString(Charsets.UTF_8.name())
            } finally { connection.disconnect() }
        } catch (_: Exception) {
            null
        }
    }

    private fun findApkUrl(release: JSONObject): String? {
        val assets = release.getJSONArray("assets")
        val apkAssets = mutableListOf<JSONObject>()
        
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                apkAssets.add(asset)
            }
        }

        if (apkAssets.isEmpty()) return null

        // 1. Поиск под конкретную архитектуру устройства (в порядке приоритета ОС).
        // Матчим по суффиксу "_<abi>.apk" (наш собственный формат имени из build.gradle.kts),
        // а не по contains() - иначе abi="x86" ложно совпадает с файлом "..._x86_64.apk",
        // т.к. "x86_64" содержит "x86" как подстроку.
        for (abi in Build.SUPPORTED_ABIS) {
            val suffix = "_${abi.lowercase()}.apk"
            val match = apkAssets.find {
                it.getString("name").lowercase().endsWith(suffix)
            }
            if (match != null) return match.getString("browser_download_url")
        }

        // 2. Поиск универсальной сборки
        val universal = apkAssets.find { 
            it.getString("name").lowercase().contains("universal") 
        }
        if (universal != null) return universal.getString("browser_download_url")

        // 3. Фолбэк на любой найденный APK
        return apkAssets.firstOrNull()?.getString("browser_download_url")
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
        private const val RELEASES_URL =
            "https://api.github.com/repos/lesovoi53/tuna-vpn/releases/latest"

        private data class VerifiedApk(val file: File, val digest: String)
        private val artifactLock = Any()
        private var readyApk: VerifiedApk? = null
        private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val installing = java.util.concurrent.atomic.AtomicBoolean(false)

        private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        private val _downloadProgress = MutableStateFlow(0)
        private var latestApkUrl: String? = null
        private var lastCheckTime = 0L
        private var activeJob: Job? = null

        fun isNewer(remote: String, current: String, remoteBody: String = ""): Boolean {
            // "1.0.2-unstable" -> [1, 0, 2]
            fun String.toVersionList(): List<Int> {
                val basePart = this.split("-").first()
                return basePart.split(".")
                    .map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
            }

            val remoteIsUnstable = remote.contains("unstable", ignoreCase = true)
            val currentIsUnstable = current.contains("unstable", ignoreCase = true)

            // Если обе версии нестабильные, сравниваем хеши (если они есть)
            if (remoteIsUnstable && currentIsUnstable) {
                val rHash = remote.split("-").lastOrNull()?.take(7)
                val cHash = current.split("-").lastOrNull()?.take(7)
                
                // Если хеши в самих тегах/версиях совпали — это та же версия
                if (rHash != null && cHash != null && rHash == cHash && rHash.length >= 7) return false
                
                // Если в удаленном теге нет хеша (напр. просто v1.0-unstable), 
                // ищем хеш текущей версии в описании релиза
                if (cHash != null && cHash.length >= 7 && remoteBody.contains(cHash)) {
                    return false
                }
                
                // Если номера версий одинаковые, но хеши разные (или текущий хеш не найден в удаленном)
                // считаем, что на GitHub более свежая сборка (так как мы пересоздаем релиз)
                val r = remote.toVersionList()
                val c = current.toVersionList()
                if (r == c) return true
            }

            val r = remote.toVersionList()
            val c = current.toVersionList()

            // Сравниваем основные числа версии
            for (i in 0 until maxOf(r.size, c.size)) {
                val rv = r.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (rv != cv) return rv > cv
            }
            
            // Если номера версий идентичны (напр. 1.0 и 1.0-unstable)
            // Стабильная версия всегда считается новее нестабильной
            if (!remoteIsUnstable && currentIsUnstable) return true
            
            // Если на GitHub пришла версия с суффиксом unstable, а у нас стабильная того же номера - это не обнова
            // Во всех остальных случаях (версии равны) - false
            return false
        }
    }
}
