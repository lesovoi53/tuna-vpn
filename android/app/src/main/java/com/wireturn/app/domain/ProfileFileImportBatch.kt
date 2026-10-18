package com.wireturn.app.domain

import com.wireturn.app.data.Profile
import kotlinx.coroutines.CancellationException

internal data class ProfileFileImportBatchResult(
    val totalImported: Int,
    val filesFailed: Int,
    val revealProfileId: String?
)

/** Keep the committed local ID across multiple documents, including partially successful ZIPs. */
internal suspend fun <T> importProfileFiles(
    files: List<T>,
    importFile: suspend (T) -> Pair<ImportResult, List<Profile>>?
): ProfileFileImportBatchResult {
    var total = 0
    var failed = 0
    var revealId: String? = null
    for (file in files) {
        val imported = try {
            importFile(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val result = imported?.first
        if (result == null || result.total == 0) {
            failed++
            continue
        }
        total += result.total
        if (result.skipped > 0) failed++
        if (revealId == null) revealId = imported.second.firstOrNull()?.id
    }
    return ProfileFileImportBatchResult(total, failed, revealId)
}
