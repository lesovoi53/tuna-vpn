package com.wireturn.app.kernel

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.wireturn.app.CaptchaSession
import com.wireturn.app.CoreStatus
import com.wireturn.app.domain.awaitCoreSupervisorResume
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class CoreCommandBurstTest {
    @Test(timeout=10000) fun announcingStartCancelsParkedRunBeforeWakingItAndStillJoinsCleanup() = runBlocking {
        for (parked in listOf(CoreStatus.Suppressed, CoreStatus.WaitingForNetwork,
            CoreStatus.CaptchaRequired(CaptchaSession("https://example.invalid/captcha", 1)))) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val lifecycle = CoreCommandLifecycle(scope, Dispatchers.Unconfined)
            val status = MutableStateFlow<CoreStatus>(parked)
            val entered = CompletableDeferred<Unit>()
            val cleanupEntered = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val events = CopyOnWriteArrayList<String>()
            try {
                lifecycle.start({}) {
                    try {
                        entered.complete(Unit)
                        awaitCoreSupervisorResume(status)
                        events += "obsolete start"
                    } finally {
                        withContext(NonCancellable) {
                            cleanupEntered.complete(Unit)
                            releaseCleanup.await()
                            events += "old cleanup"
                        }
                    }
                }
                withTimeout(3000) { entered.await() }
                lifecycle.cancelBeforeStart { status.value = CoreStatus.Starting }
                withTimeout(3000) { cleanupEntered.await() }
                lifecycle.start({}) { events += "replacement start" }
                assertTrue(events.isEmpty())
                releaseCleanup.complete(Unit)
                withTimeout(3000) { lifecycle.job!!.join() }
                assertEquals(listOf("old cleanup", "replacement start"), events.toList())
            } finally { releaseCleanup.complete(Unit); scope.cancel() }
        }
    }

    @Test(timeout=10000) fun announcedReplacementPreventsOldStopFromPublishingIdleBeforeServiceStartArrives() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lifecycle = CoreCommandLifecycle(scope, Dispatchers.Unconfined)
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<String>()
        try {
            lifecycle.start({}) { awaitCancellation() }
            lifecycle.stop({}, {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
                events += "stop cleanup"
            }, { events += "stale idle" })
            withTimeout(3000) { cleanupEntered.await() }
            lifecycle.cancelBeforeStart { events += "starting" }
            releaseCleanup.complete(Unit)
            withTimeout(3000) { lifecycle.job!!.join() }
            assertEquals(listOf("starting", "stop cleanup"), events.toList())
            lifecycle.start({}) { events += "replacement start" }
            withTimeout(3000) { lifecycle.job!!.join() }
            assertEquals(listOf("starting", "stop cleanup", "replacement start"), events.toList())
        } finally { releaseCleanup.complete(Unit); scope.cancel() }
    }

    @Test(timeout=10000) fun queuedCommandBurstsCannotStartRetiredRunsOrPublishStaleIdle()=runBlocking {
        for(count in listOf(2,8,32)) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
            val lifecycle=CoreCommandLifecycle(scope,Dispatchers.Unconfined)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val events=CopyOnWriteArrayList<String>()
            try {
                lifecycle.start({}) { awaitCancellation() }
                lifecycle.stop({}, { entered.complete(Unit); release.await();events+="first cleanup" }, {events+="stale idle"})
                withTimeout(3000) { entered.await() }
                repeat(count) { index ->
                    lifecycle.start({}) { events+="stale run $index" }
                    lifecycle.stop({}, { events+="cleanup $index" }, {events+="stale idle $index"})
                }
                lifecycle.start({}) { events+="final run" }
                release.complete(Unit)
                withTimeout(5000) { lifecycle.job!!.join() }
                assertEquals(listOf("first cleanup")+(0 until count).map {"cleanup $it"}+"final run",events.toList())
            } finally { release.complete(Unit);scope.cancel() }
        }
    }
}
