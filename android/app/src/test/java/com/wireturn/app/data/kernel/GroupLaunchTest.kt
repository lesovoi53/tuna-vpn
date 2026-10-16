package com.wireturn.app.data.kernel

import com.wireturn.app.CoreStatus
import com.wireturn.app.data.*
import com.wireturn.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class GroupLaunchTest {
    @Test fun legacySpeedGroupsCannotProbeActivateOrStartAutomaticWork() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            var measurements = 0
            val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, _ ->
                measurements++
                GroupMeasurement(id, 10, measuredAt = System.currentTimeMillis())
            }
            val legacy = runtime.group.copy(type = GroupTestType.SPEEDTEST, automatic = true, testOnConnect = true)
            runner.test(legacy)
            runner.connect(legacy)
            runner.run(legacy)
            runner.selectMember(legacy, "a")
            yield()
            assertEquals(0, measurements)
            assertTrue(runtime.activated.isEmpty())
            assertNull(runtime.activeGroupId())
            assertFalse(runner.hasPendingWork)
            assertFalse(runner.state.value.busy)
            assertFalse(runner.state.value.automatic)
            assertEquals(GroupTestType.SPEEDTEST, legacy.type)
        } finally { scope.cancel() }
    }

    @Test fun historyReceivesCompletedMeasurementsAndRetainsTestType() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            val recorded = mutableListOf<GroupMeasurement>()
            val runner = ConnectionGroupRunner(runtime, scope, onMeasurement = { _, profile, result, digest ->
                assertEquals(profile.id, result.profileId)
                assertEquals(64, digest.length)
                recorded += result
            }) { _, id, _, _ -> GroupMeasurement(id, 10, 1000.0, System.currentTimeMillis()) }
            runner.test(runtime.group)
            withTimeout(2000) { while (runner.state.value.busy) yield() }
            assertEquals(2, recorded.size)
            assertEquals(GroupTestType.URL_TEST, runner.state.value.testType)
            assertTrue(runtime.activated.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun cancellationKeepsCompletedHistoryAndWaitsForCleanup() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            val secondStarted = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val recorded = mutableListOf<String>()
            val runner = ConnectionGroupRunner(runtime, scope, onMeasurement = { _, _, result, _ -> recorded += result.profileId }) { _, id, _, _ ->
                if (id == "b") {
                    secondStarted.complete(Unit)
                    try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
                }
                GroupMeasurement(id, 10, measuredAt = System.currentTimeMillis())
            }
            runner.test(runtime.group)
            withTimeout(2000) { secondStarted.await() }
            runner.release(); yield()
            assertTrue(runner.hasPendingWork)
            assertEquals(listOf("a"), recorded)
            cleanup.complete(Unit)
            withTimeout(2000) { while (runner.hasPendingWork) yield() }
            assertEquals(listOf("a"), recorded)
        } finally { scope.cancel() }
    }
    private class Runtime(val timeoutFirst: Boolean = false) : GroupRuntime {
        override val status = MutableStateFlow<CoreStatus>(CoreStatus.Idle)
        val activated = mutableListOf<String>()
        private var selected = "a"
        private var groupId: String? = null
        val items = listOf("a", "b").map { id ->
            Profile(id, id.uppercase(), KernelConfig.Vpn(VpnConfig("vless://00000000-0000-0000-0000-000000000001@$id.example:443?security=tls")))
        }
        val group = ConnectionGroup("g", "My group", ConnectionCategory.VPN, GroupTestType.URL_TEST, listOf("a", "b"))
        override suspend fun profiles() = items
        override suspend fun groups() = listOf(group)
        override suspend fun currentId() = selected
        override suspend fun activeGroupId() = groupId
        override suspend fun selectGroup(id: String) { groupId = id }
        override fun network(allowMobile: Boolean) = setOf("wifi")
        var settings = GroupProbeSettings()
        override suspend fun probeSettings(profiles: List<Profile>) = settings
        override suspend fun activate(profile: Profile, routing: Profile): ClientConfig {
            activated += profile.id
            selected = profile.id
            status.value = CoreStatus.Connected
            return ClientConfig(kernelConfig = profile.kernelConfig)
        }
        override suspend fun <T> withTestTunnel(profile: Profile, block: suspend (ClientConfig) -> T): T {
            if (timeoutFirst && profile.id == "a") withTimeout(5) { delay(1000) }
            return block(ClientConfig(kernelConfig = profile.kernelConfig))
        }
    }

    @Test fun changingDnsInvalidatesCachedGroupWinner() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            val group = runtime.group.copy(testOnConnect = false)
            var measurements = 0
            val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, _ ->
                measurements++
                GroupMeasurement(id, if (id == "a") 20 else 10, measuredAt = System.currentTimeMillis())
            }
            runner.connect(group)
            withTimeout(1000) { while (runner.state.value.busy) yield() }
            assertEquals(2, measurements)
            runtime.settings = GroupProbeSettings(SharedNetworkSettings(dns = NetworkDnsSettings(servers = "tcp://192.0.2.53")))
            runner.connect(group)
            withTimeout(1000) { while (runner.state.value.busy) yield() }
            assertEquals("Changed DNS must invalidate an otherwise fresh comparison", 4, measurements)
        } finally { scope.cancel() }
    }

    @Test fun changingDnsDuringComparisonPreventsWinnerActivation() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, _ ->
                runtime.settings = GroupProbeSettings(SharedNetworkSettings(dns = NetworkDnsSettings(servers = "tcp://192.0.2.53")))
                GroupMeasurement(id, 10, measuredAt = System.currentTimeMillis())
            }
            runner.connect(runtime.group)
            withTimeout(1000) { while (runner.state.value.busy) yield() }
            assertTrue("Results from different DNS settings must not select a server", runtime.activated.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun manualSelectionStopsAutomaticTestingAndUsesOnlyChosenMember() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            var measurements = 0
            val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, _ ->
                measurements++
                delay(10000)
                GroupMeasurement(id, 10, measuredAt = System.currentTimeMillis())
            }
            runner.run(runtime.group.copy(automatic = true))
            yield()
            runner.selectMember(runtime.group, "b")
            withTimeout(1500) { while (runner.state.value.busy) yield() }
            assertEquals(listOf("b"), runtime.activated)
            assertFalse(runner.state.value.automatic)
            assertEquals("b", runner.state.value.selectedId)
            assertTrue(measurements <= 1)
        } finally { scope.cancel() }
    }

    @Test fun clickingTestWithVpnOffStartsCandidatesAndShowsWinner() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, progress ->
                progress(ProbeProgress(1, 1))
                GroupMeasurement(id, if (id == "a") 50 else 10, measuredAt = System.currentTimeMillis())
            }
            runner.run(runtime.group)
            yield()
            assertTrue("Test click while disconnected must start a candidate", runtime.activated.isNotEmpty())
            withTimeout(1000) { while (runner.state.value.busy) yield() }
            assertEquals("Only the winner may replace the main tunnel", listOf("b"), runtime.activated)
            assertEquals("b", runner.state.value.selectedId)
            assertEquals(2, runner.state.value.results.size)
        } finally { scope.cancel() }
    }

    @Test fun urlTestFromIdlePublishesLiveProgressAndReusesFreshWinner() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            val group = runtime.group.copy(testOnConnect = false)
            var measurements = 0
            lateinit var runner: ConnectionGroupRunner
            runner = ConnectionGroupRunner(runtime, scope) { _, id, _, progress ->
                assertEquals(id, runner.state.value.testingId)
                assertTrue(runner.state.value.busy)
                progress(ProbeProgress(1, 1, 4096, 100))
                assertEquals(4096L, runner.state.value.progress?.bytes)
                measurements++
                GroupMeasurement(id, if (id == "a") 20 else 10, measuredAt = System.currentTimeMillis())
            }
            runner.connect(group)
            withTimeout(1000) { while (runner.state.value.busy) yield() }
            assertEquals("b", runner.state.value.selectedId)
            assertEquals(2, measurements)
            runner.connect(group)
            withTimeout(1000) { while (runner.state.value.busy) yield() }
            assertEquals(2, measurements)
            assertEquals("b", runtime.activated.last())
        } finally { scope.cancel() }
    }

    @Test fun oneTimedOutCandidateDoesNotCancelWholeGroup() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime(timeoutFirst = true)
            val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, _ ->
                GroupMeasurement(id, 10, measuredAt = System.currentTimeMillis())
            }
            runner.run(runtime.group)
            withTimeout(1500) { while (runner.state.value.busy) yield() }
            assertEquals("b", runner.state.value.selectedId)
            assertEquals("Таймаут подключения", runner.state.value.results.first().error)
            assertEquals(2, runner.state.value.results.size)
        } finally { scope.cancel() }
    }

    @Test fun manualUrlTestsNeverReplaceActiveTunnelOrSelectGroup() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            for (type in listOf(GroupTestType.URL_TEST)) {
                val runtime = Runtime()
                runtime.status.value = CoreStatus.Connected
                val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, _ ->
                    GroupMeasurement(id, 20, 100.0, System.currentTimeMillis())
                }
                runner.test(runtime.group.copy(type = type))
                withTimeout(1000) { while (runner.state.value.busy) yield() }
                assertTrue(runtime.activated.isEmpty())
                assertNull(runtime.activeGroupId())
                assertEquals("a", runtime.currentId())
                assertEquals(2, runner.state.value.results.size)
                assertFalse(runner.state.value.automatic)
            }
        } finally { scope.cancel() }
    }
}
