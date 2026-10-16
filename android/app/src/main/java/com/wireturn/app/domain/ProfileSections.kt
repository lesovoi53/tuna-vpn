package com.wireturn.app.domain

import com.wireturn.app.data.*

enum class ProfileSection(val title: String) {
    FAVORITES(""), VLESS("VLESS"), VLESS_CF("VLESS CF"), HYSTERIA2("Hysteria2"),
    SNELL("Snell"), MIERU("Mieru"), TROJAN("Trojan"), SHADOWSOCKS("Shadowsocks"),
    TUIC("TUIC"), ANYTLS("AnyTLS"), NAIVE("NaiveProxy"), WIREGUARD("WireGuard"),
    AWG("AmneziaWG"), TRUSTTUNNEL("TrustTunnel"), CDN("CDN"), QWDTT("qWDTT"),
    XRAY_JSON("Xray JSON"), DNS(""), WEBDAV("WebDAV"), OTHER("")
}

/** Presentation only: no profile copies, connection edits or subscription mutations. */
object ProfileSections {
    fun isPinnedBalancer(profile: Profile): Boolean = (profile.kernelConfig as? KernelConfig.Vpn)?.config?.isXrayBalancer == true

    /** A global block, independent of source/category/search filters, with stable source order. */
    fun pinnedBalancers(profiles: List<Profile>): List<Profile> = profiles.filter(::isPinnedBalancer).distinctBy { it.id }

    fun pinnedBalancerItemCount(profiles: List<Profile>): Int = pinnedBalancers(profiles).size.let { if (it == 0) 0 else it + 1 }

    /** Header is item 0, pinned title is item 1, then each selectable profile. */
    fun pinnedBalancerLazyIndex(profiles: List<Profile>, id: String): Int =
        pinnedBalancers(profiles).indexOfFirst { it.id == id }.let { if (it < 0) -1 else it + 2 }

    fun section(profile: Profile): ProfileSection = when (val kernel = profile.kernelConfig) {
        is KernelConfig.Vpn -> if (profile.listCategory == ConnectionCategory.BYPASS) ProfileSection.CDN else when (kernel.config.protocol) {
            "vless" -> if (CloudflareProfile.tagged(profile.name)) ProfileSection.VLESS_CF else ProfileSection.VLESS
            "xray-json" -> ProfileSection.XRAY_JSON
            "hysteria2" -> ProfileSection.HYSTERIA2
            "snell" -> ProfileSection.SNELL
            "mieru" -> ProfileSection.MIERU
            "trojan" -> ProfileSection.TROJAN
            "shadowsocks" -> ProfileSection.SHADOWSOCKS
            "tuic" -> ProfileSection.TUIC
            "anytls" -> ProfileSection.ANYTLS
            "naive" -> ProfileSection.NAIVE
            "wireguard" -> ProfileSection.WIREGUARD
            "awg" -> ProfileSection.AWG
            "trusttunnel" -> ProfileSection.TRUSTTUNNEL
            else -> ProfileSection.OTHER
        }
        is KernelConfig.Qwdtt -> ProfileSection.QWDTT
        is KernelConfig.MasterDns -> ProfileSection.DNS
        is KernelConfig.Webdav -> ProfileSection.WEBDAV
        else -> ProfileSection.OTHER
    }

    fun groups(profiles: List<Profile>, category: ConnectionCategory, subscriptions: Boolean,
        favorites: Set<String>, order: ProfileSortOrder, search: String = ""): List<Pair<ProfileSection, List<Profile>>> {
        val rows = ProfileListOrder.sorted(profiles.filter {
            !isPinnedBalancer(it) && it.listCategory == category && (it.subscriptionId != null) == subscriptions &&
                (search.isBlank() || it.name.contains(search.trim(), true) || section(it).title.contains(search.trim(), true))
        }, order, favorites)
        val groups = rows.groupBy { if (ProfileListOrder.isFavorite(it, favorites)) ProfileSection.FAVORITES else section(it) }
        return ProfileSection.entries.mapNotNull { section ->
            val members = groups[section].orEmpty()
            if (section == ProfileSection.FAVORITES || members.isNotEmpty()) section to members else null
        }
    }
}
