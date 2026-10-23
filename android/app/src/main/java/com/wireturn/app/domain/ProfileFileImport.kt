package com.wireturn.app.domain

import com.google.gson.Gson
import com.wireturn.app.data.KernelConfig
import com.wireturn.app.data.Profile
import com.wireturn.app.data.ProfileBundle
import com.wireturn.app.data.SubscriptionImportIssue
import com.wireturn.app.data.kernel.AwgUri
import com.wireturn.app.data.variant
import com.wireturn.app.kernel.KernelRegistry
import java.util.UUID

/** Parsing only: persistence, duplicate handling and selection stay in ProfileManager. */
internal object ProfileFileImport {
    fun parse(text: String, gson: Gson, defaultName: String = "Profile"): ProfileBundle {
        val source = text.trim().removePrefix("\uFEFF").trim()
        // A WG configuration is one document, not a list of independent lines.
        if (AwgUri.isIni(source)) return requireNotNull(parseWgProfileImport(source))
        // A damaged JSON document must never be rescued by scanning embedded URI fields.
        if (source.startsWith('{') || source.startsWith('[') || !SubscriptionTextFormat.recognizes(source)) {
            return LegacyProfileImport.parse(source, gson, defaultName)
        }

        val profiles = mutableListOf<Profile>()
        val issues = mutableListOf<SubscriptionImportIssue>()
        var skipped = 0
        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val bundle = LegacyProfileImport.itemOrNull {
                if (line.substringBefore("://").lowercase() in setOf("tuna", "wireturn", "wt") && "://" in line) {
                    val decoded = requireNotNull(ProfileEncoder.decode(line.substringAfter("://"))) {
                        "Invalid or oversized profile envelope"
                    }
                    LegacyProfileImport.parse(decoded, gson, defaultName)
                } else parseWgProfileImport(line) ?: run {
                    // Match JSON's supported URI boundary; file import never fetches a URL.
                    val config = requireNotNull(KernelConfig.parseUri(line)) { "Unsupported or invalid profile URI" }
                    val kernel = KernelRegistry.get(config.variant)
                    val profile = Profile(
                        id = UUID.nameUUIDFromBytes(line.toByteArray(Charsets.UTF_8)).toString(),
                        name = kernel.displayNameFromUri(line)?.takeIf(String::isNotBlank) ?: kernel.defaultProfileName,
                        kernelConfig = config,
                        subscriptionUriIdentity = true
                    )
                    ProfileBundle(profiles = listOf(LegacyProfileImport.sanitize(profile, defaultName)))
                }
            }
            profiles += bundle?.profiles.orEmpty()
            skipped += bundle?.skippedEntries ?: 1
            if (bundle == null) issues += SubscriptionImportIssue.uri(line, index = index)
            else issues += bundle.importIssues.orEmpty()
        }
        return ProfileBundle(profiles = profiles, skippedEntries = skipped, importIssues = issues)
    }
}
