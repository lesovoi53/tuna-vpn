package com.wireturn.app.kernel

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AbandonedSecretChecksTest {
    @Test fun cleanupRemovesOnlyKnownGeneratedChecksAndPreservesOtherCache() {
        val cache = kotlin.io.path.createTempDirectory("tuna-check-cleanup-").toFile()
        try {
            val removed = listOf("core-check-123.json", "core-check-456.log", "route-check-789.json", "route-check-00000000-0000-0000-0000-000000000000.json", ".tuna-config-123456.tmp")
            val retained = listOf("core-check-not-generated.json", "my-profile.json", "update.apk", "route-check-123.txt")
            (removed + retained).forEach { File(cache, it).writeText("fixture") }
            val probe = File(cache, "profile-test-12345").apply { mkdirs() }
            File(probe, "nested").mkdir()
            File(probe, "nested/config.json").writeText("fixture-runtime-secret")
            val unknown = File(cache, "profile-test-user").apply { mkdirs() }
            File(unknown, "config.json").writeText("fixture-user-data")
            PrivateRuntimeDirectory.removeAbandonedChecks(cache)
            removed.forEach { assertFalse(File(cache, it).exists()) }
            retained.forEach { assertTrue(File(cache, it).isFile) }
            assertFalse(probe.exists())
            assertEquals("fixture-user-data", File(unknown, "config.json").readText())
            PrivateRuntimeDirectory.removeAbandonedChecks(cache) // idempotent
        } finally { cache.deleteRecursively() }
    }
}
