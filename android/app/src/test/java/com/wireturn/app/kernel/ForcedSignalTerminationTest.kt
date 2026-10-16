package com.wireturn.app.kernel

import java.io.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ForcedSignalTerminationTest {
    @Test fun failedTermStillForcesExitAndDoesNotLeaveAChild() = runBlocking {
        var alive = true
        val events = mutableListOf<String>()
        val process = object : Process() {
            override fun getInputStream(): InputStream = error("unused")
            override fun getErrorStream(): InputStream = error("unused")
            override fun getOutputStream(): OutputStream = error("unused")
            override fun waitFor(): Int = error("unbounded wait")
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { events += "wait"; return !alive }
            override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
            override fun destroy() { error("stream closing destroy") }
        }
        terminateCoreProcess(process, { events += "TERM"; error("signal failed") },
            forceTerminate = { events += "KILL"; alive = false })
        assertEquals(listOf("TERM", "KILL", "wait"), events)
        terminateCoreProcess(process, { fail("must not signal an exited child") })
    }
    @Test fun usesExplicitKillAndReapsBeforeReturningWithoutClosingBlockedPipes() = runBlocking {
        var alive = true
        val events = mutableListOf<String>()
        val process = object : Process() {
            override fun getInputStream(): InputStream = error("not needed")
            override fun getErrorStream(): InputStream = error("not needed")
            override fun getOutputStream(): OutputStream = error("not needed")
            override fun waitFor(): Int = error("unbounded wait")
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { events += "wait"; return !alive }
            override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
            override fun destroy() { error("Android stream-closing destroy must not be called") }
        }
        terminateCoreProcess(process, { events += "TERM" }, forceTerminate = { events += "KILL"; alive = false })
        assertEquals(listOf("TERM", "wait", "KILL", "wait"), events)
        assertFalse(alive)
    }
}
