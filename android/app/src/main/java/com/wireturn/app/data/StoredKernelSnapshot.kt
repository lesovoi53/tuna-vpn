package com.wireturn.app.data

import com.google.gson.Gson
import com.wireturn.app.data.kernel.*

/** Unknown selections stay empty; they must never reuse fields of another stored kernel. */
internal fun decodeStoredKernelSnapshot(json: String?, gson: Gson): KernelConfig {
    if (json == null) return KernelConfig.Vpn()
    return runCatching {
        val snap = gson.fromJson(json, KernelSnapshot::class.java) ?: return KernelConfig.Vpn()
        when (KernelVariant.valueOf(snap.variant)) {
            KernelVariant.MASTERDNS -> KernelConfig.MasterDns(snap.masterdns ?: MasterDnsConfig())
            KernelVariant.WEBDAV -> KernelConfig.Webdav(snap.webdav ?: WebdavConfig())
            KernelVariant.QWDTT -> KernelConfig.Qwdtt(snap.qwdtt ?: QwdttConfig())
            KernelVariant.VPN -> KernelConfig.Vpn(snap.vpn ?: VpnConfig())
            else -> KernelConfig.Vpn()
        }
    }.getOrElse { KernelConfig.Vpn() }
}
