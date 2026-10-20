package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.CoreServiceState
import com.wireturn.app.CoreStatus
import com.wireturn.app.R
import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelVariant
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.ui.activities.kernel.VpnConfigActivity
import java.io.File

object SingBoxKernel : Kernel {
    override val variant = KernelVariant.VPN
    override val displayNameRes = R.string.kernel_vpn
    override val configActivityClass = VpnConfigActivity::class.java
    override val defaultProfileName = "VPN"
    override fun iconRes(cfg: KernelConfig, outlined: Boolean) = R.drawable.vpn_key_24px
    override fun description(context: Context, cfg: KernelConfig): String {
        val vpn = com.wireturn.app.data.kernel.VpnEnginePolicy.apply((cfg as KernelConfig.Vpn).config)
        val protocolName = if (vpn.isRawXray) "Xray JSON" else if (vpn.protocol == "naive") "NaiveProxy" else vpn.protocol.uppercase()
        return "$protocolName · ${vpn.engineLabel} · ${vpn.serverLabel}"
    }
    override fun decodeUri(uri: String) = VpnConfig.parse(uri)?.let { KernelConfig.Vpn(it) }
    override fun displayNameFromUri(uri: String) = VpnConfig.parse(uri)?.displayName
    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val profile = com.wireturn.app.data.kernel.VpnEnginePolicy.apply((cfg.kernelConfig as KernelConfig.Vpn).config)
        profile.validate()
        if (profile.usesXray) {
            val directory = File(ctx.filesDir, "xray-vpn").apply { mkdirs() }
            val file = File(directory, "config.json")
            val generated = XrayVpnConfig.generate(profile, cfg.socksAddr, cfg.socksUser, cfg.socksPass, !cfg.isSocksAuthEnabled, ctx.bootstrapDns)
            if (profile.isRawXray) {
                RawXrayConfig.validateAssets(profile, ctx.xrayAssetsDir)
                ctx.xrayAssetsDir?.let { generated.addProperty("assets_path", it.absolutePath) }
            }
            MemoryConfigs.write(file, generated.toString())
            return listOf("${ctx.nativeLibraryDir}/libxray.so", "-config", file.absolutePath)
        }
        if (profile.protocol == "mieru") {
            require(profile.isValid())
            val directory = File(ctx.filesDir, "mieru").apply { mkdirs() }
            val file = File(directory, "launch.json")
            MemoryConfigs.write(file, com.google.gson.Gson().toJson(mapOf("uri" to profile.uri, "listen" to cfg.socksAddr, "username" to cfg.socksUser, "password" to cfg.socksPass, "allowNoAuth" to !cfg.isSocksAuthEnabled)))
            return listOf("${ctx.nativeLibraryDir}/libmieru.so", file.absolutePath)
        }
        val directory = File(ctx.filesDir, "singbox").apply { mkdirs() }
        val file = File(directory, "config.json")
        MemoryConfigs.write(file, SingBoxConfig.generate(profile, cfg.socksAddr, cfg.socksUser, cfg.socksPass, !cfg.isSocksAuthEnabled, ctx.bootstrapDns).toString())
        return listOf("${ctx.nativeLibraryDir}/libsingbox.so", "run", "-c", file.absolutePath)
    }
    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        if (lower.contains("sing-box started") || lower.contains("xray started ->") || lower.contains("mieru client socks5 server is running")) {
            state.startupEmitted = true
            // Service readiness is not a remote-server connectivity or throughput measurement.
            if (canUpdateConnectingStatus()) {
                CoreServiceState.setStatus(CoreStatus.Connected)
                ctx.updateNotification(ctx.getString(R.string.core_active))
            }
        }
        if (VpnCoreLog.isFatal(line)) {
            state.startupFailed = true
            CoreServiceState.setStatus(CoreStatus.Error(ctx.getString(R.string.core_failed)))
            return true
        }
        return false
    }
}
