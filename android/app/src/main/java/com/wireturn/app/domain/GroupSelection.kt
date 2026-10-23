package com.wireturn.app.domain

import com.wireturn.app.data.ConnectionGroup
import com.wireturn.app.data.GroupSelectionMode
import com.wireturn.app.data.GroupTestType

data class GroupMeasurement(
    val profileId: String,
    val latencyMs: Long,
    val bytesPerSecond: Double = 0.0,
    val measuredAt: Long,
    val error: String? = null,
    /** Legacy measurement metadata, retained for stored-history compatibility only. */
    val selectionLatencyMs: Long? = null,
    val endpointLabel: String? = null
)

/** Stateful hysteresis; timestamps supplied by the caller also make stale results testable. */
class GroupSelection {
    var decisionMessage: String = ""
        private set
    private var challenger: String? = null
    private var wins = 0
    private var failures = 0
    private var lastSwitch = 0L

    fun choose(group: ConnectionGroup, results: List<GroupMeasurement>, current: String?, now: Long): String? {
        decisionMessage = ""
        if (group.type != GroupTestType.URL_TEST) return null
        val fresh = results.filter { it.profileId in group.memberIds && it.error == null &&
            now >= it.measuredAt && now - it.measuredAt <= group.freshnessSeconds * 1000L &&
            it.latencyMs >= 0 }
        val ordered = if (group.effectiveSelectionMode == GroupSelectionMode.PRIORITY) fresh.sortedBy { group.memberIds.indexOf(it.profileId) }
            else fresh.sortedBy { it.latencyMs }
        val best = ordered.firstOrNull() ?: run {
            decisionMessage = "Нет свежих успешных результатов"
            challenger = null; wins = 0; failures = 0
            return null
        }
        val active = fresh.find { it.profileId == current }
        if (current == null) {
            lastSwitch = now
            decisionMessage = if (group.effectiveSelectionMode == GroupSelectionMode.PRIORITY)
                "Выбран первый доступный сервер по приоритету" else "Выбран сервер с минимальной задержкой"
            return best.profileId
        }
        if (active == null) {
            challenger = null; wins = 0
            failures++
            if (failures < maxOf(2, group.confirmations) ||
                (lastSwitch != 0L && now - lastSwitch < group.holdSeconds * 1000L)) {
                decisionMessage = "Текущий сервер не прошёл тест: подтверждений $failures из ${maxOf(2, group.confirmations)}; удержание ${group.holdSeconds} с"
                return current
            }
            failures = 0; lastSwitch = now; challenger = null; wins = 0
            decisionMessage = "Переключение после подтверждённого отказа текущего сервера"
            return best.profileId
        }
        failures = 0
        val improved = best.profileId != current && (group.effectiveSelectionMode == GroupSelectionMode.PRIORITY ||
            active.latencyMs - best.latencyMs > group.improvementMs)
        if (!improved) {
            challenger = null; wins = 0
            decisionMessage = when {
                group.effectiveSelectionMode == GroupSelectionMode.PRIORITY -> "Сохранён первый доступный сервер по приоритету"
                best.profileId == current -> "Текущий сервер имеет минимальную задержку"
                else -> "Разница ${active.latencyMs - best.latencyMs} мс не превышает порог ${group.improvementMs} мс"
            }
            return current
        }
        if (challenger == best.profileId) wins++ else { challenger = best.profileId; wins = 1 }
        if (wins < group.confirmations || now - lastSwitch < group.holdSeconds * 1000L) {
            val remaining = ((group.holdSeconds * 1000L - (now - lastSwitch)).coerceAtLeast(0) + 999) / 1000
            decisionMessage = "Кандидат лучше: подтверждений ${minOf(wins, group.confirmations)} из ${group.confirmations}" +
                if (remaining > 0) "; до конца удержания ${remaining} с" else "; ожидание следующей проверки"
            return current
        }
        challenger = null; wins = 0; lastSwitch = now
        decisionMessage = "Переключение на подтверждённого кандидата"
        return best.profileId
    }

}
