package com.wireturn.app.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.*
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedPreferencesTest {
    private val profiles = stringPreferencesKey("profiles_json")
    private val subscriptions = stringPreferencesKey("subscriptions_json")
    private val packages = stringSetPreferencesKey("profile_favorites_v1")
    private val enabled = booleanPreferencesKey("enabled")
    private val counter = intPreferencesKey("counter")
    private fun legacy() = mutablePreferencesOf(
        profiles to "[{\"id\":\"fixture-id\",\"secret\":\"fixture-secret\"}]",
        subscriptions to "https://example.invalid/sub?token=fixture-token",
        stringPreferencesKey("active_kernel_json") to "fixture-runtime-secret",
        stringPreferencesKey("vless_link_history") to "fixture-history-secret",
        stringPreferencesKey("subscription_settings_last_backup") to "fixture-rollback-secret",
        packages to setOf("fixture-favorite", "тест"), enabled to true, counter to 1
    ).toPreferences()

    /** Real AES-GCM with an injectable key lifecycle, without Android Keystore in JVM tests. */
    private class TestCipher(var key: SecretKey? = null) : PreferenceCipher {
        var creations = 0
        var encryptions = 0
        var decryptions = 0
        var failEncryption = false
        var failVerification = false
        override fun ensureKey(allowCreate: Boolean) {
            if (key == null) {
                check(allowCreate) { "key unavailable" }
                key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                creations++
            }
        }
        override fun encrypt(value: ByteArray, aad: ByteArray): ByteArray {
            encryptions++
            check(!failEncryption)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(value)
        }
        override fun decrypt(value: ByteArray, aad: ByteArray): ByteArray {
            decryptions++
            check(!failVerification)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(value, 12, value.size - 12)
        }
    }

    private class AtomicStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        var failCommit = false
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = mutex.withLock {
            val next = transform(data.value)
            currentCoroutineContext().ensureActive()
            check(!failCommit)
            data.value = next
            next
        }
    }

    @Test fun migrationProtectsEveryStringAndRetainsTypesAndSettings() {
        val cipher = TestCipher()
        val codec = EncryptedPreferencesCodec(cipher)
        val source = legacy()
        val migrated = codec.migrate(source)
        assertEquals(source, codec.decode(migrated))
        assertEquals(legacy(), source)
        assertEquals(true, migrated[enabled])
        assertEquals(1, migrated[counter])
        assertFalse(migrated.asMap().toString().contains("fixture-secret"))
        assertFalse(migrated.asMap().toString().contains("fixture-token"))
        assertFalse(migrated.asMap().toString().contains("fixture-runtime-secret"))
        assertFalse(migrated.asMap().toString().contains("fixture-history-secret"))
        assertFalse(migrated.asMap().toString().contains("fixture-rollback-secret"))
        assertFalse(migrated[packages]!!.contains("fixture-favorite"))
        assertEquals(1, cipher.creations)
        assertSame(migrated, codec.migrate(migrated))
        assertEquals(1, cipher.creations)
    }

    @Test fun repeatedWritesUseFreshNoncesAndBindCiphertextToKeyAndType() {
        val codec = EncryptedPreferencesCodec(TestCipher())
        val first = codec.migrate(legacy())
        val second = codec.encode(legacy())
        assertNotEquals(first[profiles], second[profiles])
        val moved = first.toMutablePreferences().apply { this[subscriptions] = first[profiles]!! }
        assertThrows(SecretStorageException::class.java) { codec.decode(moved) }
        val changedType = first.toMutablePreferences().apply {
            remove(profiles)
            this[stringSetPreferencesKey(profiles.name)] = setOf(first[profiles]!!)
        }
        assertThrows(SecretStorageException::class.java) { codec.decode(changedType) }
    }

    @Test fun missingKeyTamperUnknownVersionAndRemovedMarkerFailClosed() {
        val cipher = TestCipher()
        val codec = EncryptedPreferencesCodec(cipher)
        val encrypted = codec.migrate(legacy())
        cipher.key = null
        assertThrows(SecretStorageException::class.java) { codec.decode(encrypted) }
        assertThrows(SecretStorageException::class.java) { codec.migrate(encrypted) }
        assertEquals(1, cipher.creations)
        val fresh = EncryptedPreferencesCodec(TestCipher())
        val original = fresh.migrate(legacy())
        val invalid = original.toMutablePreferences().apply { this[profiles] = original[profiles]!!.dropLast(4) + "AAAA" }
        assertThrows(SecretStorageException::class.java) { fresh.decode(invalid) }
        invalid[EncryptedPreferencesCodec.VERSION] = 99
        assertThrows(SecretStorageException::class.java) { fresh.migrate(invalid) }
        invalid.remove(EncryptedPreferencesCodec.VERSION)
        assertThrows(SecretStorageException::class.java) { fresh.migrate(invalid) }
    }

    @Test fun migrationEncryptionOrVerificationFailureDoesNotCommitOrLosePlaintext() = runBlocking {
        for (verification in listOf(false, true)) {
            val cipher = TestCipher().apply { failEncryption = !verification; failVerification = verification }
            val codec = EncryptedPreferencesCodec(cipher)
            val raw = AtomicStore(legacy())
            try {
                raw.updateData { codec.migrate(it) }
                fail("Expected migration failure")
            } catch (_: SecretStorageException) { }
            assertEquals(legacy(), raw.data.value)
            cipher.failEncryption = false; cipher.failVerification = false
            raw.updateData { codec.migrate(it) }
            assertEquals(legacy(), codec.decode(raw.data.value))
            assertEquals(1, cipher.creations)
        }
    }

    @Test fun atomicEditsReturnPlaintextAndSerializeConcurrentTransactions() = runBlocking {
        val codec = EncryptedPreferencesCodec(TestCipher())
        val raw = AtomicStore(codec.migrate(legacy()))
        val store = EncryptedPreferencesStore(raw, codec)
        coroutineScope {
            repeat(40) { launch(Dispatchers.Default) { store.edit { it[counter] = it[counter]!! + 1 } } }
        }
        assertEquals(41, store.data.first()[counter])
        val result = store.edit { it[profiles] = "fixture-updated-secret" }
        assertEquals("fixture-updated-secret", result[profiles])
        assertEquals("fixture-updated-secret", store.data.first()[profiles])
        assertFalse(raw.data.value.asMap().toString().contains("fixture-updated-secret"))
        assertEquals(legacy()[subscriptions], store.data.first()[subscriptions])
    }

    @Test fun collectorsShareVerifiedSnapshotAndOnlyChangedStringsAreEncrypted() = runBlocking {
        val cipher = TestCipher()
        val writer = EncryptedPreferencesCodec(cipher)
        val initial = writer.migrate(legacy())
        val codec = EncryptedPreferencesCodec(cipher) // Simulate reopening the disk snapshot.
        val raw = AtomicStore(initial)
        val store = EncryptedPreferencesStore(raw, codec)
        cipher.encryptions = 0; cipher.decryptions = 0
        assertEquals(legacy(), store.data.first())
        val firstRead = cipher.decryptions
        assertTrue(firstRead > 0)
        coroutineScope { repeat(20) { launch(Dispatchers.Default) { assertEquals(legacy(), store.data.first()) } } }
        assertEquals(firstRead, cipher.decryptions)
        store.edit { it[counter] = 2 }
        assertEquals(0, cipher.encryptions)
        assertEquals(firstRead, cipher.decryptions)
        store.edit { it[profiles] = "changed-fixture-secret" }
        assertEquals(1, cipher.encryptions)
        assertEquals(firstRead + 1, cipher.decryptions) // Verify only the newly encrypted value.
        assertEquals(initial[subscriptions], raw.data.value[subscriptions])
        assertEquals(initial[packages], raw.data.value[packages])
        assertNotEquals(initial[profiles], raw.data.value[profiles])
        assertEquals("changed-fixture-secret", store.data.first()[profiles])
        assertEquals(firstRead + 1, cipher.decryptions)
    }

    @Test fun failedOrCancelledUpdateRetainsPriorEncryptedSnapshotAndCannotReset() = runBlocking {
        val cipher = TestCipher()
        val codec = EncryptedPreferencesCodec(cipher)
        val before = codec.migrate(legacy())
        val raw = AtomicStore(before)
        val store = EncryptedPreferencesStore(raw, codec)
        raw.failCommit = true
        try { store.edit { it[profiles] = "uncommitted" }; fail("Expected commit failure") }
        catch (_: IllegalStateException) { }
        assertEquals(before, raw.data.value)
        raw.failCommit = false
        val started = CompletableDeferred<Unit>()
        val job = launch {
            store.updateData { started.complete(Unit); awaitCancellation() }
        }
        started.await(); job.cancelAndJoin()
        assertEquals(before, raw.data.value)
        cipher.key = null
        try { store.edit { it.clear() }; fail("Cannot reset unreadable ciphertext") }
        catch (_: SecretStorageException) { }
        assertEquals(before, raw.data.value)
    }

    @Test fun startupNoOpSavesDoNotDecryptAlreadyVerifiedDataStoreSnapshot() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("tuna-secret-noop-test-").toFile()
        val file = File(directory, "fixture.preferences_pb")
        val cipher = TestCipher()
        val initial = EncryptedPreferencesCodec(cipher).migrate(legacy())
        val job = SupervisorJob()
        val raw = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }),
            scope = CoroutineScope(Dispatchers.IO + job))
        try {
            raw.updateData { initial }
            val bytes = file.readBytes()
            val codec = EncryptedPreferencesCodec(cipher)
            val store = EncryptedPreferencesStore(raw, codec)
            cipher.encryptions = 0; cipher.decryptions = 0
            repeat(6) { assertEquals(legacy(), store.data.first()) }
            val initialDecryptions = cipher.decryptions
            assertTrue(initialDecryptions > 0)
            repeat(5) {
                store.edit { preferences -> preferences[counter] = 1 }
                // Real DataStore may retain the previous equal immutable object after a no-op.
                assertEquals(legacy(), store.data.first())
            }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(0, cipher.encryptions)
            println("NOOP_CRYPTO_COUNTS initial=$initialDecryptions afterFiveSaves=${cipher.decryptions}")
            assertEquals("No-op saves must not repeat verified AES-GCM decrypts", initialDecryptions, cipher.decryptions)
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun previousAndCurrentCollectorSnapshotsDoNotEvictEachOther() {
        val cipher = TestCipher()
        val codec = EncryptedPreferencesCodec(cipher)
        val before = codec.migrate(legacy())
        val changedPlain = legacy().toMutablePreferences().apply { this[counter] = 2 }.toPreferences()
        val current = codec.encode(changedPlain, previous = before)
        cipher.encryptions = 0; cipher.decryptions = 0
        // A slow collector can finish the previous emission after a writer publishes
        // the current one. Repeated first() readers may also hold either snapshot.
        repeat(10) {
            assertEquals(legacy(), codec.decode(before))
            assertEquals(changedPlain, codec.decode(current))
        }
        println("ALTERNATING_CRYPTO_COUNTS snapshotReads=20 decryptions=${cipher.decryptions}")
        assertEquals(0, cipher.encryptions)
        assertEquals("Both already verified immutable snapshots must remain reusable", 0, cipher.decryptions)
    }

    @Test fun verifiedCacheDoesNotTrustSubsequentMutationOfCallerSnapshot() {
        val cipher = TestCipher()
        val mutable = EncryptedPreferencesCodec(cipher).migrate(legacy()).toMutablePreferences()
        val reader = EncryptedPreferencesCodec(cipher)
        assertEquals(legacy(), reader.decode(mutable))
        mutable[profiles] = mutable[profiles]!!.dropLast(4) + "AAAA"
        assertThrows(SecretStorageException::class.java) { reader.decode(mutable) }
        // The failed ciphertext must not enter the verified cache either.
        assertThrows(SecretStorageException::class.java) { reader.decode(mutable) }
    }

    @Test fun realDataStoreMigratesFileAndReopensWithoutPlaintextCopies() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("tuna-secret-test-").toFile()
        val file = File(directory, "fixture.preferences_pb")
        val cipher = TestCipher()
        val codec = EncryptedPreferencesCodec(cipher)
        suspend fun <T> session(migrate: Boolean, action: suspend (DataStore<Preferences>) -> T): T {
            val job = SupervisorJob()
            // Android FileStorage uses Linux rename replacement semantics. Okio provides
            // the same atomic replace contract on this Windows JVM test host.
            val raw = PreferenceDataStoreFactory.create(
                storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }),
                migrations = if (migrate) listOf(EncryptedPreferencesMigration(codec)) else emptyList(),
                scope = CoroutineScope(Dispatchers.IO + job))
            return try { action(if (migrate) EncryptedPreferencesStore(raw, codec) else raw) }
            finally { job.cancelAndJoin() }
        }
        try {
            session(false) { raw -> raw.updateData { legacy() } }
            assertTrue(file.readBytes().toString(Charsets.UTF_8).contains("fixture-secret"))
            session(true) { store -> assertEquals(legacy(), store.data.first()) }
            val encryptedBytes = file.readBytes()
            assertFalse(encryptedBytes.toString(Charsets.UTF_8).contains("fixture-secret"))
            assertFalse(encryptedBytes.toString(Charsets.UTF_8).contains("fixture-token"))
            session(true) { store -> assertEquals(legacy(), store.data.first()) }
            assertArrayEquals(encryptedBytes, file.readBytes())
            assertEquals(listOf(file.name), directory.listFiles()!!.map { it.name })
            cipher.key = null
            try { session(true) { it.data.first() }; fail("Missing key must block storage") }
            catch (_: SecretStorageException) { }
            assertArrayEquals(encryptedBytes, file.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
