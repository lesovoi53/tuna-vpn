package com.wireturn.app.data.security

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.map
import java.util.Base64

/** A cryptographic/storage failure must never look like an empty profile list. */
internal class SecretStorageException(cause: Throwable? = null) :
    IllegalStateException("Не удалось открыть защищённые настройки. Данные сохранены; сброс не выполнен.", cause)

internal interface PreferenceCipher {
    fun ensureKey(allowCreate: Boolean)
    fun encrypt(value: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(value: ByteArray, aad: ByteArray): ByteArray
}

/** Encrypt every string, including nested JSON, rollback snapshots and future string keys. */
internal class EncryptedPreferencesCodec(private val cipher: PreferenceCipher) {
    // DataStore can retain/copy an equal snapshot after a no-op, and a slow collector
    // can still hold the previous emission. Keep only the two latest verified values,
    // comparing immutable content rather than object identity. All access is locked.
    private data class VerifiedSnapshot(val raw: Preferences, val plain: Preferences)
    private val verified = ArrayList<VerifiedSnapshot>(2)
    companion object {
        val VERSION = intPreferencesKey("_tuna_secret_storage_version")
        private const val CURRENT_VERSION = 1
        private const val PREFIX = "tuna.enc.v1:"
    }

    fun isEncrypted(source: Preferences): Boolean = when (source.asMap()[VERSION]) {
        null -> false
        CURRENT_VERSION -> true
        else -> throw SecretStorageException()
    }

    fun migrate(source: Preferences): Preferences {
        if (isEncrypted(source)) {
            decode(source) // Validate; never rewrite an already encrypted document with a new key.
            return source
        }
        // A missing marker on a previously encrypted document must not double-encrypt it.
        if (source.asMap().values.any { value ->
                value is String && value.startsWith(PREFIX) ||
                    value is Set<*> && value.any { it is String && it.startsWith(PREFIX) }
            }) throw SecretStorageException()
        return encode(source, allowCreateKey = true)
    }

    @Synchronized fun encode(source: Preferences, allowCreateKey: Boolean = false, previous: Preferences? = null): Preferences = protect {
        val plain = source.toPreferences()
        if (plain.asMap().keys.any { it.name == VERSION.name }) throw SecretStorageException()
        cipher.ensureKey(allowCreateKey)
        val oldPlain = previous?.let(::decode)
        if (oldPlain == plain) return@protect requireNotNull(previous)
        val result = plain.toMutablePreferences()
        for ((key, value) in plain.asMap()) when (value) {
            is String -> {
                val typed = stringPreferencesKey(key.name)
                result[typed] = if (oldPlain?.asMap()?.get(key) == value) previous!![typed]!!
                    else sealVerified(value, key.name, "string")
            }
            is Set<*> -> {
                if (value.any { it !is String }) throw SecretStorageException()
                val typed = stringSetPreferencesKey(key.name)
                result[typed] = if (oldPlain?.asMap()?.get(key) == value) previous!![typed]!!
                    else value.map { sealVerified(it as String, key.name, "set") }.toSet()
            }
        }
        result[VERSION] = CURRENT_VERSION
        val encrypted = result.toPreferences()
        // Every changed ciphertext was round-trip verified; unchanged values came from
        // the verified old snapshot. Only then may DataStore atomically commit the result.
        rememberVerified(encrypted, plain)
        encrypted
    }

    @Synchronized fun decode(source: Preferences): Preferences = protect {
        if (!isEncrypted(source)) throw SecretStorageException()
        cipher.ensureKey(allowCreate = false)
        verifiedPlain(source)?.let { return@protect it }
        val result = source.toMutablePreferences()
        result.remove(VERSION)
        for ((key, value) in source.asMap()) when (value) {
            is String -> result[stringPreferencesKey(key.name)] = open(value, key.name, "string")
            is Set<*> -> {
                if (value.any { it !is String }) throw SecretStorageException()
                result[stringSetPreferencesKey(key.name)] = value.map { open(it as String, key.name, "set") }.toSet()
            }
        }
        result.toPreferences().also { rememberVerified(source, it) }
    }

    private fun verifiedPlain(source: Preferences): Preferences? {
        val index = verified.indexOfFirst { it.raw == source }
        if (index < 0) return null
        val snapshot = verified.removeAt(index)
        verified.add(0, snapshot)
        return snapshot.plain
    }

    private fun rememberVerified(source: Preferences, plain: Preferences) {
        verified.removeAll { it.raw == source }
        // Freeze a copy even if a caller supplied mutable Preferences. Later mutation
        // must not make unverified ciphertext compare equal to a cached raw object.
        verified.add(0, VerifiedSnapshot(source.toPreferences(), plain))
        if (verified.size > 2) verified.removeAt(2)
    }

    private fun aad(key: String, type: String) = "tuna.preferences/v1/$type/$key".toByteArray(Charsets.UTF_8)
    private fun seal(value: String, key: String, type: String) = PREFIX + Base64.getEncoder().encodeToString(
        cipher.encrypt(value.toByteArray(Charsets.UTF_8), aad(key, type)))
    private fun sealVerified(value: String, key: String, type: String): String = seal(value, key, type).also {
        if (open(it, key, type) != value) throw SecretStorageException()
    }
    private fun open(value: String, key: String, type: String): String {
        if (!value.startsWith(PREFIX)) throw SecretStorageException()
        return cipher.decrypt(Base64.getDecoder().decode(value.removePrefix(PREFIX)), aad(key, type)).toString(Charsets.UTF_8)
    }
    private inline fun <T> protect(block: () -> T): T = try { block() }
        catch (failure: SecretStorageException) { throw failure }
        catch (failure: Exception) { throw SecretStorageException(failure) }
}

/** DataStore runs this before reads/edits and atomically commits only a verified result. */
internal class EncryptedPreferencesMigration(private val codec: EncryptedPreferencesCodec) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = !codec.isEncrypted(currentData)
    override suspend fun migrate(currentData: Preferences) = codec.migrate(currentData)
    override suspend fun cleanUp() = Unit
}

/** Existing transactional/CAS helpers see plaintext only in memory, including updateData's result. */
internal class EncryptedPreferencesStore(
    private val raw: DataStore<Preferences>,
    private val codec: EncryptedPreferencesCodec
) : DataStore<Preferences> {
    override val data = raw.data.map(codec::decode)
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        codec.decode(raw.updateData { current -> codec.encode(transform(codec.decode(current)), previous = current) })
}
