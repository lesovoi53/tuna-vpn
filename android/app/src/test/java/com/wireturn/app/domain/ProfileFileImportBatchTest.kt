package com.wireturn.app.domain

import com.wireturn.app.data.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileFileImportBatchTest {
    @Test fun confRevealUsesCommittedLocalIdInsteadOfSourceId() = runBlocking {
        val local = Profile(id = "new-local-id", name = "AWG", subscriptionSourceId = "source-id")
        val batch = importProfileFiles(listOf("awg.conf")) {
            ImportResult(added = 1, total = 1) to listOf(local)
        }
        assertEquals(ProfileFileImportBatchResult(1, 0, "new-local-id"), batch)
    }

    @Test fun multipleFilesAndPartialZipRevealFirstSuccessfulProfile() = runBlocking {
        val batch = importProfileFiles(listOf("bad.conf", "profiles.zip", "another.conf", "unreadable")) { file ->
            when (file) {
                "bad.conf" -> ImportResult() to emptyList()
                "profiles.zip" -> ImportResult(added = 2, total = 2, skipped = 1) to
                    listOf(Profile(id = "zip-first-local", name = "First"), Profile(id = "zip-second-local", name = "Second"))
                "another.conf" -> ImportResult(added = 1, total = 1) to listOf(Profile(id = "last-local", name = "Last"))
                else -> throw java.io.IOException("Synthetic unavailable document")
            }
        }
        assertEquals(ProfileFileImportBatchResult(3, 3, "zip-first-local"), batch)
    }

    @Test fun failedImportsDoNotRevealAnExistingOrSourceProfile() = runBlocking {
        val batch = importProfileFiles(listOf("bad.conf", "missing")) {
            if (it == "missing") null else ImportResult(skipped = 1) to listOf(Profile(id = "not-committed", name = "Invalid"))
        }
        assertEquals(ProfileFileImportBatchResult(0, 2, null), batch)
    }

    @Test fun cancellationStopsTheBatchWithoutImportingLaterDocuments() {
        var calls = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                importProfileFiles(listOf("cancelled", "later")) {
                    calls++
                    throw CancellationException("Synthetic cancellation")
                }
            }
        }
        assertEquals(1, calls)
    }
}
