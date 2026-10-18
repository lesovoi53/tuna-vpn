package com.wireturn.app.domain

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal object UpdateApkValidator {
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File) {
        require(file.isFile && file.length() in 1..UpdateSecurity.MAX_APK_BYTES) { "Некорректный файл APK" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = requireNotNull(signatures).map {
            factory.generateCertificate(it.toByteArray().inputStream()) as X509Certificate
        }
        UpdateSecurity.verifySignature(file, Build.VERSION.SDK_INT, certificates)
        val candidate = requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)) { "Не удалось прочитать APK" }
        UpdateSecurity.verifyMetadata(candidate.packageName,
            if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong(), context.packageName,
            if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong())
        require(candidate.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true) { "APK требует более новый Android" }
        UpdateSecurity.verifyAbi(file, Build.SUPPORTED_ABIS.toList())
    }
}
