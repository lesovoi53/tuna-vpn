package com.wireturn.app.data

import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test

class RestoredProfileImportTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()
    private fun imported(uri: String): Profile = gson.fromJson(
        """{"id":"qa","name":"QA","uri":"$uri"}""", Profile::class.java).sanitize()

    @Test fun removedKernelsRemainRejected() {
        listOf(KernelConfig.Turnable(), KernelConfig.Olcrtc(), KernelConfig.FreeTurn()).forEach { kernel ->
            assertThrows(IllegalArgumentException::class.java) { Profile("qa", "QA", kernel).sanitize() }
        }
    }


    @Test fun webdavQuickImportRetainsAllParametersAfterPersistence() {
        val profile = imported("webdav://qa:example@127.0.0.1:8443?chunk-size=131071&coalesce=5ms&poll-max=200ms&poll-min=50ms&puts=8&read-max=8&read-min=3&timeout=60s")
        assertTrue(profile.kernelConfig is KernelConfig.Webdav)
        assertFalse(profile.isEmpty())
        val restored = gson.fromJson(gson.toJson(profile), Profile::class.java).sanitize()
        assertEquals(profile, restored)
        assertEquals("50ms", restored.webdavConfig.pollMin)
        assertEquals("200ms", restored.webdavConfig.pollMax)
        assertEquals("5ms", restored.webdavConfig.coalesce)
    }
}
