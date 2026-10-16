package com.wireturn.app.data.kernel

import com.wireturn.app.domain.WorkerTelemetry
import org.junit.Assert.*
import org.junit.Test

class WorkerTelemetryTest {

    @Test fun acceptsStatisticsFromBundledQwdtt() {
        assertEquals(18, WorkerTelemetry.parse("2026/10/02 23:10:15 [СТАТИСТИКА] Активных: 18 | Трафик: 3.00 МБ | ↓2.00 МБ / ↑1.00 МБ", "qwdtt"))
        assertEquals(0, WorkerTelemetry.parse("2026/10/02 23:10:18 [СТАТИСТИКА] Активных: 0 | Трафик: 3.00 МБ | ↓2.00 МБ / ↑1.00 МБ", "qwdtt"))
    }


    @Test fun parsesOnlyProtocolStatistics() {
        assertEquals(4, WorkerTelemetry.parse("2026/09/21 16:00:00 [СТАТИСТИКА] Активных: 4 | Трафик: 0.20 МБ", "qwdtt"))
        assertNull(WorkerTelemetry.parse("Активных: 4", "qwdtt"))
    }

    @Test fun staleStatisticsAreNotPresentedAsLive() {
        val sample = WorkerTelemetry.Sample(0, 18, 1000)
        assertTrue(sample.fresh(1000))
        assertTrue(sample.fresh(11000))
        assertFalse(sample.fresh(11001))
        assertFalse(sample.fresh(999))
        assertFalse(WorkerTelemetry.Sample(null, 18).fresh(1000))
    }
}
