package com.wireturn.app

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageDiagnosticTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun accountsForDataWithoutExportingNamesOrChangingFiles() {
        val root = temporary.newFolder()
        val files = mapOf(
            "files/subscription-routing/private-token/generation-ab/geoip.dat" to 20,
            "files/geo_catalog/provider/generation-cd/geosite.dat" to 30,
            "no_backup/diagnostics/diagnostic-00000000000000000001.log" to 40,
            "shared_prefs/secret.xml" to 10,
            "cache/export.zip" to 5
        )
        files.forEach { (path, size) -> File(root, path).apply { requireNotNull(parentFile).mkdirs(); writeBytes(ByteArray(size)) } }
        val before = files.keys.associateWith { File(root, it).readBytes().toList() }
        val result = StorageDiagnostic.report(root)
        assertTrue(result.contains("storage_total_bytes=105"))
        assertTrue(result.contains("storage_subscription_routing_bytes=20"))
        assertTrue(result.contains("storage_diagnostics_bytes=40"))
        assertTrue(result.contains("storage_generation_directories=2"))
        assertTrue(result.contains("storage_truncated=false"))
        assertFalse(result.contains("private-token"))
        assertFalse(result.contains("secret.xml"))
        assertEquals(before, files.keys.associateWith { File(root, it).readBytes().toList() })
    }

    @Test fun boundsTraversalAndReportsIncompleteSnapshot() {
        val root = temporary.newFolder()
        repeat(5) { File(root, "file-$it").writeText("test") }
        assertTrue(StorageDiagnostic.report(root, 2).contains("storage_truncated=true"))
    }
}
