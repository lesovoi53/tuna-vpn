package com.wireturn.app.domain

import com.google.gson.JsonParser
import java.net.URI
import java.nio.ByteBuffer
import java.util.Base64

/** Self-contained v1 common routing import. Parsing never changes settings. */
object TunaRoutingLink {
    data class Command(val source: String, val policy: SubscriptionPolicyParser.HappPolicy,
        val apps: AppExclusionsLink.Command?)
    fun recognizes(text: String) = text.trim().startsWith("tuna://routing/", true)

    fun parse(text: String): Command {
        require(text.length <= 350_000) { "Routing link too large" }
        val uri = URI(text.trim())
        require(uri.scheme.equals("tuna", true) && uri.rawAuthority == "routing" &&
            uri.rawPath == "/v1" && uri.rawFragment == null) { "Invalid routing URI" }
        val query = requireNotNull(uri.rawQuery)
        require(query.startsWith("data="))
        val encoded = query.removePrefix("data=")
        require(encoded.isNotEmpty() && encoded.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size <= 256 * 1024) { "Routing document too large" }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded) { "Non-canonical base64url" }
        val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        validateJson(json)
        val root = JsonParser.parseString(json).asJsonObject
        require(root.keySet().all { it in setOf("version", "policy", "apps", "dns") }) { "Unknown routing field" }
        val version = requireNotNull(root.get("version"))
        require(version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.toString() == "1") { "Unsupported routing version" }
        val policyJson = requireNotNull(root.get("policy"))
        require(policyJson.isJsonObject)
        root["dns"]?.let { dns ->
            require(dns.isJsonObject)
            require(!policyJson.asJsonObject.has("TunaDNS") || policyJson.asJsonObject["TunaDNS"] == dns) { "Conflicting DNS settings" }
            policyJson.asJsonObject.add("TunaDNS", dns.deepCopy())
        }
        val source = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(policyJson.toString().toByteArray(Charsets.UTF_8))
        val policy = SubscriptionPolicyParser.parseHapp(source, enabled = true)
        require(!policy.fakeDns && policy.warnings.isEmpty()) { "Unsupported routing options" }
        val apps = root.get("apps")?.let { value ->
            require(value.isJsonObject)
            val app = value.asJsonObject
            require(app.keySet() == setOf("mode", "packages")) { "Unknown app routing field" }
            val mode = requireNotNull(app.get("mode"))
            require(mode.isJsonPrimitive && mode.asJsonPrimitive.isString && mode.asString in setOf("bypass", "vpn"))
            val packages = requireNotNull(app.get("packages"))
            require(packages.isJsonArray && packages.asJsonArray.size() in 1..100)
            val names = packages.asJsonArray.map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString.also { name ->
                    require(name.length <= 255 && name.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                }
            }
            AppExclusionsLink.command("tuna://app-exclusions/v1?mode=${mode.asString}&packages=${names.joinToString(",")}")
                .also { require(it.packages.size == names.size) { "Duplicate app package" } }
        }
        return Command(source, policy, apps)
    }

    // Gson's tree parser otherwise accepts duplicate fields and some non-JSON syntax.
    private fun validateJson(json: String) {
        val reader = com.google.gson.stream.JsonReader(java.io.StringReader(json))
        reader.isLenient = false
        fun value(depth: Int) {
            require(depth <= 16) { "Routing JSON too deeply nested" }
            when (reader.peek()) {
                com.google.gson.stream.JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val names = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        require(names.add(reader.nextName())) { "Duplicate JSON field" }
                        value(depth + 1)
                    }
                    reader.endObject()
                }
                com.google.gson.stream.JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) value(depth + 1)
                    reader.endArray()
                }
                com.google.gson.stream.JsonToken.STRING, com.google.gson.stream.JsonToken.NUMBER -> reader.nextString()
                com.google.gson.stream.JsonToken.BOOLEAN -> reader.nextBoolean()
                com.google.gson.stream.JsonToken.NULL -> reader.nextNull()
                else -> error("Invalid routing JSON")
            }
        }
        reader.use {
            value(0)
            require(reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT)
        }
    }
}
