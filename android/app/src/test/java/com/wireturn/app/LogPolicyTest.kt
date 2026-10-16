package com.wireturn.app

import org.junit.Assert.*
import org.junit.Test

class LogPolicyTest {
    @Test fun fatalFalseIsAnOrdinaryErrorAndWarningIsNotFatal() {
        assertFalse(LogLevel.FATAL.accepts("[Core] error fatal=false: DNS unavailable"))
        assertTrue(LogLevel.ERROR.accepts("[Core] error fatal=false: DNS unavailable"))
        assertTrue(LogLevel.FATAL.accepts("[Core] error fatal=true: authentication failed"))
        assertFalse(LogLevel.ERROR.accepts("[Warning] deprecated setting"))
        assertTrue(LogLevel.WARNING.accepts("[Warning] deprecated setting"))
        assertTrue(LogLevel.ALL.accepts("TURN ChannelBind ready"))
    }
    @Test fun retentionDropsOldestRecordsAndCapsHugeMessages() {
        val logs = (0L..2100L).map { AppLogsState.LogEntry(it, "x".repeat(1000)) }
        assertEquals(200, LogRetention.trim(logs, 200).size)
        assertEquals(2100L, LogRetention.trim(logs, 200).last().id)
        assertTrue(LogRetention.trim(logs, 2000).sumOf { it.message.length } <= 1_000_000)
    }
    @Test fun turnSecretsAreRemovedButErrorReasonSurvives() {
        val log = DiagnosticRedactor.clean("DNS failed\nCookie: remixsid=short-secret; other=session\nAuthorization: Bearer abc\npassword=pwd\nhttps://api.vk.ru/method/test?access_token=token")
        for (secret in listOf("short-secret", "session", "Bearer abc", "pwd", "access_token=token")) assertFalse(log.contains(secret))
        assertTrue(log.contains("DNS failed"))
    }
}
