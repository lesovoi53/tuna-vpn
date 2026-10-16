package com.wireturn.app.domain

import com.wireturn.app.data.KernelVariant
import com.wireturn.app.kernel.VpnCoreLog

internal fun isIsolatedProfileFatal(variant: KernelVariant, line: String): Boolean =
    VpnCoreLog.isFatal(line) ||
        (variant == KernelVariant.MASTERDNS && line.startsWith("TUNA_MASTER_ERROR"))

internal fun isIsolatedProfileReady(variant: KernelVariant, line: String): Boolean {
    val lower = line.lowercase()
    if (variant == KernelVariant.MASTERDNS) return line == "TUNA_MASTER_READY"
    return (variant == KernelVariant.WEBDAV && lower.contains("server connected")) ||
        lower.contains("[socks] listening") ||
        lower.contains("sing-box started") || lower.contains("xray started ->") ||
        lower.contains("mieru client socks5 server is running")
}
