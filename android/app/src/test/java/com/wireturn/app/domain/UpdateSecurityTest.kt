package com.wireturn.app.domain

import com.android.apksig.ApkSigner
import com.android.apksig.SigningCertificateLineage
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateSecurityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun rejectsDowngradeWrongPackageAndNonGithubRedirectTargets() {
        UpdateSecurity.verifyMetadata("com.example.app", 2, "com.example.app", 1)
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.verifyMetadata("other", 2, "com.example.app", 1) }
        for (version in listOf(0L, 1L)) assertThrows(IllegalArgumentException::class.java) {
            UpdateSecurity.verifyMetadata("com.example.app", version, "com.example.app", 1)
        }
        for (url in listOf("http://github.com/lesovoi53/tuna-vpn/releases/download/v1/app.apk",
            "https://github.com.evil.invalid/a", "https://github.com@evil.invalid/a", "https://localhost/a",
            "https://github.com/other/repo/releases/download/a.apk", "https://release-assets.githubusercontent.com:8443/a"))
            assertThrows(url, IllegalArgumentException::class.java) { UpdateSecurity.allowedUrl(url) }
        UpdateSecurity.allowedUrl("https://github.com/lesovoi53/tuna-vpn/releases/download/v2/app.apk")
        UpdateSecurity.allowedUrl("https://release-assets.githubusercontent.com/github-production-release-asset/a?token=synthetic")
        UpdateSecurity.allowedUrl("https://api.github.com/repos/lesovoi53/tuna-vpn/releases/latest", true)
    }

    @Test fun boundedStreamRejectsTruncationOversizeAndExpiredDeadline() {
        val bytes = "fixture".toByteArray()
        val output = ByteArrayOutputStream()
        val digest = UpdateSecurity.copyBounded(bytes.inputStream(), output, 20, bytes.size.toLong())
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(64, digest.length)
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.copyBounded(bytes.inputStream(), ByteArrayOutputStream(), 3) }
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.copyBounded(bytes.inputStream(), ByteArrayOutputStream(), 20, 10) }
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.copyBounded(bytes.inputStream(), ByteArrayOutputStream(), 20, timeoutMs = 0) }
    }

    @Test fun supportedAbiRequiredBeforeInstaller() {
        val apk = unsigned()
        UpdateSecurity.verifyAbi(apk, listOf("arm64-v8a"))
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.verifyAbi(apk, listOf("x86_64")) }
    }

    @Test fun cryptographicVerificationRejectsForeignSignerAndTamperedApk() {
        val first = key("first")
        val second = key("second")
        val signed = sign(unsigned(), listOf(first))
        UpdateSecurity.verifySignature(signed, 33, listOf(first.cert))
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.verifySignature(signed, 33, listOf(second.cert)) }
        val raw = signed.readBytes()
        val marker = "synthetic-native-library".toByteArray()
        val offset = raw.indices.first { i -> i + marker.size <= raw.size && marker.indices.all { j -> raw[i + j] == marker[j] } }
        raw[offset] = (raw[offset].toInt() xor 1).toByte()
        signed.writeBytes(raw)
        assertThrows(Exception::class.java) { UpdateSecurity.verifySignature(signed, 33, listOf(first.cert)) }
    }

    @Test fun rotatedSignerContinuesCurrentIdentityButOldSignerCannotReturn() {
        val old = key("old")
        val fresh = key("fresh")
        val lineage = SigningCertificateLineage.Builder(old.lineage(), fresh.lineage()).build()
        val rotated = sign(unsigned(), listOf(old, fresh), lineage)
        // Universal APK uses the original signer on Android 8–12 and the rotated signer on 13+.
        for (sdk in listOf(26, 28, 32)) UpdateSecurity.verifySignature(rotated, sdk, listOf(old.cert))
        UpdateSecurity.verifySignature(rotated, 33, listOf(old.cert))
        UpdateSecurity.verifySignature(rotated, 36, listOf(fresh.cert))
        val oldOnly = sign(unsigned(), listOf(old))
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.verifySignature(oldOnly, 36, listOf(fresh.cert)) }
        val newOnly = sign(unsigned(), listOf(fresh))
        assertThrows(IllegalArgumentException::class.java) { UpdateSecurity.verifySignature(newOnly, 36, listOf(old.cert)) }
    }

    private data class TestKey(val name: String, val privateKey: PrivateKey, val cert: X509Certificate) {
        fun signer() = ApkSigner.SignerConfig.Builder(name, privateKey, listOf(cert)).build()
        fun lineage() = SigningCertificateLineage.SignerConfig.Builder(privateKey, cert).build()
    }

    // Ephemeral synthetic keys generated for this test only; no application signing key is accessed.
    private fun key(name: String): TestKey {
        val file = File(temp.root, "$name.p12")
        val executable = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val process = ProcessBuilder(executable.path, "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "30", "-dname", "CN=Synthetic updater test", "-keystore", file.path, "-storetype", "PKCS12",
            "-storepass", "synthetic-test-only", "-keypass", "synthetic-test-only", "-noprompt").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        val password = "synthetic-test-only".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
        return TestKey(name, store.getKey(name, password) as PrivateKey, store.getCertificate(name) as X509Certificate)
    }

    private fun unsigned(): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            val manifest = requireNotNull(javaClass.getResourceAsStream("/update/AndroidManifest.axml")).use { it.readBytes() }
            for ((name, bytes) in listOf("AndroidManifest.xml" to manifest, "lib/arm64-v8a/libfixture.so" to "synthetic-native-library".toByteArray())) {
                val entry = ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size;
                    crc = java.util.zip.CRC32().apply { update(bytes) }.value }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
        }
    }

    private fun sign(input: File, keys: List<TestKey>, lineage: SigningCertificateLineage? = null): File = temp.newFile().also { output ->
        val builder = ApkSigner.Builder(keys.map { it.signer() }).setInputApk(input).setOutputApk(output)
            .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).setV4SigningEnabled(false)
        if (lineage != null) builder.setSigningCertificateLineage(lineage).setMinSdkVersionForRotation(33)
        builder.build().sign()
    }
}
