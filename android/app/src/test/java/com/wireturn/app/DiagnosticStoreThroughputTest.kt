package com.wireturn.app

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Controlled host microbenchmark; not a measurement of Android battery savings. */
class DiagnosticStoreThroughputTest {
    @Test fun recordFixedWorkloadWithoutDroppingLines() {
        val elapsed = mutableListOf<Long>()
        repeat(3) {
            val directory = Files.createTempDirectory("tuna-log-bench-").toFile()
            val store = RotatingDiagnosticStore(directory, maxFiles = 4, maxFileBytes = 500_000)
            val line = "[Core] fixture connection ${"x".repeat(150)}"
            val start = System.nanoTime()
            repeat(2_000) { store.append(line) }
            elapsed += (System.nanoTime() - start) / 1_000_000
            assertEquals(2_000, store.files().sumOf { it.readLines().size })
            store.close()
            directory.deleteRecursively()
        }
        val output = File("build/acceptance-1.1.48/log-benchmark").apply { mkdirs() }
        File(output, "${System.currentTimeMillis()}.txt").writeText("records=2000 runsMs=$elapsed medianMs=${elapsed.sorted()[1]}\n")
        println("Diagnostic fixed workload: $elapsed ms")
    }
}
