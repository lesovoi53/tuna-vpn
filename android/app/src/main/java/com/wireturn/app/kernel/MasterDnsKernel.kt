package com.wireturn.app.kernel

import android.content.Context
import com.wireturn.app.*
import com.wireturn.app.data.*
import com.wireturn.app.data.kernel.MasterDnsConfig
import com.wireturn.app.ui.activities.kernel.MasterDnsConfigActivity
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import com.google.gson.Gson

object MasterDnsKernel : Kernel {
    override val variant = KernelVariant.MASTERDNS
    override val displayNameRes = R.string.kernel_masterdns
    override val configActivityClass = MasterDnsConfigActivity::class.java
    override val defaultProfileName = "MasterDnsVPN"
    override fun iconRes(cfg: KernelConfig, outlined: Boolean) = R.drawable.vpn_key_24px
    override fun description(context: Context, cfg: KernelConfig): String {
        val c = (cfg as KernelConfig.MasterDns).config
        return c.localPool?.let { "MasterDnsVPN · серверов: ${it.memberIds.size}" }
            ?: ("MasterDnsVPN · " + c.domains.joinToString())
    }
    override fun decodeUri(uri: String) = MasterDnsConfig.parse(uri)?.let { KernelConfig.MasterDns(it) }
    override fun displayNameFromUri(uri: String) = MasterDnsConfig.parse(uri)?.name
    fun writeConfig(directory: File, c: MasterDnsConfig, listen: String, user: String, password: String, auth: Boolean): File {
        directory.mkdirs()
        MemoryConfigs.write(File(directory, "client_resolvers.txt"), c.resolverList().joinToString("\n"))
        return MemoryConfigs.write(File(directory, "client.toml"), c.toml(listen,user,password,auth))
    }
    override fun buildCommand(ctx: KernelCommandContext, cfg: ClientConfig): List<String> {
        val config = (cfg.kernelConfig as KernelConfig.MasterDns).config
        if (config.localPool != null) {
            val plan = requireNotNull(cfg.masterDnsPoolPlan) { "Не проверен состав пула Master DNS" }
            require(plan.members.map { it.id } == config.localPool.memberIds &&
                plan.strategy == config.localPool.strategy) { "Состав пула Master DNS изменился" }
            require(cfg.isSocksAuthEnabled && cfg.socksUser.isNotBlank() && cfg.socksPass.isNotBlank()) {
                "Пул Master DNS требует аутентификацию SOCKS"
            }
            // Reserve all child ports together so siblings cannot receive the same free port.
            val sockets = mutableListOf<ServerSocket>()
            try {
                val members = plan.members.mapIndexed { index, member ->
                    val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                    sockets += socket
                    val listen = "127.0.0.1:${socket.localPort}"
                    require(listen != cfg.socksAddr) { "Порт пула Master DNS занят" }
                    val user = UUID.randomUUID().toString()
                    val password = UUID.randomUUID().toString()
                    val file = writeConfig(File(ctx.filesDir, "masterdns/pool/$index"),
                        member.config.copy(localPool = null), listen, user, password, true)
                    linkedMapOf("id" to "member-${index + 1}", "configPath" to file.absolutePath,
                        "resolversPath" to File(file.parentFile, "client_resolvers.txt").absolutePath,
                        "listen" to listen, "username" to user, "password" to password)
                }
                val manifest = linkedMapOf<String, Any>("version" to 1, "listen" to cfg.socksAddr,
                    "username" to cfg.socksUser, "password" to cfg.socksPass,
                    "strategy" to plan.strategy, "members" to members)
                val file = MemoryConfigs.write(File(ctx.filesDir, "masterdns/pool.json"), Gson().toJson(manifest))
                return listOf("${ctx.nativeLibraryDir}/libmasterdns.so", "-pool", file.absolutePath)
            } finally {
                sockets.forEach { it.close() }
            }
        }
        val file = writeConfig(File(ctx.filesDir,"masterdns"), (cfg.kernelConfig as KernelConfig.MasterDns).config,
            cfg.socksAddr, cfg.socksUser, cfg.socksPass, cfg.isSocksAuthEnabled)
        return listOf("${ctx.nativeLibraryDir}/libmasterdns.so", "-config", file.absolutePath, "-resolvers", File(file.parentFile, "client_resolvers.txt").absolutePath)
    }
    override suspend fun parseLogLine(line: String, lower: String, state: BinaryOutputState, ctx: KernelLogContext, cfg: ClientConfig): Boolean {
        if (line.startsWith("TUNA_MASTER_POOL ") && line.length <= 2048 && cfg.masterDnsPoolPlan != null) {
            runCatching {
                val status = com.google.gson.JsonParser.parseString(line.substringAfter(' ')).asJsonObject
                val ready = status["ready"].asInt
                val total = status["total"].asInt
                val active = status["active"].asInt
                require(total == cfg.masterDnsPoolPlan.members.size && ready in 0..total && active in 0..256)
                CoreServiceState.setStatusText("DNS-туннель: доступно $ready из $total · соединений: $active")
            }
        }
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
