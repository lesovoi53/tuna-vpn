package com.wireturn.app.kernel

import org.junit.Assert.*
import org.junit.Test

class VpnCoreLogTest {
    @Test fun ordinaryDestinationAndErrorTextCannotStopTheCore() {
        for (line in listOf(
            "INFO[0001] outbound/snell[vpn]: outbound connection to fatal.example:443",
            "ERROR[0002] connection: remote error: tls: fatal alert",
            "2026/10/02 14:00:00 [Warning] peer reported fatal error",
            "INFO[0003] response body: [FATAL] unrelated text",
            "ERROR[0004] status=fatal=false",
            "INFO[0005] name=FATAL panic: ignored"
        )) assertFalse(line, VpnCoreLog.isFatal(line))
    }
    @Test fun realNativeFatalLevelsStopTheCore() {
        for (line in listOf(
            "FATAL[0000] start service: listen failed",
            "+0600 2026-10-02 14:00:00 FATAL [42] failed to start",
            "2026/10/02 14:00:00.123456 [Fatal] startup failed",
            "F1002 14:00:00.123456 1234 main.go:44] startup failed",
            "panic: unexpected native failure",
            "fatal error: concurrent map writes"
        )) assertTrue(line, VpnCoreLog.isFatal(line))
    }
}
