package com.wireturn.app

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test

class ActiveConnectionLabelTest {
    @Test fun protocolIsTunnelNotInternalSocksHop() {
        assertEquals("Mieru", ActiveConnectionLabel.protocol(KernelConfig.Vpn(VpnConfig("mierus://user:pass@example.invalid?port=1234&protocol=TCP"))))
        assertEquals("VLESS", ActiveConnectionLabel.protocol(KernelConfig.Vpn(VpnConfig("vless://user@example.invalid:443?encryption=none"))))
        assertEquals("qWDTT", ActiveConnectionLabel.protocol(KernelConfig.Qwdtt()))
        assertEquals("WebDAV", ActiveConnectionLabel.protocol(KernelConfig.Webdav()))
    }
    @Test fun fullProfileNameSurvivesAndMissingNameUsesAppTitle() {
        val name = "Очень длинное имя фактически выбранного сервера"
        assertEquals(name, ActiveConnectionLabel.title(name, "TUNA VPN"))
        assertEquals("TUNA VPN", ActiveConnectionLabel.title(" ", "TUNA VPN"))
        assertEquals("TUNA VPN", ActiveConnectionLabel.title(null, "TUNA VPN"))
    }
}
