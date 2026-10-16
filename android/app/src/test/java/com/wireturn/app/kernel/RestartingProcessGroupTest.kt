package com.wireturn.app.kernel

import java.io.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RestartingProcessGroupTest {
    @Test fun startupExceptionReachesDiagnosticsBeforeRestart() = runBlocking {
        val result = CompletableDeferred<List<Any?>>()
        val failure = IOException("synthetic startup failure")
        val group = RestartingProcessGroup(listOf(RestartingProcessGroup.Command(1, listOf("missing"), File("."))),
            startProcess = { throw failure },
            onTermination = { id, attempt, reason, code, error -> result.complete(listOf(id, attempt, reason, code, error)) })
        val job = launch(Dispatchers.Default) { group.run() }
        try {
            val event = withTimeout(3000) { result.await() }
            assertEquals(listOf(1, 1, "start_failed", null, failure), event)
        } finally { group.stop(); job.cancelAndJoin() }
    }

    @Test fun nativeExitCodeAndOriginalStderrReachDiagnostics() = runBlocking {
        val result = CompletableDeferred<Pair<String, Int?>>()
        val lines = CopyOnWriteArrayList<String>()
        val group = RestartingProcessGroup(listOf(RestartingProcessGroup.Command(1, listOf("fake"), File("."))),
            startProcess = { object : Process() {
                override fun getInputStream() = ByteArrayInputStream("fatal: auth status 403\n".toByteArray())
                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun waitFor() = 23
                override fun exitValue() = 23
                override fun isAlive() = false
                override fun destroy() {}
            } },
            onLine = { _, line -> lines.add(line) },
            onTermination = { _, _, reason, code, _ -> result.complete(reason to code) })
        val job = launch(Dispatchers.Default) { group.run() }
        try {
            assertEquals("exited" to 23, withTimeout(3000) { result.await() })
            assertEquals(listOf("fatal: auth status 403"), lines)
        } finally { group.stop(); job.cancelAndJoin() }
    }
    @Test fun incompatibleChannelStopsWithoutRestartingOtherChannels() = runBlocking {
        val children = java.util.concurrent.ConcurrentHashMap<Int, Child>()
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val disabled = CompletableDeferred<Int>()
        val group = RestartingProcessGroup((1..2).map { RestartingProcessGroup.Command(it, listOf("channel"), File(".")) },
            restartDelayMs = 10,
            permanentFailure = { _, line -> line == "action_url missing" },
            onPermanentFailure = { disabled.complete(it) },
            startProcess = { command -> starts.incrementAndGet(); Child().also { children[command.id] = it } })
        val job = launch(Dispatchers.Default) { group.run() }
        try {
            withTimeout(3000) { while (children.size < 2) delay(10) }
            children.getValue(1).emit("action_url missing")
            assertEquals(1, withTimeout(3000) { disabled.await() })
            withTimeout(3000) { while (children.getValue(1).isAlive) delay(10) }
            delay(100)
            assertEquals(2, starts.get())
            assertTrue(children.getValue(2).isAlive)
        } finally { group.stop(); job.cancelAndJoin() }
        assertTrue(children.values.none { it.isAlive })
    }
    @Test fun cancellationReapsChildrenAndStopBeforeStartCreatesNone() = runBlocking {
        val children = CopyOnWriteArrayList<Child>()
        val commands = (0..8).map { RestartingProcessGroup.Command(it, listOf("child-$it"), File(".")) }
        val group = RestartingProcessGroup(commands, startProcess = { Child().also(children::add) })
        val job = launch(Dispatchers.Default) { group.run() }
        try {
            withTimeout(3_000) { while (children.size < 9) delay(10) }
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(children.none { it.isAlive })
        } finally { group.stop(); job.cancelAndJoin() }
        val stopped = RestartingProcessGroup(commands, startProcess = { error("started after stop") })
        stopped.stop()
        withTimeout(3_000) { stopped.run() }
    }
    @Test fun replacesOnlyFailedChildAndStopsEveryChild() = runBlocking {
        val children = CopyOnWriteArrayList<Child>()
        val commands = (1..8).map { RestartingProcessGroup.Command(it, listOf("channel-$it"), File(".")) }
        val group = RestartingProcessGroup(commands, restartDelayMs = 20,
            startProcess = { Child().also(children::add) })
        val job = launch(Dispatchers.Default) { group.run() }
        suspend fun waitForCount(n: Int) = withTimeout(3_000) { while (children.size < n) delay(10) }
        try {
            waitForCount(8)
            children[0].destroy()
            waitForCount(9)
            assertTrue(children.drop(1).all { it.isAlive })
            group.stop()
            withTimeout(3_000) { job.join() }
            assertTrue(children.none { it.isAlive })
            delay(80)
            assertEquals(9, children.size)
        } finally { group.stop(); job.cancelAndJoin() }
    }

    // The process boundary is simulated; the real supervisor and coroutines run unchanged.
    private class Child : Process() {
        private val output = PipedOutputStream()
        private val input = PipedInputStream(output)
        private val ended = CountDownLatch(1)
        fun emit(line: String) { output.write((line + "\n").toByteArray()); output.flush() }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = ended.await(timeout, unit)
        override fun exitValue(): Int { check(!isAlive); return 0 }
        override fun isAlive(): Boolean = ended.count != 0L
        override fun destroy() { output.close(); ended.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
