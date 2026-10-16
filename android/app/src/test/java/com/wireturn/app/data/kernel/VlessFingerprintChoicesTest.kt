package com.wireturn.app.data.kernel

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class VlessFingerprintChoicesTest {
    private val base = "vless://00000000-0000-4000-8000-000000000001@example.com:443?type=tcp&security=reality&fp=edge"

    @Test fun importedFingerprintSurvivesOpeningAndChangingEngine() {
        val draft = VpnEditorDraft.from(base)
        assertEquals(base, draft.toUri())
        for (engine in listOf("sing-box", "xray")) {
            val profile = VpnConfig(draft.toUri(), engine)
            assertEquals("edge", VlessFingerprintChoices.effective(profile))
            assertNotNull(VlessFingerprintChoices.warning(profile))
            assertEquals(base, profile.uri)
        }
        val field = draft.fields.single { it.queryName == "fp" }
        assertEquals(base.replace("fp=edge", "fp=firefox"), draft.change(field.key, "firefox").toUri())
    }

    @Test fun nativeOverrideTakesPrecedenceAndTlsOverrideDoesNotAffectReality() {
        val patch = JsonParser.parseString("""{"streamSettings":{"tlsSettings":{"fingerprint":"chrome"},"realitySettings":{"fingerprint":"qq"}}}""").asJsonObject
        val profile = VpnConfig(base.replace("fp=edge", "fp=firefox"), "xray", VpnOptions().withEngine("xray", patch))
        assertEquals("qq", VlessFingerprintChoices.effective(profile))
        assertNotNull(VlessFingerprintChoices.warning(profile))
        val inherited = profile.copy(options = VpnOptions().withEngine("xray", NativeOptions.put(patch, "streamSettings.realitySettings.fingerprint", null)))
        assertEquals("firefox", VlessFingerprintChoices.effective(inherited))
        assertNull(VlessFingerprintChoices.warning(inherited))
    }

    @Test fun encodedParameterIsIdentifiedWithoutRewritingUri() {
        val uri = base.replace("fp=edge", "f%70=edge")
        val draft = VpnEditorDraft.from(uri)
        assertEquals("edge", draft.fields.single { it.queryName == "fp" }.value)
        assertEquals(uri, draft.toUri())
        assertEquals("chrome", VlessFingerprintChoices.effective(VpnConfig(base.substringBefore("&fp="), "xray")))
    }

    @Test fun nativeSecurityOverrideSelectsTheEffectiveFingerprintField() {
        val patch = JsonParser.parseString("""{"streamSettings":{"security":"reality","realitySettings":{"fingerprint":"firefox"}}}""").asJsonObject
        val profile = VpnConfig(base.replace("security=reality", "security=tls"), "xray", VpnOptions().withEngine("xray", patch))
        assertEquals("streamSettings.realitySettings.fingerprint", VlessFingerprintChoices.nativePath(profile))
        assertEquals("firefox", VlessFingerprintChoices.effective(profile))
        assertNull(VlessFingerprintChoices.warning(profile))
    }
}
