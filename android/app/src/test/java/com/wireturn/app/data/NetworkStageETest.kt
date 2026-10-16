package com.wireturn.app.data

import androidx.datastore.preferences.core.*
import com.google.gson.*
import com.wireturn.app.data.kernel.*
import com.wireturn.app.domain.*
import com.wireturn.app.kernel.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress

class NetworkStageETest {
    private val webdav = KernelConfig.Webdav(WebdavConfig(webdav="https://storage.invalid",login="user",password="secret"))
    @Test fun activationPreservesEverySharedSettingAndConsent() {
        val p=mutablePreferencesOf(booleanPreferencesKey("routing_disabled_after_confirmation_v1") to true, stringPreferencesKey("unrelated") to "keep")
        val network=SharedNetworkSettings(router=NetworkRouter.SING_BOX_EXTENDED,dns=NetworkDnsSettings(servers="tcp://9.9.9.9",bootstrap="tcp://1.1.1.1",splitDnsJson="disabled"),routing=NetworkRoutingSettings(policyJson="{\"future\":17}",bypassLan=true))
        NetworkPreferences.write(p,network)
        val before=p.asMap().toMap()
        val member=Profile("member","WebDAV",webdav)
        val anchor=Profile("anchor","Chain",xrayEnabled=true,xrayProtocol=XrayConfiguration.VLESS,vlessConfig=VlessConfig(vlessLink="vless://u@server.invalid:443",isSocks5Chain=true))
        val activation=ProfileActivation.forGroup(member,anchor)
        assertEquals(member.kernelConfig,activation.kernelConfig)
        ProfileActivation.write(p,member.id,activation,KernelSnapshot(variant="WEBDAV",webdav=webdav.config),Gson())
        before.forEach { (key,value)->assertEquals(value,p.asMap()[key]) }
        assertEquals(network.copy(router = NetworkRouter.XRAY),NetworkPreferences.read(p)); assertEquals(member.id,p[AppPreferences.CURRENT_PROFILE_ID])
        assertTrue(p[AppPreferences.ACTIVE_XRAY_ENABLED]!!)
    }
    @Test fun priorityIgnoresPingButRequiresConfirmedRecovery() {
        val group=ConnectionGroup("g","reserve",ConnectionCategory.BYPASS,GroupTestType.URL_TEST,listOf("a","b"),selectionMode=GroupSelectionMode.PRIORITY,confirmations=2,holdSeconds=0)
        val choose=GroupSelection();fun results(now:Long)=listOf(GroupMeasurement("a",500,measuredAt=now),GroupMeasurement("b",10,measuredAt=now))
        assertEquals("a",choose.choose(group,results(1000),null,1000))
        assertEquals("b",choose.choose(group,results(2000),"b",2000))
        assertEquals("a",choose.choose(group,results(3000),"b",3000))
        assertNull(choose.choose(group,emptyList(),"a",4000))
    }
    @Test fun refreshPreservesLocalTuningWhileApplyingRemoteCredentials() {
        val original=Profile("id","WebDAV",webdav)
        val local=original.copy(kernelConfig=KernelConfig.Webdav(webdav.config.copy(pollMax="800ms")),subscriptionKernelBaseline=SubscriptionProfileMerge.snapshot(webdav))
        val remote=original.copy(kernelConfig=KernelConfig.Webdav(webdav.config.copy(password="new-secret",timeout="90s")))
        val merged=SubscriptionProfileMerge.kernel(local,remote) as KernelConfig.Webdav
        assertEquals("new-secret",merged.config.password);assertEquals("90s",merged.config.timeout);assertEquals("800ms",merged.config.pollMax)
    }
    @Test fun mergeKeepsUnknownNestedFieldsAndLocalDeletion() {
        fun j(s:String)=JsonParser.parseString(s)
        val merged=SubscriptionProfileMerge.mergeJson(j("""{"future":{"a":1},"deleted":2}"""),j("""{"future":{"a":3}}"""),j("""{"future":{"a":1,"b":4},"deleted":5}"""))!!.asJsonObject
        assertEquals(3,merged.getAsJsonObject("future")["a"].asInt);assertEquals(4,merged.getAsJsonObject("future")["b"].asInt);assertFalse(merged.has("deleted"))
    }
    @Test fun firstRefreshRetainsBothLocalAndNewNativeOptionFields() {
        fun j(text: String) = JsonParser.parseString(text).asJsonObject
        val local = VpnOptions(xray = j("""{"sockopt":{"tcpFastOpen":true},"future":{"local":1}}"""))
        val remote = VpnOptions(xray = j("""{"sockopt":{"tcpFastOpen":false,"tcpKeepAliveIdle":30},"future":{"remote":2}}"""))
        val result = SubscriptionProfileMerge.legacyOptions(local, remote)!!.xray!!
        assertTrue(result.getAsJsonObject("sockopt")["tcpFastOpen"].asBoolean)
        assertEquals(30,result.getAsJsonObject("sockopt")["tcpKeepAliveIdle"].asInt)
        assertEquals(2,result.getAsJsonObject("future").size())
    }
    @Test fun cyclesAndQuicCannotStartTcpOnlyChain() {
        val client=ClientConfig(kernelConfig=webdav,socksAddr="127.0.0.1:1080")
        val enabled=XrayConfig(enabled=true,protocol=XrayConfiguration.VLESS)
        for(link in listOf("vless://u@127.0.0.1:1080", "vless://u@server.invalid:443?type=quic", "hysteria2://u@server.invalid:443")) {
            assertTrue(runCatching { ChainCompatibility.validate(client,enabled,VlessConfig(vlessLink=link,isSocks5Chain=true),XraySettings(socksBindAddress="127.0.0.1:1089")) }.isFailure)
        }
        ChainCompatibility.validate(client,enabled,VlessConfig(vlessLink="vless://u@server.invalid:443?type=tcp",isSocks5Chain=true),XraySettings())
    }
}
