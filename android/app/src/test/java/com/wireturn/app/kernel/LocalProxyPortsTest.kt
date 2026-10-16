package com.wireturn.app.kernel

import com.wireturn.app.data.*
import org.junit.Assert.*
import org.junit.Test

class LocalProxyPortsTest {
    @Test fun qwdttAndXrayNeverShareTheUpstreamPort() {
        val client = ClientConfig(kernelConfig = KernelConfig.Qwdtt(), socksAddr = "127.0.0.1:1080")
        val initial = XraySettings(socksBindAddress = "127.0.0.1:1080", httpBindAddress = "127.0.0.1:1080")
        val result = LocalProxyPorts.resolve(client, initial)
        assertEquals("127.0.0.1:1089", result.socksBindAddress)
        assertEquals("127.0.0.1:1090", result.httpBindAddress)
        assertEquals(result, LocalProxyPorts.resolve(client, result))
        assertEquals("127.0.0.1:1080", client.socksAddr)
    }
    @Test fun preservesDistinctListenerAddresses() {
        val initial = XraySettings(socksBindAddress = "127.0.0.1:1080")
        assertEquals(initial, LocalProxyPorts.resolve(ClientConfig(socksAddr = "127.0.0.1:2081"), initial))
    }
}
