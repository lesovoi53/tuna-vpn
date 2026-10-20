package com.wireturn.app.kernel

import android.content.Context
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** A run owns its directory; old cleanup cannot remove a new process's configuration. */
class PrivateRuntimeDirectory(context: Context, prefix: String) : Closeable {
    val directory: File
    init {
        val root = File(context.noBackupFilesDir, "native-runtime")
        check(root.isDirectory || root.mkdirs()) { "Cannot create private runtime" }
        directory = Files.createTempDirectory(root.toPath(), "$prefix-").toFile()
        check(directory.setReadable(false, false) && directory.setReadable(true, true) &&
            directory.setWritable(false, false) && directory.setWritable(true, true) &&
            directory.setExecutable(false, false) && directory.setExecutable(true, true)) { "Cannot protect private runtime" }
    }
    override fun close() {
        MemoryConfigs.removeDirectory(directory)
        check(!directory.exists() || directory.deleteRecursively()) { "Cannot remove private runtime" }
    }
    companion object {
        /** Called once before any app service can start, never on a profile switch. */
        fun removeAbandoned(context: Context) {
            val root = File(context.noBackupFilesDir, "native-runtime")
            root.listFiles()?.forEach { check(it.deleteRecursively()) { "Cannot remove abandoned runtime" } }
            // Exact historical generated files only; do not remove assets or user documents.
            for (path in listOf("hev-socks5-tunnel.yaml", "xray-vpn/config.json", "mieru/launch.json", "mieru/native.json",
                "singbox/config.json", "webdav.json", "masterdns/client.toml", "masterdns/client_resolvers.txt",
                "routing-policy.json", "network-router/extended.json")) {
                val file = File(context.filesDir, path)
                check(!file.exists() || file.delete()) { "Cannot remove legacy runtime configuration" }
            }
            removeAbandonedChecks(context.cacheDir)
            removeMatchingFiles(File(context.filesDir, "network-router"), Regex("extended-[0-9]+\\.json"))
        }

        /** Only generated names from isolated checks; never erase the whole shared cache. */
        internal fun removeAbandonedChecks(cache: File) {
            removeMatchingFiles(cache, Regex("\\.tuna-config-[0-9]+\\.tmp"))
            removeMatchingFiles(cache, Regex("(?:core-check|route-check)-(?:[0-9]+|[0-9a-f-]{36})\\.(?:json|log)"))
            val generatedDirectory = Regex("(?:profile-test|masterdns-scan)-[0-9]+|snell-health-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
            cache.listFiles()?.filter { generatedDirectory.matches(it.name) &&
                Files.isDirectory(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }?.forEach { directory ->
                // walk() does not follow links: even a malicious/stale symlink cannot erase its target.
                Files.walk(directory.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            }
        }

        private fun removeMatchingFiles(root: File, name: Regex) {
            root.listFiles()?.filter { name.matches(it.name) &&
                Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }?.forEach {
                check(it.delete()) { "Cannot remove abandoned check configuration" }
            }
        }
    }
}
