package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.*
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.ui.activities.kernel.MasterDnsConfigActivity
import java.io.File

object MasterDnsKernel : Kernel {
    override val variant = KernelVariant.MASTERDNS
    override val displayNameRes = R.string.kernel_masterdns
    override val configActivityClass = MasterDnsConfigActivity::class.java
    override val defaultProfileName = "MasterDnsVPN"
    override fun iconRes(cfg: KernelConfig, outlined: Boolean) = R.drawable.vpn_key_24px
    override fun description(context: Context, cfg: KernelConfig) = "MasterDnsVPN · " + (cfg as KernelConfig.MasterDns).config.domains.joinToString()
    override fun decodeUri(uri: String) = MasterDnsConfig.parse(uri)?.let { KernelConfig.MasterDns(it) }
    override fun displayNameFromUri(uri: String) = MasterDnsConfig.parse(uri)?.name
    fun writeConfig(directory: File, c: MasterDnsConfig, listen: String, user: String, password: String, auth: Boolean): File {
        directory.mkdirs()
        File(directory, "client_resolvers.txt").writeText(c.resolverList().joinToString("\n"))
        return File(directory, "client.toml").apply { writeText(c.toml(listen,user,password,auth)) }
    }
    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val file = writeConfig(File(ctx.filesDir,"masterdns"), (cfg.kernelConfig as KernelConfig.MasterDns).config,
            cfg.socksAddr, cfg.socksUser, cfg.socksPass, cfg.isSocksAuthEnabled)
        return listOf("${ctx.nativeLibraryDir}/libmasterdns.so", "-config", file.absolutePath)
    }
    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        if (line == "TUNA_MASTER_CONNECTING") {
            markConnecting(); ctx.updateNotification("MasterDnsVPN: проверка резолверов и подключение")
        }
        if (line == "TUNA_MASTER_READY") {
            state.startupEmitted = true
            CoreServiceState.setStatus(CoreStatus.Connected)
            ctx.updateNotification(ctx.getString(R.string.core_active))
        }
        if (line.startsWith("TUNA_MASTER_ERROR")) {
            state.startupFailed = true
            CoreServiceState.setStatus(CoreStatus.Error("MasterDnsVPN: ошибка конфигурации или запуска; проверьте журнал"))
            return true
        }
        return false
    }
}
