package com.wireturn.app.data.kernel

import com.wireturn.app.domain.CoalescedRefresh
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoalescedRefreshTest {
    @Test fun serviceBurstPublishesLatestStateOnceAndAcceptsLaterChanges() = runBlocking {
        val published = mutableListOf<String>()
        var state = "Starting"
        val refresher = CoalescedRefresh(this, 40) { published += state }
        repeat(30) { refresher.request() }
        yield()
        state = "Connected"
        refresher.request()
        delay(100)
        assertEquals(listOf("Connected"), published)
        state = "Idle"
        refresher.request()
        delay(100)
        assertEquals(listOf("Connected", "Idle"), published)
    }
}
