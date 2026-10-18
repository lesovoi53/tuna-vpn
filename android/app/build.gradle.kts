import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val prebuiltNative = providers.gradleProperty("prebuiltNative").orNull == "true"
val targetAbis = providers.gradleProperty("targetAbis").orElse("arm64-v8a,x86_64,armeabi-v7a,x86").get().split(",")

android {
    namespace = "com.wireturn.app"
    testBuildType = providers.gradleProperty("qaBuildType").orElse("debug").get()
    compileSdk = project.property("project.compileSdk").toString().toInt()
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.tunaturn.android"
        testInstrumentationRunner = providers.gradleProperty("qaRunner").orElse("com.wireturn.app.FingerprintMatrixRunner").get()
        buildConfigField("boolean", "PRIVATE_DISTRIBUTION", "true")
        minSdk = project.property("project.minSdk").toString().toInt()
        targetSdk = project.property("project.targetSdk").toString().toInt()
        
        val date = Date()
        val sdf = SimpleDateFormat("yyMMddHH", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val formattedDate = sdf.format(date)
        versionCode = providers.gradleProperty("project.versionCode").orElse(formattedDate).get().toInt().also {
            require(it in 1..2_100_000_000) { "project.versionCode must be a positive Android version code" }
        }
        versionName = project.property("project.versionName").toString()
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*targetAbis.toTypedArray())
            isUniversalApk = targetAbis.size > 1
        }
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libhevsocks5.so"
            keepDebugSymbols += "**/libturnable.so"
            keepDebugSymbols += "**/libolcrtc.so"
            keepDebugSymbols += "**/libxray.so"
            keepDebugSymbols += "**/libwebdav.so"
            keepDebugSymbols += "**/libfreeturn.so"
            keepDebugSymbols += "**/libqwdtt.so"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appName"] = project.property("project.appName").toString()
            // Exercise the release optimizer on the existing private package,
            // preserving its data and signature for on-device acceptance.
            if (providers.gradleProperty("optimizedCandidate").orNull == "true") {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Private release acceptance can update the existing installation while
            // still excluding src/debug and disabling run-as/debugging. Public release
            // signing remains a separate owner-supplied configuration.
            if (providers.gradleProperty("privateRelease").orNull == "true") {
                applicationIdSuffix = ".debug"
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["appName"] = project.property("project.appName").toString()
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

@Suppress("UnstableApiUsage")
androidComponents {
    onVariants { variant ->
        val branch = providers.exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
        }.standardOutput.asText.get().trim()
        
        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()

        val isUnstable = branch == "unstable" || System.getenv("GITHUB_REF_NAME") == "unstable"
        val baseVersion = android.defaultConfig.versionName ?: "0.0"
        val vName = if (isUnstable) "$baseVersion-unstable-$gitHash" else baseVersion
        
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier ?: "universal"
            // Для unstable убираем суффикс из имени файла, чтобы GitHub перезаписывал ассеты
            val fileSuffix = "" 
            
            // Устанавливаем версию (с хешем для отображения в приложении)
            output.versionName.set(vName)
            // Имя файла (без хеша для стабильной ссылки в релизах)
            val fileNameBase = if (isUnstable) "$baseVersion-unstable" else vName
            output.outputFileName.set("TUNA_VPN_v${fileNameBase}${fileSuffix}_${abi}.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("com.android.tools.build:apksig:9.4.0")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation("com.google.re2j:re2j:1.8")
    implementation(libs.zxing.core)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.guava)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

val verifyPrebuiltNative = tasks.register("verifyPrebuiltNative") {
    doLast {
        val manifest = groovy.json.JsonSlurper().parse(file("src/main/assets/native/singbox.json")) as Map<*, *>
        val lock = (groovy.json.JsonSlurper().parse(rootProject.file("native-lock.json")) as Map<*, *>)["SingBoxExtended"] as Map<*, *>
        check(manifest["name"] == "sing-box-extended" && manifest["commit"] == lock["commit"] && manifest["version"] == lock["version"] && manifest["buildTags"] == lock["buildTags"]) { "sing-box-extended provenance mismatch: rebuild native/singbox/build.ps1" }
        check(manifest["utls"] == lock["utls"]) { "uTLS dependency differs from native lock" }
        check(manifest["sourceOverlay"] == lock["sourceOverlay"]) { "Native source overlay differs from lock" }
        check(manifest["dependencyOverlay"] == lock["dependencyOverlay"]) { "Native dependency overlay differs from lock" }
        (manifest["dependencyFiles"] as Map<*, *>).forEach { (path, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(rootProject.file(path as String).readBytes()).joinToString("") { "%02x".format(it) }
            check(actual.equals(expected as String, true)) { "Native dependency input changed: $path" }
        }
        targetAbis.forEach { abi ->
            check(manifest["abi"] == abi) { "Missing sing-box-extended provenance for $abi" }
            val digest = MessageDigest.getInstance("SHA-256")
            file("src/main/jniLibs/$abi/libsingbox.so").inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer,0,count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) }.equals(manifest["sha256"] as String,true)) { "Packaged sing-box binary differs from provenance" }
        }
        val capabilities = groovy.json.JsonSlurper().parse(file("src/main/assets/native/capabilities.json")) as Map<*, *>
        val xrayManifest = capabilities["xray"] as Map<*, *>
        val xrayBinary = file("src/main/jniLibs/arm64-v8a/libxray.so")
        val xrayHash = MessageDigest.getInstance("SHA-256").digest(xrayBinary.readBytes()).joinToString("") { "%02x".format(it) }
        check(xrayHash.equals(xrayManifest["sha256"] as String, true)) { "Xray binary differs from capability manifest" }
        val xrayProvenance = groovy.json.JsonSlurper().parse(rootProject.file("native/routing/provenance-current.json")) as Map<*, *>
        check(xrayHash == (xrayProvenance["binaries"] as Map<*, *>)["libxray-network.so"]) { "Xray binary differs from build provenance" }
        (xrayProvenance["sources"] as Map<*, *>).forEach { (path, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(rootProject.file(path as String).readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Xray native source changed: rebuild native/routing/build.ps1 ($path)" }
        }
        (capabilities["adapterSources"] as Map<*, *>).forEach { (path, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(rootProject.file(path as String).readBytes()).joinToString("") { "%02x".format(it) }
            check(actual.equals(expected as String, true)) { "Core adapter changed: regenerate tools/core_manifest.py after native build ($path)" }
        }
        val webdav = groovy.json.JsonSlurper().parse(rootProject.file("native/webdav/provenance.json")) as Map<*, *>
        val webdavLock = (groovy.json.JsonSlurper().parse(rootProject.file("native-lock.json")) as Map<*, *>)["WebDAV"] as Map<*, *>
        check(webdav["commit"] == webdavLock["commit"]) { "WebDAV pin mismatch" }
        (webdav["sources"] as Map<*, *>).forEach { (path, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(rootProject.file(path as String).readBytes()).joinToString("") { "%02x".format(it) }
            check(actual.equals(expected as String, true)) { "WebDAV source changed: rebuild native/webdav/build.ps1 ($path)" }
        }
        val webdavHash = MessageDigest.getInstance("SHA-256").digest(file("src/main/jniLibs/arm64-v8a/libwebdav.so").readBytes()).joinToString("") { "%02x".format(it) }
        check(webdavHash == (webdav["binaries"] as Map<*, *>)["libwebdav.so"]) { "WebDAV binary differs from provenance" }
        val names = listOf("hevsocks5", "xray", "qwdtt", "singbox", "mieru", "masterdns", "webdav")
        fun hash(path: String) = MessageDigest.getInstance("SHA-256").digest(rootProject.file(path).readBytes()).joinToString("") { "%02x".format(it) }
        fun provenance(path: String) = groovy.json.JsonSlurper().parse(rootProject.file(path)) as Map<*, *>
        val mieru = provenance("native/mieru/provenance.json")
        check(hash("app/src/main/jniLibs/arm64-v8a/libmieru.so") == mieru["binarySha256"])
        (mieru["sources"] as Map<*, *>).forEach { (path, expected) -> check(hash(path as String) == expected) { "Mieru source changed: $path" } }
        val prebuilt = provenance("native/prebuilt-provenance.json")
        check(prebuilt["source"] == provenance("native-lock.json")["WireTurnApk"])
        (prebuilt["binaries"] as Map<*, *>).forEach { (name, expected) -> check(hash("app/src/main/jniLibs/arm64-v8a/$name") == expected) { "Prebuilt changed: $name" } }
        val master = provenance("native/masterdns/provenance.json")
        check(hash("app/src/main/jniLibs/arm64-v8a/libmasterdns.so").equals(master["binarySha256"] as String, true))
        check(hash("native/masterdns/main.go").equals(master["adapterSha256"] as String, true))
        check(hash("native/masterdns/prepare.py").equals(master["overlayScriptSha256"] as String, true))
        targetAbis.forEach { abi -> names.forEach { name ->
            check(file("src/main/jniLibs/$abi/lib$name.so").isFile) { "Missing native library: $abi/lib$name.so" }
        } }
    }
}
tasks.named("preBuild") {
    dependsOn(verifyPrebuiltNative)
}
