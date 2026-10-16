package com.wireturn.app.domain

import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.SubscriptionImportIssue
import com.wireturn.app.data.kernel.VpnConfig
import com.wireturn.app.kernel.NativeConfigCheckFailure
import kotlinx.coroutines.CancellationException

internal data class CheckedVlessImports(
    val profiles: List<Pair<String?, Profile>>,
    val rejected: List<SubscriptionImportIssue>
)

/** All import paths call this before publishing profiles; one bad entry cannot hide others. */
internal suspend fun checkVlessImports(
    entries: List<Pair<String?, Profile>>,
    check: suspend (VpnConfig) -> Unit
): CheckedVlessImports {
    val accepted = mutableListOf<Pair<String?, Profile>>()
    val rejected = mutableListOf<SubscriptionImportIssue>()
    for (entry in entries) {
        val vpn = (entry.second.kernelConfig as? KernelConfig.Vpn)?.config
        try {
            if (vpn?.protocol == "vless") check(vpn)
            accepted += entry
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeConfigCheckFailure) {
            rejected += SubscriptionImportIssue.profile(entry.second).copy(reason = when (failure.kind) {
                NativeConfigCheckFailure.Kind.TIMEOUT -> "Нативная проверка VLESS не завершилась за 15 секунд. Сервер ещё не проверялся."
                NativeConfigCheckFailure.Kind.REJECTED -> "Ядро отклонило параметры VLESS: неподдерживаемая или некорректная конфигурация. Сервер ещё не проверялся."
            })
        } catch (_: Exception) {
            // Native errors may contain key material. Store presentation metadata only.
            rejected += SubscriptionImportIssue.profile(entry.second).copy(
                reason = "Нативная проверка VLESS не пройдена: ядро отклонило параметры либо проверка превысила время. Сервер ещё не проверялся.")
        }
    }
    return CheckedVlessImports(accepted, rejected)
}
