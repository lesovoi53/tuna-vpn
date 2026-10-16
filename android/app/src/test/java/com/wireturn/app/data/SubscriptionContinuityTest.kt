package com.wireturn.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.domain.SubscriptionIdentity
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SubscriptionContinuityTest {
    private val uri = "vless://00000000-0000-4000-8000-000000000001@vpn.invalid:443?security=tls&fp=firefox&sid=aa#Old"
    private fun source(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    private fun incoming(value: String, raw: Boolean = true) = Profile(source(value), "Server", KernelConfig.Vpn(VpnConfig(value)), subscriptionUriIdentity = raw)
    private fun old(value: String = uri) = incoming(value).copy(id="local",subscriptionId="sub",subscriptionSourceId=source(value))

    @Test fun textRotationAndRenameKeepLocalIdentityButNotByNameAlone() {
        val next = incoming(uri.replace("sid=aa#Old","sid=bb#New"))
        assertEquals("local",SubscriptionIdentity.match(next,listOf(next),listOf(old()),emptySet())?.id)
        assertNull(SubscriptionIdentity.match(next,listOf(next),listOf(old().copy(kernelConfig=KernelConfig.Vpn(VpnConfig(uri.replace("vpn.invalid","other.invalid"))))),emptySet()))
        assertEquals("local",SubscriptionIdentity.match(next,listOf(next),listOf(old().copy(subscriptionUriIdentity=false)),emptySet())?.id)
        val otherAccount = incoming(uri.replace("000000000001", "000000000002"))
        assertNull(SubscriptionIdentity.match(otherAccount,listOf(otherAccount),listOf(old()),emptySet()))
    }

    @Test fun explicitServerIdsAndAmbiguousEndpointsNeverGetFuzzyMatched() {
        val next = incoming(uri.replace("sid=aa","sid=bb"))
        assertNull(SubscriptionIdentity.match(next.copy(subscriptionUriIdentity=false),listOf(next),listOf(old()),emptySet()))
        assertNull(SubscriptionIdentity.match(next,listOf(next),listOf(old(),old().copy(id="other-local",subscriptionSourceId="other-source")),emptySet()))
        assertNull(SubscriptionIdentity.match(next,listOf(next,next.copy(id="another")),listOf(old()),emptySet()))
        assertNull(SubscriptionIdentity.match(next,listOf(next),listOf(old()),setOf("local")))
        assertNull(SubscriptionIdentity.match(next,listOf(next),listOf(old().copy(subscriptionUriIdentity=false,subscriptionSourceId="server-managed-id")),emptySet()))
        val exact = next.copy(id="local-exact",subscriptionSourceId=next.id)
        assertEquals(exact,SubscriptionIdentity.match(next,listOf(next),listOf(old(),exact),emptySet()))
    }

    @Test fun plainSubscriptionCommitRepairsLocalGroupsWithoutGuessingRouting() {
        val gson=Gson(); val profiles=listOf(old(),old().copy(id="second"))
        val sub=Subscription("sub","Subscription","https://example.invalid/sub")
        val group=ConnectionGroup("local-group","Keep name",ConnectionCategory.VPN,GroupTestType.URL_TEST,
            listOf("gone","local","second"),routingProfileId="gone",automatic=true,intervalSeconds=600)
        val key=stringPreferencesKey("connection_groups_v1")
        val prefs=mutablePreferencesOf(AppPreferences.PROFILES_JSON to gson.toJson(profiles),
            AppPreferences.SUBSCRIPTIONS_JSON to gson.toJson(listOf(sub)),key to gson.toJson(listOf(group)))
        val snapshot=SubscriptionImportSnapshot(profiles,listOf(sub),"local",prefs[AppPreferences.PROFILES_JSON]!!,prefs[AppPreferences.SUBSCRIPTIONS_JSON]!!)
        SubscriptionImportCommit.write(prefs,snapshot,profiles,listOf(sub),null,gson)
        val saved=gson.fromJson(prefs[key],Array<ConnectionGroup>::class.java).single()
        assertEquals(listOf("local","second"),saved.memberIds)
        assertEquals("",saved.routingProfileId)
        assertFalse(saved.automatic)
        assertEquals(group.name,saved.name); assertEquals(600,saved.intervalSeconds)
        assertTrue(runCatching { saved.validate(profiles) }.isFailure)
    }
}
