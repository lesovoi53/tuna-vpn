package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.kernel.NativeConfigCheckFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VlessImportGateTest {
    private fun profile(name: String) = Profile(id = name, name = name, kernelConfig = KernelConfig.Vpn(
        VpnConfig("vless://00000000-0000-4000-8000-000000000001@example.invalid:443?encryption=none", "xray")))

    @Test fun badEntryIsReportedWithoutDiscardingGoodEntriesOrPublishingSecrets() = runBlocking {
        val good = profile("good")
        val bad = profile("bad").copy(kernelConfig = KernelConfig.Vpn(VpnConfig("vless://invalid", "xray")))
        val result = checkVlessImports(listOf(null to bad, null to good)) {
            if (it.uri.endsWith("invalid")) error("privatekey=never-include-this")
        }
        assertEquals(listOf(good), result.profiles.map { it.second })
        assertEquals(1, result.rejected.size)
        assertEquals("bad", result.rejected.single().name)
        assertFalse(result.rejected.toString().contains("never-include-this"))
    }

    @Test fun cancellationNeverBecomesAnImportFailure() = runBlocking {
        try {
            checkVlessImports(listOf(null to profile("test"))) { throw CancellationException("cancel") }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun timeoutAndNativeRejectionRemainDistinctWithoutDroppingNeighborsOrLeakingCauses() = runBlocking {
        val entries = listOf("before", "timeout", "between", "rejected", "after").map { null to profile(it) }
        var checked = 0
        val result = checkVlessImports(entries) {
            // Profiles share a synthetic URI; associate each invocation with its entry.
            when (entries[checked++].second.name) {
                "timeout" -> throw NativeConfigCheckFailure(NativeConfigCheckFailure.Kind.TIMEOUT)
                    .also { it.initCause(IllegalStateException("privatekey=never-include-timeout")) }
                "rejected" -> throw NativeConfigCheckFailure(NativeConfigCheckFailure.Kind.REJECTED)
                    .also { it.initCause(IllegalStateException("privatekey=never-include-rejection")) }
            }
        }
        assertEquals(listOf("before", "between", "after"), result.profiles.map { it.second.name })
        assertEquals(listOf("timeout", "rejected"), result.rejected.map { it.name })
        assertEquals("Нативная проверка VLESS не завершилась за 15 секунд. Сервер ещё не проверялся.", result.rejected[0].reason)
        assertEquals("Ядро отклонило параметры VLESS: неподдерживаемая или некорректная конфигурация. Сервер ещё не проверялся.", result.rejected[1].reason)
        assertFalse(result.rejected.toString().contains("never-include"))
    }

    @Test fun typedNativeFailuresKeepExistingEditorMessages() {
        assertEquals("Проверка ядром не завершилась за 15 секунд",
            NativeConfigCheckFailure(NativeConfigCheckFailure.Kind.TIMEOUT).message)
        assertEquals("Ядро отклонило конфигурацию: неподдерживаемые или некорректные параметры. Это не ошибка доступности сервера.",
            NativeConfigCheckFailure(NativeConfigCheckFailure.Kind.REJECTED).message)
    }
}
