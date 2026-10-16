package com.wireturn.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class InternetAccessStatusTest {
    @Test fun tunnelStartIsNotProofOfInternetAccess() {
        assertEquals(InternetAccessStatus.CHECKING,
            InternetAccessMonitor.visibleStatus(true, 2L, null, InternetAccessStatus.AVAILABLE))
    }
    @Test fun anotherSessionCannotInheritSuccessfulCheck() {
        assertEquals(InternetAccessStatus.CHECKING,
            InternetAccessMonitor.visibleStatus(true, 2L, 1L, InternetAccessStatus.AVAILABLE))
    }
    @Test fun disconnectedSessionCannotRetainSuccess() {
        assertEquals(InternetAccessStatus.CHECKING,
            InternetAccessMonitor.visibleStatus(false, 1L, 1L, InternetAccessStatus.AVAILABLE))
    }
    @Test fun onlyCurrentSessionResultIsShown() {
        for (result in InternetAccessStatus.entries) assertEquals(result,
            InternetAccessMonitor.visibleStatus(true, 1L, 1L, result))
    }
}
