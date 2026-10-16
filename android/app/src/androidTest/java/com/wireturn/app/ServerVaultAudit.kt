package com.wireturn.app

import android.content.Context
import com.wireturn.app.server.*
import java.io.File

/** Synthetic credentials only; isolated records are removed in finally. */
object ServerVaultAudit {
    fun run(context: Context): String {
        val store = ServerCredentialStore(context)
        val before = store.read()
        val one = SavedServer(name = "QA vault password", host = "example.invalid", username = "synthetic", password = "synthetic-secret-never-real-8951")
        val two = SavedServer(name = "QA vault key", host = "key.example.invalid", username = "synthetic", useKey = true,
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nsynthetic-only\n-----END OPENSSH PRIVATE KEY-----", keyPassphrase = "synthetic-passphrase-5469")
        try {
            store.save(one); store.save(two)
            val disk = File(context.noBackupFilesDir, "server-access-v1.enc").readText()
            check(!disk.contains(one.password) && !disk.contains(one.host) && !disk.contains(two.keyPassphrase) && !disk.contains("PRIVATE KEY"))
            val restored = ServerCredentialStore(context).read()
            check(restored.single { it.id == one.id }.password == one.password)
            check(restored.single { it.id == two.id }.privateKey == two.privateKey)
            val pin = ServerIdentity.sshFingerprint(byteArrayOf(5, 7, 9))
            store.trust(one.id, one.host, one.port, pin)
            store.save(one.copy(name = "Edited"))
            check(store.read().single { it.id == one.id }.hostFingerprint == pin)
            store.save(one.copy(host = "changed.example.invalid"))
            check(store.read().single { it.id == one.id }.hostFingerprint.isBlank())
            store.delete(one.id)
            check(store.read().any { it.id == two.id })
            check(runCatching { store.save(two.copy(privateKey = "bad")) }.isFailure)
            check(store.read().single { it.id == two.id }.privateKey == two.privateKey)
        } finally { store.delete(one.id); store.delete(two.id) }
        check(store.read() == before)
        return "vault_encrypted_roundtrip_key_password_pin_edit_delete_preservation_pass"
    }
}
