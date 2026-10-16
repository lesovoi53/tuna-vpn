package com.wireturn.app.data.kernel

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class VkProfileModesTest {
    @Test fun legacyCaptchaSettingsMigrateToAutomaticWithoutChangingCredentials() {
        val qwdtt = QwdttConfig(peer = "192.0.2.1:56000", password = "test", anonymousPath = "legacy", manualCaptcha = true, accountAuth = true)
        assertEquals(qwdtt.copy(anonymousPath = "vkcalls", manualCaptcha = false), qwdtt.sanitize())
    }
    @Test fun qwdttAccountCanStartWithoutManualHashes() {
        val config = QwdttConfig(peer = "192.0.2.1:56000", password = "test", accountAuth = true)
        assertTrue("Account startup must reach automatic hash preparation", config.isValid())
        assertEquals(4, config.effectiveWorkers)
        assertFalse("Anonymous startup still needs a supplied hash", config.copy(accountAuth = false).isValid())
    }
    private val hash = "abcdefghijklmnop1234"
    @Test fun qwdttEmptyHashesDoNotInheritAnotherServerSecrets() {
        val old = QwdttConfig("192.0.2.5:56000", hash, "old")
        for (suffix in listOf("", "&hashes=")) {
            val parsed = QwdttConfig.parse("qwdtt://config?peer=192.0.2.8%3A56000&pass=new$suffix", old)!!
            assertTrue(parsed.isImportable())
            assertFalse(parsed.isValid())
            assertEquals("", parsed.vkHashes)
            assertEquals("new", parsed.password)
        }
        assertNull(QwdttConfig.parse("qwdtt://config?peer=192.0.2.8%3A56000", old))
    }
    @Test fun qwdttRoundTripsReservedCharactersAndModes() {
        val cfg = QwdttConfig("[2001:db8::1]:56000", "$hash,$hash-2", "a+&=#",
            workers = 18, obfsMode = "video", turnTcp = true, noTls = true,
            manualCaptcha = true, anonymousPath = "legacy", accountAuth = true)
        assertTrue(cfg.isValid())
        assertEquals(cfg.automaticTurnModes(), QwdttConfig.parse(cfg.toUri("Имя + &")))
        assertNotNull(QwdttConfig.parse("qwdtt:config?peer=example.com&dtls_port=56000&pass=p"))
        assertNull(QwdttConfig.parse("qwdtt://config?peer=example.com%3A0&pass=p"))
        assertNull(QwdttConfig.parse("qwdtt://config?peer=example.com%3A56000&pass=p&workers=abc"))
        assertNull(QwdttConfig.parse("qwdtt://config?peer=example.com%3A56000&pass=p&pass=x"))
    }
    @Test fun legacyJsonRemainsManualAndNeverImportsAnAccountToken() {
        val qwdtt = Gson().fromJson("""{"peer":"192.0.2.1:56000","password":"p","hashes":"$hash"}""", QwdttConfig::class.java).sanitize()
        assertTrue(qwdtt.isValid())
        assertFalse(qwdtt.accountAuth)
        assertEquals("vkcalls", qwdtt.anonymousPath)
    }
}
