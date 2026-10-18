package com.wireturn.app.domain

import com.android.apksig.ApkVerifier
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/** No trust is derived from release metadata or a checksum on the same server. */
object UpdateSecurity {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    const val MAX_METADATA_BYTES = 2L * 1024 * 1024
    const val DOWNLOAD_TIMEOUT_MS = 180_000L

    fun allowedUrl(value: String, metadata: Boolean = false): URI {
        val uri = URI(value)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == 443)) { "Недопустимый адрес обновления" }
        val allowed = if (metadata) uri.host == "api.github.com" &&
            uri.path in setOf("/repos/lesovoi53/tuna-vpn/releases", "/repos/lesovoi53/tuna-vpn/releases/latest")
        else (uri.host == "github.com" && uri.rawPath.startsWith("/lesovoi53/tuna-vpn/releases/download/")) ||
            uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
        require(allowed) { "Источник обновления не разрешён" }
        return uri
    }

    fun open(url: String, proxy: Proxy = Proxy.NO_PROXY, metadata: Boolean = false): HttpURLConnection {
        var next = allowedUrl(url, metadata)
        repeat(6) { redirect ->
            val connection = next.toURL().openConnection(proxy) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "TUNA-VPN-App")
            connection.setRequestProperty("Accept", if (metadata) "application/vnd.github+json" else "application/octet-stream")
            try {
                when (connection.responseCode) {
                    200 -> return connection
                    301, 302, 303, 307, 308 -> {
                        require(redirect < 5) { "Слишком много перенаправлений" }
                        next = allowedUrl(next.resolve(requireNotNull(connection.getHeaderField("Location"))).toString(), metadata)
                    }
                    else -> error("Сервер обновлений вернул HTTP ${connection.responseCode}")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("Слишком много перенаправлений")
    }

    fun copyBounded(input: InputStream, output: OutputStream, maximum: Long, expected: Long = -1,
        timeoutMs: Long = DOWNLOAD_TIMEOUT_MS, progress: (Long) -> Unit = {}): String {
        require(expected <= maximum) { "Файл обновления слишком большой" }
        val started = System.nanoTime()
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            require((System.nanoTime() - started) / 1_000_000 < timeoutMs) { "Истекло время загрузки" }
            val size = input.read(buffer)
            if (size < 0) break
            count += size
            require(count <= maximum) { "Файл обновления слишком большой" }
            progress(count)
            digest.update(buffer, 0, size)
            output.write(buffer, 0, size)
        }
        require(count > 0 && (expected < 0 || count == expected)) { "Неполная загрузка обновления" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySignature(file: File, sdk: Int, current: List<X509Certificate>) {
        require(current.isNotEmpty()) { "Не удалось определить текущую подпись" }
        val result = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(sdk).setMaxCheckedPlatformVersion(sdk).build().verify()
        require(result.isVerified) { "Криптографическая проверка APK не пройдена" }
        val candidate = result.signerCertificates
        if (candidate.toSet() == current.toSet()) return
        require(sdk >= 28 && current.size == 1 && candidate.size == 1) { "Подпись обновления не доверена" }
        val lineage = requireNotNull(result.signingCertificateLineage) { "Нет подтверждённой цепочки смены подписи" }
        val certificates = lineage.certificatesInLineage
        val installedIndex = certificates.indexOf(current.single())
        require(installedIndex >= 0 && installedIndex < certificates.lastIndex &&
            certificates.last() == candidate.single() && lineage.getSignerCapabilities(current.single()).hasInstalledData()) {
            "Обновление не продолжает текущую подпись"
        }
    }

    fun verifyMetadata(packageName: String, version: Long, expectedPackage: String, installedVersion: Long) {
        require(packageName == expectedPackage) { "APK принадлежит другому приложению" }
        require(version > installedVersion) { "Версия APK не новее установленной" }
    }

    fun verifyAbi(file: File, supported: List<String>) {
        val abis = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }
            .filter { it.startsWith("lib/") && it.endsWith(".so") }.map { it.split('/').getOrNull(1) }.toSet() }
        require(abis.isNotEmpty() && supported.any { it in abis }) { "APK не поддерживает архитектуру устройства" }
    }
}
