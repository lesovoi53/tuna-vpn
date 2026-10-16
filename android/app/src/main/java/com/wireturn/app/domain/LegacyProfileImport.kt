package com.wireturn.app.domain

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.wireturn.app.data.Profile
import com.wireturn.app.data.ProfileBundle
import com.wireturn.app.data.KernelConfig

/** Legacy profiles have independent entries; versioned subscription snapshots use their own parser. */
internal object LegacyProfileImport {
    fun parse(json: String, gson: Gson, defaultName: String = "Profile",
        missingId: ((index: Int, total: Int) -> String)? = null): ProfileBundle {
        val element = JsonParser.parseString(json)
        require(element.isJsonArray || element.isJsonObject) { "Invalid legacy profile container" }
        val root = element.takeIf { it.isJsonObject }?.asJsonObject
        // Never interpret a versioned snapshot as a legacy bundle, including unknown schemas.
        require(root?.has("schema") != true) { "Versioned subscriptions require snapshot validation" }
        val isBundle = root?.has("profiles") == true
        val entries = when {
            element.isJsonArray -> element.asJsonArray.toList()
            isBundle -> {
                require(root.get("profiles").isJsonArray) { "Invalid legacy profile list" }
                root.getAsJsonArray("profiles").toList()
            }
            else -> listOf(element)
        }
        if (entries.any { it.isJsonObject && XrayJsonImport.recognizes(it.asJsonObject) }) {
            // Validate the original container before Gson's tree loses duplicate JSON keys.
            com.wireturn.app.kernel.RawXrayConfig.parseContainer(json)
        }
        // Metadata errors invalidate the container. Only independent endpoints are tolerated.
        val metadata = if (isBundle) gson.fromJson(root.deepCopy().apply {
            add("profiles", JsonArray())
        }, ProfileBundle::class.java) else ProfileBundle()
        var skipped = 0
        val issues = mutableListOf<com.wireturn.app.data.SubscriptionImportIssue>()
        val profiles = entries.mapIndexedNotNull { index, entry ->
            itemOrNull {
                require(entry.isJsonObject) { "Invalid profile entry" }
                val fields = entry.asJsonObject
                if (XrayJsonImport.recognizes(fields)) {
                    return@itemOrNull XrayJsonImport.profile(fields, entries)
                }
                val type = fields["kernelConfig"]?.takeUnless { it.isJsonNull }?.asJsonObject?.get("type")
                    ?.takeUnless { it.isJsonNull }?.asString
                require(type == null || type in setOf("masterdnsvpn", "vpn", "webdav", "qwdtt")) {
                    "Unsupported profile kernel"
                }
                fields["uri"]?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
                    require(KernelConfig.parseUri(it) != null) { "Unsupported or invalid profile URI" }
                }
                require(listOf("turnableUrl", "olcrtcUrl", "freeturnUrl").none {
                    fields[it]?.takeUnless { value -> value.isJsonNull }?.asString?.isNotBlank() == true
                }) { "Unsupported legacy kernel" }
                val profile = gson.fromJson(entry, Profile::class.java)
                val sanitized = sanitize(profile, defaultName)
                if ((profile.id as String?).isNullOrBlank() && missingId != null) {
                    sanitized.copy(id = missingId(index, entries.size))
                } else sanitized
            }.also { if (it == null) {
                skipped++
                issues += com.wireturn.app.data.SubscriptionImportIssue.json(entry, index)
            } }
        }
        return metadata.copy(profiles = profiles, skippedEntries = skipped, importIssues = issues)
    }

    fun sanitize(profile: Profile, defaultName: String = "Profile"): Profile =
        profile.sanitize(defaultName).also {
            // Server-only VK configurations are importable before account/hash authorization.
            val importable = when (val kernel = it.kernelConfig) {
                is KernelConfig.Qwdtt -> kernel.config.isImportable()
                else -> !it.isEmpty()
            }
            require(importable || it.wgConfig.isValid() || it.vlessConfig.isValid()) { "Invalid profile endpoint" }
        }

    fun prepare(data: List<Pair<String?, Profile>>, defaultName: String,
        onRejected: (Profile) -> Unit = {}): Pair<List<Pair<String?, Profile>>, Int> {
        var skipped = 0
        val profiles = data.mapNotNull { (file, profile) ->
            itemOrNull { file to sanitize(profile, defaultName) }.also { if (it == null) { skipped++; onRejected(profile) } }
        }
        return profiles to skipped
    }

    /** Only errors in external entry data are recoverable; cancellation and VM failures escape. */
    fun <T : Any> itemOrNull(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        if (e is java.util.concurrent.CancellationException) throw e
        when (e) {
            is JsonParseException, is IllegalArgumentException, is IllegalStateException,
            is NullPointerException, is UnsupportedOperationException -> null
            else -> throw e
        }
    }
}
