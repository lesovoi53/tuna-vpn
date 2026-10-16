package com.wireturn.app.data.kernel

import com.wireturn.app.domain.*
import com.wireturn.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DnsAndTitleTest {
    @Test fun decodesPanelTitleAndKeepsPlainNames() {
        val name = "ЭТО ТЕСТ"
        assertEquals(name, SubscriptionTitle.decode("base64:" + Base64.getEncoder().encodeToString(name.toByteArray())))
        assertEquals(name, SubscriptionTitle.decode("BASE64:" + Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray())))
        assertEquals("Мои серверы", SubscriptionTitle.decode(" Мои серверы "))
        assertNull(SubscriptionTitle.decode("base64:!!!"))
        assertNull(SubscriptionTitle.decode("base64:/w=="))
        assertNull(SubscriptionTitle.decode("base64:"))
        assertEquals("ab", SubscriptionTitle.decode("a\nb"))
    }
    @Test fun presetsAreValidAndQwdttUsesNativeSyntax() {
        DnsPresets.endpoints.forEach {
            SplitDnsSettings(it.resolver.copy(route = RouteAction.VPN), it.resolver.copy(route = RouteAction.DIRECT)).validate()
        }
        assertEquals("custom:77.88.8.8", QwdttDnsSetting.encode("77.88.8.8"))
        assertEquals("doh:https://1.1.1.1/dns-query", QwdttDnsSetting.encode("https://1.1.1.1/dns-query"))
        assertEquals("8.8.8.8,8.8.4.4", QwdttDnsSetting.addresses("google"))
    }
    @Test fun dnsRequiresCorrectQuestionIdAndAnAddressAnswer() {
        val q = DnsWire.query(1234)
        val response = q.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 } +
            byteArrayOf(0xc0.toByte(),12,0,1,0,1,0,0,0,30,0,4,93,184.toByte(),216.toByte(),34)
        DnsWire.validate(q, response)
        fun bad(bytes: ByteArray) = assertTrue(runCatching { DnsWire.validate(q, bytes) }.isFailure)
        bad(q)
        bad(response.copyOf().also { it[0] = 55 })
        bad(response.copyOf().also { it[3] = 0x82.toByte() })
        bad(response.copyOf().also { it[2] = 0x83.toByte() })
        bad(response.copyOf(response.size - 1))
    }
    @Test fun videoDefaultsAndExplicitAudioArePreserved() {
        val qw = "qwdtt://config?peer=192.0.2.1%3A56000&pass=test"
        assertEquals("video", QwdttConfig().obfsMode)
        assertEquals("video", QwdttConfig.parse(qw)!!.obfsMode)
        assertEquals("audio", QwdttConfig.parse("$qw&obfs=audio")!!.obfsMode)
    }
}
