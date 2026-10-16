package com.wireturn.app.domain

import com.wireturn.app.data.ClientConfig
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.AwgUri
import com.wireturn.app.data.kernel.VpnConfig
import com.google.gson.JsonObject

class ActiveWgProbeConflict : IllegalStateException("Этот аккаунт WireGuard уже используется с другими настройками")

/** Shared by manual URL tests and group selection through IsolatedProfileTest. */
object WgProbeGuard {
    private fun vpn(config: KernelConfig): VpnConfig? = (config as? KernelConfig.Vpn)?.config
        ?.takeIf { it.protocol in setOf("awg", "wireguard") }

    private fun peers(config: JsonObject): Set<String> = config.getAsJsonArray("peers")
        .map { it.asJsonObject.get("public_key").asString }.toSet()

    suspend fun <T> run(requested: KernelConfig, active: ClientConfig?, ready: Boolean,
                        isolated: suspend () -> T, reuse: suspend (ClientConfig) -> T): T {
        val candidate = vpn(requested) ?: return isolated()
        val running = active?.let { vpn(it.kernelConfig) } ?: return isolated()
        val desired = candidate.outbound()
        val current = running.outbound()
        // A peer learns the latest authenticated UDP endpoint. A second instance using
        // this key would redirect replies away from the running VPN, even under another name.
        val sameAccount = desired.get("private_key") == current.get("private_key") &&
            peers(desired).any { it in peers(current) }
        if (!sameAccount) return isolated()
        if (!ready || desired != current || AwgUri.parse(candidate.uri).dns != AwgUri.parse(running.uri).dns) {
            throw ActiveWgProbeConflict()
        }
        return reuse(active)
    }
}
