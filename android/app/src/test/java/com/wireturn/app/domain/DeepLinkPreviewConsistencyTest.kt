package com.wireturn.app.domain

import org.junit.Assert.*
import org.junit.Test

class DeepLinkPreviewConsistencyTest {
    private fun profile(id: String) = """{"id":"$id","name":"$id","uri":"vless://sample@192.0.2.1:443?security=tls"}"""
    private val removed = """{"id":"old","name":"Removed","kernelConfig":{"type":"olcrtc","config":{}}}"""
    private fun preview(json: String) = previewDeepLink("tuna://" + ProfileEncoder.encode(json))

    @Test fun legacyBundlePreviewNamesAndCountsMatchProfilesToBeImported() {
        val source = """{"name":"Bundle title","profiles":[${profile("one")},${profile("two")},$removed]}"""
        val result = preview(source) as DeepLinkPreview.Profiles
        assertEquals(2, result.count)
        assertEquals(listOf("one", "two"), result.names)
        assertEquals(source, result.json)
    }

    @Test fun unsupportedAndBrokenEntriesDoNotInflateConfirmationCount() {
        val result = preview("[${profile("valid")},$removed,{\"name\":{}},null]") as DeepLinkPreview.Profiles
        assertEquals(1, result.count)
        assertEquals(listOf("valid"), result.names)
    }

    @Test fun unsupportedContainerDoesNotPromiseSuccessfulProfileImport() {
        assertEquals(DeepLinkPreview.Invalid, preview("""{"schema":"future/subscription","profiles":[${profile("one")}]}"""))
        assertEquals(DeepLinkPreview.Invalid, preview("[$removed]"))
    }
}
