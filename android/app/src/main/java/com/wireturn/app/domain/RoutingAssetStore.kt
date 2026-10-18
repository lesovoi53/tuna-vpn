package com.wireturn.app.domain

import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import java.util.WeakHashMap

/** Publish a complete checked generation with one atomic pointer. Old readers retain their files. */
internal object RoutingAssetStore {
    // Only a directory created by stage() in this process may be discarded as unpublished.
    // Weak keys also release failed downloads that their callers clean up before publish().
    private val unpublishedStages = WeakHashMap<File, Unit>()
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun current(root: File): File = runCatching {
        val name = AtomicFile(File(root, "current")).openRead().bufferedReader().use { it.readText() }
        require(name.matches(Regex("generation-[a-f0-9-]+")))
        File(root, name).also { require(it.isDirectory) }
    }.getOrDefault(root) // Upgrade from the original flat cache.

    @Synchronized
    fun stage(root: File): File = File(root, "generation-${UUID.randomUUID()}").also {
        check(it.mkdirs())
        unpublishedStages[it] = Unit
    }

    @Synchronized
    fun publish(root: File, stage: File) {
        require(isGeneration(root, stage)) { "Invalid routing asset staging directory" }
        require(unpublishedStages.remove(stage) != null) { "Routing asset directory is not an unpublished stage" }
        val previous = current(root)
        if (previous != stage && isGeneration(root, previous) && sameContents(previous, stage)) {
            // Repeated refreshes frequently download the same databases. Keeping the
            // existing pointer avoids accumulating duplicate generations during a VPN run.
            // Delete only this verified unpublished stage; existing readers are untouched.
            val files = requireNotNull(stage.listFiles())
            check(files.all { it.isFile && !Files.isSymbolicLink(it.toPath()) })
            for (file in files) check(file.delete()) { "Cannot remove duplicate routing asset file" }
            check(stage.delete()) { "Cannot remove duplicate routing asset stage" }
            pruneOldGenerations(root, previous)
            return
        }
        val pointer = AtomicFile(File(root, "current"))
        val out = pointer.startWrite()
        try { out.write(stage.name.toByteArray()); pointer.finishWrite(out) }
        catch (e: Throwable) { pointer.failWrite(out); throw e }
        revision.value++
        pruneOldGenerations(root, stage)
    }

    private fun pruneOldGenerations(root: File, retained: File) {
        // Catalog retention has a guarded policy under GeoAssetsManager's reader mutex.
        // The legacy recursive sweep must not bypass its unknown-content/stage protection.
        if (root.parentFile?.name == "geo_catalog") return
        // Never remove a generation while a tunnel may still be reading it.
        if (com.wireturn.app.CoreServiceState.status.value == com.wireturn.app.CoreStatus.Idle &&
            com.wireturn.app.XrayServiceState.state.value == com.wireturn.app.viewmodel.XrayState.Idle) {
            root.listFiles()?.filter { it.isDirectory && it.name.startsWith("generation-") && it != retained }
                ?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.deleteRecursively() }
        }
    }

    /**
     * Only GeoAssetsManager's private download catalog is eligible during a tunnel run.
     * Its caller must hold the manager mutex, shared with every catalog copy/download.
     * Native kernels consume separate geo_assets/subscription-routing directories.
     */
    @Synchronized
    fun pruneCatalog(root: File) {
        require(root.parentFile?.name == "geo_catalog") { "Not a routing download catalog" }
        if (!root.exists()) return
        require(!Files.isSymbolicLink(root.parentFile.toPath()) && !Files.isSymbolicLink(root.toPath()) && root.isDirectory) {
            "Invalid routing download catalog"
        }
        val retained = current(root)
        // An invalid/missing pointer or a legacy flat cache cannot prove a usable survivor.
        if (!isGeneration(root, retained)) return
        val activeStages = unpublishedStages.keys.map { it.canonicalFile }.toSet()
        val generations = requireNotNull(root.listFiles()) { "Cannot list routing catalog" }
            .filter { isGeneration(root, it) && it.canonicalFile !in activeStages }
        val disposable = generations.filter { directory ->
            val contents = directory.listFiles() ?: error("Cannot list routing catalog generation")
            contents.all { it.name in setOf("geoip.dat", "geosite.dat", "variant") &&
                it.isFile && !Files.isSymbolicLink(it.toPath()) }
        }
        val previous = disposable.filter { directory -> directory != retained &&
            listOf("geoip.dat", "geosite.dat").all { File(directory, it).length() > 0 } }
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name })
        for (directory in disposable) {
            if (directory == retained || directory == previous) continue
            // Direct known regular files only. Never recurse into unexpected user content.
            for (file in requireNotNull(directory.listFiles()) { "Cannot list routing catalog generation" }) {
                check(file.delete()) { "Cannot remove obsolete routing catalog asset" }
            }
            check(directory.delete()) { "Cannot remove obsolete routing catalog generation" }
        }
    }

    private fun isGeneration(root: File, directory: File): Boolean =
        root.isDirectory && !Files.isSymbolicLink(root.toPath()) &&
            directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
            directory.name.matches(Regex("generation-[a-f0-9-]+")) &&
            directory.absoluteFile.normalize().parentFile == root.absoluteFile.normalize() &&
            directory.canonicalFile.parentFile == root.canonicalFile

    private fun sameContents(first: File, second: File): Boolean {
        val left = first.listFiles() ?: return false
        val right = second.listFiles() ?: return false
        if ((left.asSequence() + right.asSequence()).any { !it.isFile || Files.isSymbolicLink(it.toPath()) }) return false
        val rightByName = right.associateBy { it.name }
        if (left.map { it.name }.toSet() != rightByName.keys) return false
        for (file in left) {
            val other = rightByName.getValue(file.name)
            if (file.length() != other.length()) return false
            file.inputStream().use { input -> other.inputStream().use { comparison ->
                val buffer = ByteArray(65536)
                val otherBuffer = ByteArray(buffer.size)
                while (true) {
                    val count = input.read(buffer)
                    if (count != comparison.read(otherBuffer)) return false
                    if (count < 0) break
                    for (index in 0 until count) if (buffer[index] != otherBuffer[index]) return false
                }
            } }
        }
        return true
    }

    suspend fun download(address: String, target: File, progress: (Int) -> Unit = {}) {
        var url = URL(address)
        repeat(6) { attempt ->
            ConfigurationUrlPolicy.validate(url)
            val connection = url.openConnection(activeLocalSocksProxy()) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            try {
                if (connection.responseCode in setOf(301, 302, 303, 307, 308)) {
                    require(attempt < 5) { "Слишком много перенаправлений базы" }
                    val next = ConfigurationUrlPolicy.redirect(url, requireNotNull(connection.getHeaderField("Location")))
                    url = next
                } else {
                    require(connection.responseCode == 200) { "База: HTTP ${connection.responseCode}" }
                    val limit = 128L * 1024 * 1024
                    require(connection.contentLengthLong <= limit) { "Слишком большая база" }
                    var total = 0L
                    connection.inputStream.use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= limit) { "Слишком большая база" }
                            output.write(buffer, 0, n)
                            if (connection.contentLengthLong > 0) progress((total * 100 / connection.contentLengthLong).toInt().coerceIn(0, 100))
                        }
                    } }
                    require(total > 0) { "Пустая база" }
                    return
                }
            } finally { connection.disconnect() }
        }
        error("Не удалось загрузить базу")
    }
}
