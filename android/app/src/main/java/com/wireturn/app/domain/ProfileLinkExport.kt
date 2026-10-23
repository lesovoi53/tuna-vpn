package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import java.net.URI

/** Protocol links contain the endpoint; only the TUNA export contains the complete profile. */
object ProfileLinkExport {
    enum class UnavailableReason { UNSUPPORTED, CUSTOM_SETTINGS, LOCAL_POOL, INVALID }
    sealed interface Original {
        data class Link(val uri: String) : Original
        data class Unavailable(val reason: UnavailableReason) : Original
    }

    fun original(profile: Profile): Original {
        fun unavailable(reason: UnavailableReason) = Original.Unavailable(reason)
        if (!profile.kernelVariant.isSupported) return unavailable(UnavailableReason.UNSUPPORTED)
        if (profile.xrayEnabled) return unavailable(UnavailableReason.CUSTOM_SETTINGS)
        return try {
            val uri = when (val kernel = profile.kernelConfig) {
                is KernelConfig.Vpn -> {
                    val config = kernel.config
                    if (config.isRawXray) return unavailable(UnavailableReason.UNSUPPORTED)
                    val options = config.options
                    if (options != null && (options.version != 1 ||
                        options.singBox?.size()?.let { it > 0 } == true ||
                        options.xray?.size()?.let { it > 0 } == true ||
                        options.allowExperimentalSnellTfo || options.fragmentation != null)) {
                        return unavailable(UnavailableReason.CUSTOM_SETTINGS)
                    }
                    // Do not regenerate VLESS queries: retain unknown transport fields, escaping,
                    // encryption values and server hints exactly as stored, including the fragment.
                    val source = config.uri
                    val scheme = URI(source).scheme?.lowercase()
                    if (scheme !in setOf("snell", "vless", "hy2", "hysteria2", "mierus",
                            "trojan", "ss", "tuic", "anytls", "naive+https", "wg", "wireguard",
                            "awg", "tt", "trusttunnel")) return unavailable(UnavailableReason.UNSUPPORTED)
                    require(source.length in 1..32768 && "://" in source && source.none(Char::isWhitespace))
                    source
                }
                is KernelConfig.MasterDns -> {
                    if (kernel.config.localPool != null) return unavailable(UnavailableReason.LOCAL_POOL)
                    kernel.config.toUri()
                }
                is KernelConfig.Webdav -> kernel.config.toUri(profile.name)
                is KernelConfig.Qwdtt -> {
                    require(kernel.config.isImportable())
                    kernel.config.toUri(profile.name)
                }
                else -> return unavailable(UnavailableReason.UNSUPPORTED)
            }
            Original.Link(uri)
        } catch (_: Exception) {
            // Never show exception text: parsers may include credentials or a private URI.
            unavailable(UnavailableReason.INVALID)
        }
    }
}
