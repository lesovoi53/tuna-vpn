package com.wireturn.app.domain

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.KernelConfigAdapter
import com.wireturn.app.data.Profile
import com.wireturn.app.data.Subscription
import com.wireturn.app.data.SubscriptionImportIssue
import com.wireturn.app.data.SubscriptionImportReport
import com.wireturn.app.data.kernel.VpnConfig
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import java.util.Base64

class SubscriptionImportReportTest {
    private val gson = GsonBuilder().registerTypeAdapter(KernelConfig::class.java, KernelConfigAdapter()).create()

    @Test fun rejectedLegacyNeighborsGetSafeNamesAndReasonsWithoutChangingAcceptedOrder() {
        val document = """[
            {"id":"a","name":"Accepted A","uri":"naive+https://fixture:password@fixture.example:443"},
            {"id":"b","name":"Unsupported","uri":"unknown://fixture:secret@fixture.example:1234#hidden"},
            {"id":"c","name":"Broken WG","uri":"wg://fixture.example:51820?private_key=bad"},
            {"id":"d","name":"Accepted D","uri":"naive+https://fixture:password@fixture.example:8443"}
        ]"""
        val parsed = LegacyProfileImport.parse(document, gson)
        assertEquals(listOf("a", "d"), parsed.profiles.map { it.id })
        assertEquals(2, parsed.skippedEntries)
        val issues = parsed.importIssues.orEmpty()
        assertEquals(listOf("Unsupported", "Broken WG"), issues.map { it.name })
        assertEquals("Протокол не поддерживается", issues[0].reason)
        assertEquals("wg", issues[1].protocol)
        val report = gson.toJson(SubscriptionImportReport(rejected = issues))
        assertFalse(report.contains("private_key"))
        assertFalse(report.contains("fixture.example"))
        assertFalse(report.contains("secret"))
    }

    @Test fun filteredVersionedDocumentReportsEachRejectedEntryAndKeepsSourceUntouched() {
        val source = JsonParser.parseString("""{"profiles":[
            {"id":"a","name":"Good","uri":"naive+https://fixture:password@fixture.example:443"},
            {"id":"b","name":"Unavailable","uri":"unsupported://secret@fixture.example"}]}""").asJsonObject
        val issues = mutableListOf<SubscriptionImportIssue>()
        val (filtered, skipped) = SubscriptionEntryFilter.filterDocument(source) { entry, index ->
            issues += SubscriptionImportIssue.json(entry, index)
        }
        assertEquals(1, skipped)
        assertEquals("Unavailable", issues.single().name)
        assertEquals(2, source.getAsJsonArray("profiles").size())
        assertEquals("a", filtered.getAsJsonArray("profiles").single().asJsonObject["id"].asString)
    }

    @Test fun duplicateAccountsAreReportedSeparatelyAndChosenVariantSurvives() {
        val key = URLEncoder.encode(Base64.getEncoder().encodeToString(ByteArray(32) { 41 }), "UTF-8")
        val uri = "wg://fixture.example:51820?private_key=$key&public_key=$key&local_address=10.0.0.2%2F32&mtu="
        val first = Profile("a", "First variant", KernelConfig.Vpn(requireNotNull(VpnConfig.parse(uri + "1280"))))
        val chosen = Profile("b", "Preferred variant", KernelConfig.Vpn(requireNotNull(VpnConfig.parse(uri + "1420"))))
        val profiles = listOf(first, chosen)
        val report = SubscriptionImportReport(duplicates = AwgProfileDedup.duplicateIssues(profiles, "b"))
        assertTrue(report.rejected.isEmpty())
        assertEquals("First variant", report.duplicates.single().name)
        val kept = AwgProfileDedup.filter(profiles, { it }, "b") { _, profile -> profile }.single()
        assertEquals("b", kept.id)
        assertEquals(listOf((first.kernelConfig as KernelConfig.Vpn).config.uri), (kept.kernelConfig as KernelConfig.Vpn).config.importedVariants)
    }

    @Test fun reportRoundTripAndLegacySubscriptionDoNotLeakSourceCredentials() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 251.toByte() })
        val issue = SubscriptionImportIssue.uri("wg://user:secret@fixture.example?private_key=$key#" + URLEncoder.encode(key, "UTF-8"))
        assertEquals("Профиль без имени", issue.name)
        val sub = Subscription("id", "Name", "https://fixture.example/sub",
            importReport = SubscriptionImportReport(rejected = listOf(issue)))
        val storedReport = gson.toJson(sub.importReport)
        assertFalse(storedReport.contains(key))
        assertFalse(storedReport.contains("secret"))
        assertFalse(storedReport.contains("fixture.example"))
        assertEquals(sub, gson.fromJson(gson.toJson(sub), Subscription::class.java))
        assertNull(gson.fromJson("""{"id":"old","name":"Old","url":"https://fixture.example/sub"}""", Subscription::class.java).importReport)
    }
}
