package com.wireturn.app.data.kernel

import com.wireturn.app.CoreStatus
import com.wireturn.app.data.*
import com.wireturn.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class GroupAvailabilityTest {
    private val vpn = ConnectionGroup("vpn", "VPN", ConnectionCategory.VPN,
        GroupTestType.URL_TEST, listOf("a", "b"))

    private class Runtime : GroupRuntime {
        override val status = MutableStateFlow<CoreStatus>(CoreStatus.Connected)
        var calls = 0
        var selected = "a"
        var activeGroup: String? = null
        val activated = mutableListOf<String>()
        val items = listOf("a", "b").map { id ->
            Profile(id, id.uppercase(), KernelConfig.Vpn(VpnConfig("vless://00000000-0000-0000-0000-000000000001@$id.example:443?security=tls")))
        }
        override suspend fun profiles(): List<Profile> { calls++; return items }
        override suspend fun groups(): List<ConnectionGroup> { calls++; return emptyList() }
        override suspend fun currentId(): String { calls++; return selected }
        override suspend fun activeGroupId(): String? { calls++; return activeGroup }
        override suspend fun selectGroup(id: String) { calls++; activeGroup = id }
        override fun network(allowMobile: Boolean): Set<String> { calls++; return setOf("wifi") }
        override suspend fun activate(profile: Profile, routing: Profile): ClientConfig {
            calls++
            activated += profile.id
            selected = profile.id
            return ClientConfig(kernelConfig = profile.kernelConfig)
        }
        override suspend fun <T> withTestTunnel(profile: Profile, block: suspend (ClientConfig) -> T): T {
            calls++
            return block(ClientConfig(kernelConfig = profile.kernelConfig))
        }
    }

    @Test fun bothBypassTestModesCannotStartThroughAnyRunnerEntryPoint() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            val runner = ConnectionGroupRunner(runtime, scope) { _, _, _, _ -> error("Must not measure BYPASS") }
            yield()
            for (type in GroupTestType.entries) {
                for (family in listOf(GroupFamily.QWDTT, GroupFamily.WEBDAV)) {
                    val savedOrImported = vpn.copy(id = "legacy-$family-$type", category = ConnectionCategory.BYPASS,
                        type = type, family = family, automatic = true, testOnConnect = true)
                    assertFalse(ManualGroupPolicy.allows(savedOrImported))
                    runner.connect(savedOrImported)
                    runner.test(savedOrImported)
                    runner.run(savedOrImported)
                    runner.selectMember(savedOrImported, "b")
                }
            }
            yield()
            assertEquals(0, runtime.calls)
            assertEquals(GroupRunState(), runner.state.value)
            assertEquals("a", runtime.selected)
            assertNull(runtime.activeGroup)
            assertEquals(CoreStatus.Connected, runtime.status.value)
        } finally { scope.cancel() }
    }

    @Test fun forbiddenRequestDoesNotCancelAnOngoingVpnGroupTest() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime()
            val started = CompletableDeferred<Unit>()
            val continueTest = CompletableDeferred<Unit>()
            val runner = ConnectionGroupRunner(runtime, scope) { _, id, _, _ ->
                started.complete(Unit)
                continueTest.await()
                GroupMeasurement(id, 10, measuredAt = System.currentTimeMillis())
            }
            runner.test(vpn)
            withTimeout(1000) { started.await() }
            val before = runner.state.value
            for (type in GroupTestType.entries) {
                val bypass = vpn.copy(id = "bypass", category = ConnectionCategory.BYPASS, type = type)
                runner.connect(bypass)
                runner.test(bypass)
                runner.selectMember(bypass, "b")
            }
            assertEquals(before, runner.state.value)
            continueTest.complete(Unit)
            withTimeout(1000) { while (runner.state.value.busy) yield() }
            assertEquals(2, runner.state.value.results.size)
            assertEquals("vpn", runner.state.value.groupId)
            assertEquals("a", runtime.selected)
            assertTrue(runtime.activated.isEmpty())
            assertEquals(CoreStatus.Connected, runtime.status.value)
        } finally { scope.cancel() }
    }

    @Test fun activeMigrationClearsBypassAndRetiredSpeedGroupsWithoutChangingRecords() {
        val groups = GroupTestType.entries.flatMap { type ->
            listOf(vpn.copy(id = "vpn-$type", type = type),
                vpn.copy(id = "bypass-$type", category = ConnectionCategory.BYPASS, type = type,
                    automatic = true, family = GroupFamily.WEBDAV))
        }
        val original = groups.map { it.copy() }
        for (group in groups) {
            val allowed = group.category == ConnectionCategory.VPN && group.type == GroupTestType.URL_TEST
            assertEquals(allowed, ManualGroupPolicy.allows(group))
            assertEquals(if (allowed) null else group.id, ManualGroupPolicy.activeGroupToClear(group.id, groups))
        }
        assertNull(ManualGroupPolicy.activeGroupToClear(null, groups))
        assertNull(ManualGroupPolicy.activeGroupToClear("deleted", groups))
        assertEquals(original, groups)
    }

    @Test fun releasingLegacyCoordinatorDoesNotChangeTheOrdinaryVpnOrSelectedProfile() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val runtime = Runtime().apply { selected = "b" }
            val runner = ConnectionGroupRunner(runtime, scope)
            runner.state.value = GroupRunState("old-bypass", automatic = true, selectedId = "b")
            runner.release()
            yield()
            assertEquals(0, runtime.calls)
            assertEquals("b", runtime.selected)
            assertEquals(CoreStatus.Connected, runtime.status.value)
            assertFalse(runner.state.value.busy)
            assertFalse(runner.state.value.automatic)
            assertEquals("b", runner.state.value.selectedId)
        } finally { scope.cancel() }
    }
}
