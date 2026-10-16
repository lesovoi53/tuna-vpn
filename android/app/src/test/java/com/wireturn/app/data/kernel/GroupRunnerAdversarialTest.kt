package com.wireturn.app.data.kernel

import com.wireturn.app.CoreStatus
import com.wireturn.app.data.*
import com.wireturn.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class GroupRunnerAdversarialTest {
    private class Runtime : GroupRuntime {
        override val status=MutableStateFlow<CoreStatus>(CoreStatus.Connected)
        var items=listOf("a", "b").map { id ->
            Profile(id, id.uppercase(), KernelConfig.Vpn(VpnConfig("vless://00000000-0000-0000-0000-000000000001@$id.example:443?security=tls")))
        }
        val group=ConnectionGroup("g","G",ConnectionCategory.VPN,GroupTestType.URL_TEST,listOf("a","b"))
        val activated=mutableListOf<String>()
        var networkId="wifi"
        var failActivation=false
        var failCandidate: String? = null
        var onCandidateFailure: () -> Unit = {}
        val activationAttempts=mutableListOf<String>()
        override suspend fun profiles()=items
        var savedGroups=listOf(group)
        override suspend fun groups()=savedGroups
        override suspend fun currentId()="a"
        override suspend fun activeGroupId():String?=null
        override suspend fun selectGroup(id:String) {}
        override fun network(allowMobile:Boolean)=setOf(networkId)
        override suspend fun activate(profile:Profile,routing:Profile):ClientConfig {
            activationAttempts += profile.id
            if (profile.id == failCandidate) {
                status.value = CoreStatus.Error("synthetic startup failure")
                onCandidateFailure()
                error("synthetic startup failure")
            }
            if(failActivation) error("synthetic startup failure")
            activated+=profile.id
            status.value = CoreStatus.Connected
            return ClientConfig(kernelConfig=profile.kernelConfig)
        }
        override suspend fun <T> withTestTunnel(profile:Profile,block:suspend(ClientConfig)->T):T=block(ClientConfig(kernelConfig=profile.kernelConfig))
    }
    @Test fun editedProfileDuringLastMeasurementMustNotBeActivatedFromStaleSnapshot()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val runtime=Runtime()
            val runner=ConnectionGroupRunner(runtime,scope) { _,id,_,_ ->
                if(id=="b") runtime.items=runtime.items.map { it.copy(name="updated") }
                GroupMeasurement(id,if(id=="b") 1 else 100,measuredAt=System.currentTimeMillis())
            }
            runner.connect(runtime.group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            assertTrue("Do not activate the old config after an edit",runtime.activated.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun failedWinnerRestoresPreviouslyWorkingTunnel()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val runtime=Runtime()
            val runner=ConnectionGroupRunner(runtime,scope) { _,id,_,_ ->
                GroupMeasurement(id,if(id=="b") 1 else 100,measuredAt=System.currentTimeMillis())
            }
            runner.selectMember(runtime.group,"a")
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            runtime.activationAttempts.clear()
            runtime.failCandidate="b"
            runner.connect(runtime.group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            assertEquals("Failed candidate must not leave the old working tunnel down", CoreStatus.Connected,runtime.status.value)
            assertEquals(listOf("b","a"),runtime.activationAttempts)
        } finally { scope.cancel() }
    }
    @Test fun failedSwitchDoesNotRestartAfterUserStopOrNetworkChange()=runBlocking {
        for (userStop in listOf(true,false)) {
            val scope=CoroutineScope(coroutineContext+SupervisorJob())
            try {
                val runtime=Runtime()
                val runner=ConnectionGroupRunner(runtime,scope) { _,id,_,_ ->
                    GroupMeasurement(id,if(id=="b") 1 else 100,measuredAt=System.currentTimeMillis())
                }
                runner.selectMember(runtime.group,"a")
                withTimeout(1000) { while(runner.state.value.busy) yield() }
                runtime.activationAttempts.clear()
                runtime.failCandidate="b"
                runtime.onCandidateFailure = {
                    if (userStop) runtime.status.value=CoreStatus.Idle else runtime.networkId="mobile"
                }
                runner.connect(runtime.group)
                withTimeout(1000) { while(runner.state.value.busy) yield() }
                assertEquals("Do not override user stop or network change",listOf("b"),runtime.activationAttempts)
            } finally { scope.cancel() }
        }
    }
    @Test fun networkChangeDuringLastMeasurementDiscardsWinner()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val runtime=Runtime()
            val runner=ConnectionGroupRunner(runtime,scope) { _,id,_,_ ->
                if(id=="b") runtime.networkId="mobile"
                GroupMeasurement(id,1,measuredAt=System.currentTimeMillis())
            }
            runner.connect(runtime.group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            assertTrue(runtime.activated.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun failedActivationDoesNotPublishCandidateAsSelected()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val runtime=Runtime().apply { failActivation=true }
            val runner=ConnectionGroupRunner(runtime,scope) { _,id,_,_ ->
                GroupMeasurement(id,if(id=="b") 1 else 100,measuredAt=System.currentTimeMillis())
            }
            runner.connect(runtime.group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            assertNotEquals("b",runner.state.value.selectedId)
            assertTrue(runtime.activated.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun editedEndpointCannotReuseMeasurementsFromOldConfig()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val runtime=Runtime();var measurements=0
            val runner=ConnectionGroupRunner(runtime,scope) { _,id,_,_ ->
                measurements++
                GroupMeasurement(id,if(id=="a") 1 else 100,measuredAt=System.currentTimeMillis(),
                    error=if(id=="a" && runtime.items.first().name=="changed endpoint") "failed" else null)
            }
            val group=runtime.group.copy(testOnConnect=false)
            runner.test(group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            runtime.items=runtime.items.map { if(it.id=="a") it.copy(name="changed endpoint") else it }
            runner.connect(group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            assertEquals(4,measurements)
            assertFalse(runtime.activated.contains("a"))
        } finally { scope.cancel() }
    }
    @Test fun removedGroupDuringLastMeasurementMustNotActivateWinner()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val runtime=Runtime()
            val runner=ConnectionGroupRunner(runtime,scope) { _,id,_,_ ->
                if(id=="b") runtime.savedGroups=emptyList()
                GroupMeasurement(id,if(id=="b") 1 else 100,measuredAt=System.currentTimeMillis())
            }
            runner.connect(runtime.group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            assertTrue(runtime.activated.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun newNetworkCannotReuseThePreviousNetworksWinner()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val runtime=Runtime();var measurements=0
            val runner=ConnectionGroupRunner(runtime,scope) { _,id,_,_ ->
                measurements++
                val best=if(runtime.networkId=="wifi") "a" else "b"
                GroupMeasurement(id,if(id==best) 1 else 100,measuredAt=System.currentTimeMillis())
            }
            val group=runtime.group.copy(testOnConnect=false,allowMobile=true)
            runner.test(group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            runtime.networkId="mobile"
            runner.connect(group)
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            assertEquals(4,measurements)
            assertEquals(listOf("b"),runtime.activated)
        } finally { scope.cancel() }
    }
    @Test fun failedManualSelectionDoesNotPublishCandidateAsConnected()=runBlocking {
        val scope=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val runtime=Runtime()
            val runner=ConnectionGroupRunner(runtime,scope)
            runner.selectMember(runtime.group,"a")
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            runtime.failActivation=true
            runner.selectMember(runtime.group,"b")
            withTimeout(1000) { while(runner.state.value.busy) yield() }
            assertEquals("a",runner.state.value.selectedId)
        } finally { scope.cancel() }
    }
}
