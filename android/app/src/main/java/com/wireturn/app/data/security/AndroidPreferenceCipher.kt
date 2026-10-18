package com.wireturn.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-scoped Android Keystore key: never exported, no biometric gate for automatic VPN startup. */
internal class AndroidPreferenceCipher : PreferenceCipher {
    companion object {
        private const val ALIAS = "tuna.preferences.aes.v1"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }

    @Volatile private var key: SecretKey? = null

    @Synchronized override fun ensureKey(allowCreate: Boolean) {
        if (key != null) return
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null)
        if (existing != null) {
            key = existing as? SecretKey ?: throw SecretStorageException()
            return
        }
        if (!allowCreate) throw SecretStorageException()
        key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build())
        }.generateKey()
    }

    override fun encrypt(value: ByteArray, aad: ByteArray): ByteArray {
        ensureKey(allowCreate = false)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad)
        check(cipher.iv.size == NONCE_BYTES)
        return cipher.iv + cipher.doFinal(value)
    }

    override fun decrypt(value: ByteArray, aad: ByteArray): ByteArray {
        ensureKey(allowCreate = false)
        require(value.size >= NONCE_BYTES + TAG_BITS / 8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, value.copyOfRange(0, NONCE_BYTES)))
        cipher.updateAAD(aad)
        return cipher.doFinal(value, NONCE_BYTES, value.size - NONCE_BYTES)
    }
}

internal object AppSecretPreferences {
    val codec = EncryptedPreferencesCodec(AndroidPreferenceCipher())
    private val stores = java.util.IdentityHashMap<DataStore<Preferences>, EncryptedPreferencesStore>()
    @Synchronized fun wrap(raw: DataStore<Preferences>): DataStore<Preferences> =
        stores.getOrPut(raw) { EncryptedPreferencesStore(raw, codec) }
}
