package com.wireturn.app.kernel

import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UnreapedPoolChildTest {
    @Test fun stubbornChildPreventsReplacementInsteadOfLeakingAnotherProcess()=runBlocking {
        val starts=AtomicInteger()
        val group=RestartingProcessGroup(listOf(RestartingProcessGroup.Command(1,listOf("fake"),File("."))),
            restartDelayMs=1,startProcess={ starts.incrementAndGet(); StubbornChild() })
        val failure=runCatching { withTimeout(150) { group.run() } }.exceptionOrNull()
        assertEquals("Old child is still alive; replacement must be blocked",1,starts.get())
        assertTrue("Failure must be explicit, not just test cancellation",failure is IllegalStateException)
    }
    private class StubbornChild:Process() {
        override fun getInputStream():InputStream=ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream():InputStream=ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream():OutputStream=ByteArrayOutputStream()
        override fun isAlive()=true
        override fun waitFor():Int=error("never exits")
        override fun waitFor(timeout:Long,unit:TimeUnit)=false
        override fun exitValue():Int=throw IllegalThreadStateException()
        override fun destroy() {}
        override fun destroyForcibly():Process=this
    }
}
