package com.wireturn.app

import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.wireturn.app.server.*

object ServerSshAudit {
    private const val UPGRADE_ID = "99e3453a-055f-4610-b607-001ce57e73bc"
    fun run(context: Context, args: Bundle): String {
        val store = ServerCredentialStore(context)
        when (args.getString("serverAudit")) {
            "prepareUpgrade" -> {
                check(store.read().none { it.id == UPGRADE_ID })
                store.save(SavedServer(id = UPGRADE_ID, name = "QA upgrade synthetic", host = "upgrade.example.invalid", username = "synthetic", password = "synthetic-upgrade-only-52947"))
                return "upgrade_record_saved"
            }
            "verifyUpgrade" -> {
                check(store.read().single { it.id == UPGRADE_ID }.password == "synthetic-upgrade-only-52947")
                store.delete(UPGRADE_ID)
                return "upgrade_record_decrypted_and_removed"
            }
        }
        val port = args.getString("fixturePort")!!.toInt().also { require(it in 1024..65535) }
        val nonce = args.getString("fixtureToken")!!.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val connection = java.net.URL("http://127.0.0.1:$port/fixture").openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5000; connection.readTimeout = 5000
        connection.setRequestProperty("Authorization", "Bearer $nonce")
        val server = try { connection.inputStream.bufferedReader().use { Gson().fromJson(it, SavedServer::class.java) }.validated() } finally { connection.disconnect() }
        val before = store.read()
        require(before.none { it.id == server.id })
        try {
            val pin = runCatching { ServerSsh(context, server.copy(hostFingerprint = "")).use { it.connect() } }.exceptionOrNull()
            check(pin is HostTrustRequired && pin.fingerprint == server.hostFingerprint)
            val mismatch = runCatching { ServerSsh(context, server.copy(hostFingerprint = ServerIdentity.sshFingerprint(byteArrayOf(0)))).use { it.connect() } }.exceptionOrNull()
            check(mismatch is ServerFailure && mismatch.code == "host_changed")
            store.save(server)
            store.trust(server.id, server.host, server.port, server.hostFingerprint)
            val saved = ServerCredentialStore(context).read().single { it.id == server.id }
            return ServerSsh(context, saved).use { ssh ->
                ssh.connect()
                val inspected = ssh.request(JsonObject().apply { addProperty("action", "inspect") })
                val groups = inspected.getAsJsonArray("groups")
                check(groups.size() > 0)
                store.discovered(server.id, server.hostFingerprint, groups.map { it.asJsonObject.get("fingerprint").asString })
                val action = args.getString("serverAudit")
                if (action == "localKey") {
                    check(server.useKey && server.keyPassphrase.isNotEmpty())
                    val wrong = runCatching { ServerSsh(context, server.copy(keyPassphrase = "intentionally-wrong")).use { it.connect() } }.exceptionOrNull()
                    check(wrong is ServerFailure)
                    "encrypted_ed25519_key_accepted_wrong_passphrase_rejected_pins_pass"
                } else if (action == "refresh") {
                    val request = JsonObject().apply { addProperty("action", "refresh"); addProperty("fingerprint", groups[0].asJsonObject.get("fingerprint").asString); addProperty("revision", inspected.get("revision").asString) }
                    val state = try { ssh.request(request).get("state").asString } catch (e: ServerFailure) { e.code }
                    val after = ssh.request(JsonObject().apply { addProperty("action", "inspect") })
                    if (state != "session_verified" && state != "restart_failed") check(after.get("revision").asString == inspected.get("revision").asString)
                    "pins_verified_vault_roundtrip_groups=${groups.size()}_refresh=${state}_revision_preservation_checked"
                } else {
                    ServerBrowserProxy(ssh).use { proxy ->
                        java.net.Socket("127.0.0.1", proxy.port).use { socket ->
                            socket.soTimeout = 20000
                            socket.getOutputStream().write("CONNECT yandex.ru:443 HTTP/1.1\r\nHost: yandex.ru:443\r\n\r\n".toByteArray())
                            val input = socket.getInputStream(); val line = java.io.ByteArrayOutputStream(); var tail = 0
                            while (line.size() < 8192) { val b = input.read(); check(b >= 0); line.write(b); tail = (tail shl 8) or b; if (tail == 0x0d0a0d0a) break }
                            check(line.toString("US-ASCII").startsWith("HTTP/1.1 200"))
                            ((javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(socket, "yandex.ru", 443, true) as javax.net.ssl.SSLSocket).use { tls ->
                                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                                tls.startHandshake()
                                tls.outputStream.write("HEAD / HTTP/1.1\r\nHost: yandex.ru\r\nConnection: close\r\n\r\n".toByteArray())
                                check(tls.inputStream.bufferedReader().readLine().startsWith("HTTP/"))
                            }
                        }
                    }
                    "pins_verified_vault_roundtrip_groups=${groups.size()}_ssh_proxy_tls_pass"
                }
            }
        } finally {
            if (args.getString("serverAudit") != "seed") {
                store.delete(server.id)
                check(store.read() == before)
            }
        }
    }
}
