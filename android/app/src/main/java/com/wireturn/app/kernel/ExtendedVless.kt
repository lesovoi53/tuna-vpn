package com.wireturn.app.kernel

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/** Explicit mapping between portable Xray share-link options and extended 2.7.2 JSON. */
object ExtendedVless {
    fun encryption(value: String) {
        if (value == "none") return
        val parts = value.split('.')
        require(parts.size >= 4 && parts[0] == "mlkem768x25519plus" &&
            parts[1] in setOf("native", "xorpub", "random") && parts[2] in setOf("0rtt", "1rtt")) { "Unsupported VLESS encryption" }
        val keys = parts.drop(3).filter { it.length >= 20 }
        require(keys.isNotEmpty() && keys.all { Base64.getUrlDecoder().decode(it).size in setOf(32,1184) }) { "Incomplete VLESS encryption key" }
    }

    private val names = mapOf(
        "xPaddingBytes" to "x_padding_bytes", "noGRPCHeader" to "no_grpc_header", "noSSEHeader" to "no_sse_header",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes", "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
        "scMaxBufferedPosts" to "sc_max_buffered_posts", "scStreamUpServerSecs" to "sc_stream_up_server_secs",
        "serverMaxHeaderBytes" to "server_max_header_bytes", "headers" to "headers",
        "xPaddingObfsMode" to "x_padding_obfs_mode", "xPaddingKey" to "x_padding_key", "xPaddingHeader" to "x_padding_header",
        "xPaddingPlacement" to "x_padding_placement", "xPaddingMethod" to "x_padding_method",
        "uplinkHTTPMethod" to "uplink_http_method", "sessionPlacement" to "session_placement", "sessionIDPlacement" to "session_placement",
        "sessionKey" to "session_key", "sessionIDKey" to "session_key", "seqPlacement" to "seq_placement", "seqKey" to "seq_key",
        "uplinkDataPlacement" to "uplink_data_placement", "uplinkDataKey" to "uplink_data_key", "uplinkChunkSize" to "uplink_chunk_size",
        "sessionIDTable" to "session_id_table", "sessionIDLength" to "session_id_length", "xmux" to "xmux"
    )
    private val muxNames = mapOf("maxConcurrency" to "max_concurrency", "maxConnections" to "max_connections",
        "cMaxReuseTimes" to "c_max_reuse_times", "hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs", "hKeepAlivePeriod" to "h_keep_alive_period")
    fun xhttp(q: Map<String,String>): JsonObject {
        val extra = q["extra"]?.let { JsonParser.parseString(it).also { value -> require(value.isJsonObject) { "XHTTP extra должен быть JSON-объектом" } }.asJsonObject }
        val extraMode = extra?.get("mode")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        require(q["mode"] == null || extraMode == null || q["mode"] == extraMode) { "Конфликт параметров XHTTP: mode" }
        val mode = q["mode"] ?: extraMode ?: "auto"
        require(mode in setOf("auto","packet-up","stream-up","stream-one")) { "Неизвестный режим XHTTP" }
        val output = JsonObject().apply {
            addProperty("type","xhttp"); addProperty("mode",mode)
            addProperty("path",q["path"] ?: "/")
            q["host"]?.let { addProperty("host",it) }
        }
        q["extra"]?.let { raw ->
            val extra = JsonParser.parseString(raw)
            require(extra.isJsonObject) { "XHTTP extra должен быть JSON-объектом" }
            for ((key,value) in extra.asJsonObject.entrySet()) {
                if (key == "mode") continue // Validated against the top-level value above.
                if (key == "downloadSettings") {
                    require(value.isJsonObject) { "downloadSettings должен быть объектом" }
                    output.add("download", download(value.asJsonObject))
                    continue
                }
                val target = names[key] ?: error("Параметр XHTTP $key пока не перенесён в extended; выберите Xray")
                require(!value.isJsonNull) { "XHTTP $key: значение не задано" }
                if (key == "xmux") {
                    require(value.isJsonObject) { "xmux должен быть объектом" }
                    val mux = JsonObject()
                    for ((mk,mv) in value.asJsonObject.entrySet()) {
                        val mt = muxNames[mk] ?: error("Параметр xmux $mk не поддержан адаптером extended")
                        mux.add(mt,mv.deepCopy())
                    }
                    output.add(target,mux)
                } else {
                    require(!output.has(target) || output[target] == value) { "Конфликт параметров XHTTP: $key" }
                    output.add(target,value.deepCopy())
                }
            }
        }
        // Extended validates the raw range before applying its own 100-1000 default.
        if (!output.has("x_padding_bytes")) output.addProperty("x_padding_bytes","100-1000")
        return output
    }
    private fun download(source: JsonObject): JsonObject {
        val allowed = setOf("address", "port", "network", "security", "tlsSettings", "xhttpSettings")
        require(source.keySet().all { it in allowed }) { "downloadSettings: неподдерживаемое поле; используйте Xray" }
        require(source.has("address") && source.has("port")) { "downloadSettings: нужны address и port" }
        require(source["network"]?.asString in setOf(null, "xhttp", "splithttp")) { "Канал загрузки должен использовать XHTTP" }
        val port = source["port"].asString.toIntOrNull()
        require(port != null && port in 1..65535) { "downloadSettings: порт должен быть целым числом от 1 до 65535" }
        val result = JsonObject().apply {
            addProperty("server", source["address"].asString)
            addProperty("server_port", port)
            addProperty("x_padding_bytes", "100-1000")
        }
        source.getAsJsonObject("xhttpSettings")?.let { settings ->
            require(!settings.has("downloadSettings")) { "Вложенный downloadSettings запрещён" }
            val base = JsonObject()
            for ((key, value) in settings.entrySet()) {
                if (key in setOf("path", "host")) result.add(key, value.deepCopy())
                else if (key == "mode") require(value.asString == "auto") { "Режим канала загрузки extended задаётся основным XHTTP" }
                else base.add(key, value.deepCopy())
            }
            val converted = xhttp(mapOf("extra" to base.toString()))
            converted.remove("type"); converted.remove("mode"); converted.remove("path")
            for ((key,value) in converted.entrySet()) result.add(key,value.deepCopy())
        }
        val security = source["security"]?.asString ?: "none"
        require(security in setOf("none", "tls")) { "Защита канала загрузки: используйте Xray для $security" }
        if (security == "tls") {
            val tls = JsonObject().apply { addProperty("enabled",true) }
            source.getAsJsonObject("tlsSettings")?.let { settings ->
                val mapping = mapOf("serverName" to "server_name", "alpn" to "alpn", "minVersion" to "min_version", "maxVersion" to "max_version")
                for ((key,value) in settings.entrySet()) when (key) {
                    "fingerprint" -> tls.add("utls",JsonObject().apply { addProperty("enabled",true); add("fingerprint",value.deepCopy()) })
                    "echConfigList" -> tls.add("ech",ech(value.asString))
                    else -> tls.add(mapping[key] ?: error("download TLS $key: используйте Xray"),value.deepCopy())
                }
            }
            result.add("tls",tls)
        } else require(!source.has("tlsSettings")) { "tlsSettings требует security=tls" }
        return result
    }

    fun ech(value: String): JsonObject {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size in 6..65537 && (((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)) == bytes.size - 2) {
            "Для extended нужен ECH ConfigList в Base64; адрес запроса поддержан через Xray"
        }
        return JsonObject().apply {
            addProperty("enabled",true)
            add("config",com.google.gson.JsonArray().apply { add("-----BEGIN ECH CONFIGS-----\n" + Base64.getEncoder().encodeToString(bytes).chunked(64).joinToString("\n") + "\n-----END ECH CONFIGS-----\n") })
        }
    }
}
