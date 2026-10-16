package com.wireturn.app.domain

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.util.Base64

/** Transport only: never log payloads or relax malformed group snapshots into ordinary profiles. */
object GroupUriTransport {
    private const val LIMIT = 2 * 1024 * 1024
    val capabilities = setOf("happ-routing-v1", "split-dns-v1", SubscriptionGroups.CAPABILITY)
    data class Envelope(val document: TunaSubscriptionDocument, val ordinaryText: String)

    fun extract(body: String): Envelope? {
        if (body.trimStart().startsWith("{")) return null
        val text = if (body.contains("://")) body else runCatching {
            val compact = body.filterNot(Char::isWhitespace)
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getDecoder().decode(compact))).toString()
        }.getOrNull() ?: return null
        val lines = text.lines()
        val containers = lines.filter { it.trim().startsWith("tuna-groups:", true) }
        if (containers.isEmpty()) return null
        var document: TunaSubscriptionDocument? = null
        for (line in containers) {
            val next = decode(line.trim())
            require(document == null || document.source == next.source) { "Conflicting group snapshots" }
            document = next
        }
        return Envelope(requireNotNull(document), lines.filterNot { it.trim().startsWith("tuna-groups:", true) }.joinToString("\n"))
    }

    fun mergeProfiles(envelope: Envelope, ordinary: List<com.wireturn.app.data.Profile>): List<com.wireturn.app.data.Profile> {
        val groupedUris = envelope.document.profiles.map { it["uri"].asString.substringBefore('#') }.toSet()
        val duplicateIds = envelope.ordinaryText.lines().map(String::trim)
            .filter { it.substringBefore('#') in groupedUris }.map { line ->
                java.util.UUID.nameUUIDFromBytes(line.toByteArray(Charsets.UTF_8)).toString()
            }.toSet()
        val grouped = envelope.document.profiles.map { entry ->
            com.wireturn.app.data.Profile(id = entry["id"].asString, name = entry["name"].asString,
                kernelConfig = requireNotNull(com.wireturn.app.kernel.KernelRegistry.decodeUri(entry["uri"].asString)) {
                    "Unsupported group profile"
                }.second)
        }
        return grouped + ordinary.filterNot { it.id in duplicateIds }
    }

    fun decode(uri: String): TunaSubscriptionDocument = try {
        decodeStrict(uri)
    } catch (error: Exception) {
        // Parser/UTF-8 IO exceptions are malformed input, not a failed HTTP request.
        throw IllegalArgumentException("Invalid group container", error)
    }

    private fun decodeStrict(uri: String): TunaSubscriptionDocument {
        val bare = uri.substringBefore('#')
        require(bare.startsWith("tuna-groups://v1/")) { "Unsupported group container version" }
        val payload = bare.removePrefix("tuna-groups://v1/")
        require(payload.length <= (LIMIT * 4 + 2) / 3 && payload.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid group container encoding or size" }
        val bytes = Base64.getUrlDecoder().decode(payload)
        require(bytes.size <= LIMIT && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == payload) { "Invalid group container encoding or size" }
        val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        require(!json.startsWith('\uFEFF')) { "Invalid group JSON" }
        // Strict token walk also rejects duplicate keys instead of silently taking the last value.
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            validateValue(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing group JSON data" }
        }
        val doc = TunaSubscriptionDocument.parse(json, capabilities)
        require(doc.source.getAsJsonArray("groups") != null &&
            doc.source.getAsJsonArray("requiredCapabilities")?.any { it.asString == SubscriptionGroups.CAPABILITY } == true) { "Missing group capability or groups" }
        return doc
    }

    private fun validateValue(r: JsonReader, depth: Int) {
        require(depth <= 64) { "Group JSON nesting limit" }
        when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject(); val keys = hashSetOf<String>()
                while (r.hasNext()) { require(keys.add(r.nextName())) { "Duplicate group JSON key" }; validateValue(r, depth + 1) }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> { r.beginArray(); while (r.hasNext()) validateValue(r, depth + 1); r.endArray() }
            JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
            JsonToken.BOOLEAN -> r.nextBoolean()
            JsonToken.NULL -> r.nextNull()
            else -> error("Invalid group JSON")
        }
    }
}
