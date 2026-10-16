package com.wireturn.app

import java.io.File
import java.nio.file.Files

/** On-demand, read-only accounting. Never exports filenames or reads file contents. */
internal object StorageDiagnostic {
    fun report(data: File, maxEntries: Int = 100_000): String {
        require(maxEntries > 0)
        val totals = linkedMapOf<String, Long>()
        var entries = 0
        var unreadable = 0
        var links = 0
        var generations = 0
        var truncated = false
        val pending = java.util.ArrayDeque<Pair<File, String>>()
        pending.add(data to "")
        while (pending.isNotEmpty()) {
            if (entries++ >= maxEntries) { truncated = true; break }
            val (file, relative) = pending.removeLast()
            if (Files.isSymbolicLink(file.toPath())) { links++; continue }
            if (file.isDirectory) {
                if (file.name.matches(Regex("generation-[a-f0-9-]+"))) generations++
                val children = file.listFiles()
                if (children == null) unreadable++
                else children.forEach { child ->
                    pending.add(child to if (relative.isEmpty()) child.name else "$relative/${child.name}")
                }
            } else if (file.isFile) {
                val category = when {
                    relative.startsWith("files/geo_assets/") -> "geo_assets"
                    relative.startsWith("files/geo_catalog/") -> "geo_catalog"
                    relative.startsWith("files/subscription-routing/") -> "subscription_routing"
                    relative.startsWith("no_backup/diagnostics/") -> "diagnostics"
                    relative.startsWith("cache/") -> "cache"
                    relative.startsWith("code_cache/") -> "code_cache"
                    relative.startsWith("databases/") -> "databases"
                    relative.startsWith("shared_prefs/") -> "preferences"
                    else -> "other"
                }
                totals[category] = (totals[category] ?: 0L) + file.length()
            } else unreadable++
        }
        return buildString {
            appendLine("storage_snapshot=logical_bytes; on_demand; concurrent_writes_possible")
            totals.toSortedMap().forEach { (category, bytes) -> appendLine("storage_${category}_bytes=$bytes") }
            appendLine("storage_total_bytes=${totals.values.sum()}")
            appendLine("storage_generation_directories=$generations")
            appendLine("storage_unreadable_entries=$unreadable")
            appendLine("storage_skipped_symlinks=$links")
            appendLine("storage_truncated=$truncated")
        }
    }
}
