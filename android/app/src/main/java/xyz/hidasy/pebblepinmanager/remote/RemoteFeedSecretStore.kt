package xyz.hidasy.pebblepinmanager.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric authenticated encryption contract used to encrypt remote feed credentials at rest.
 */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray

    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * AES-256-GCM cipher framing: `[version=1][ivLen][iv][ciphertext+128bitAuthTag]`.
 *
 * Works both with Android Keystore keys (which generate their own randomized IV during
 * `Cipher.init(ENCRYPT_MODE, key)`) and standard JCE [SecretKey] instances in JVM unit tests.
 */
class AesGcmSecretCipher(
    private val keyProvider: () -> SecretKey,
) : SecretCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val iv = cipher.iv
        require(iv != null && iv.size in 1..255) {
            "Unexpected GCM IV length: ${iv?.size ?: 0}"
        }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(2 + iv.size + encrypted.size).apply {
            this[0] = FORMAT_VERSION
            this[1] = iv.size.toByte()
            iv.copyInto(this, destinationOffset = 2)
            encrypted.copyInto(this, destinationOffset = 2 + iv.size)
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 3) { "Encrypted payload is too short." }
        val version = ciphertext[0]
        require(version == FORMAT_VERSION) { "Unsupported secret cipher version: $version" }
        val ivLen = ciphertext[1].toInt() and 0xFF
        require(ivLen in 12..32 && ciphertext.size > 2 + ivLen) {
            "Malformed encrypted secret framing."
        }
        val iv = ciphertext.copyOfRange(2, 2 + ivLen)
        val encryptedBody = ciphertext.copyOfRange(2 + ivLen, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encryptedBody)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val GCM_TAG_BITS: Int = 128
    }
}

/**
 * Hardware-backed (where available) Android Keystore AES-256-GCM cipher for encrypting remote feed
 * authentication secrets strictly on-device.
 */
object AndroidKeystoreSecretCipherFactory {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pebble_pin_manager_remote_feed_secrets_v1"

    fun create(): SecretCipher = AesGcmSecretCipher(keyProvider = ::getOrCreateSecretKey)

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) {
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}

/**
 * On-device encrypted storage for remote feed auth credentials (Bearer tokens, API keys, HTTP Basic
 * passwords).
 *
 * The backing DataStore file (`datastore/remote_feed_secrets.preferences_pb`) is explicitly
 * excluded from Android Auto Backup and device-to-device transfer in `backup_rules.xml` and
 * `data_extraction_rules.xml`.
 */
interface RemoteFeedSecretStore {
    fun observeFeedIdsWithSecrets(): Flow<Set<String>>

    suspend fun getSecret(feedId: String): RemoteFeedSecret?

    suspend fun putSecret(feedId: String, secret: RemoteFeedSecret)

    suspend fun deleteSecret(feedId: String)

    suspend fun clearAll()
}

class EncryptedDataStoreRemoteFeedSecretStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val json: Json = defaultJson,
) : RemoteFeedSecretStore {

    override fun observeFeedIdsWithSecrets(): Flow<Set<String>> {
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs ->
                prefs.asMap().keys
                    .mapNotNull { key ->
                        val name = key.name
                        if (name.startsWith(KEY_PREFIX)) name.removePrefix(KEY_PREFIX) else null
                    }
                    .toSet()
            }
    }

    override suspend fun getSecret(feedId: String): RemoteFeedSecret? {
        val normalizedId = feedId.trim()
        if (normalizedId.isEmpty()) return null
        val prefs = dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .first()
        val encoded = prefs[secretKeyFor(normalizedId)] ?: return null
        return runCatching {
            val encryptedBytes = Base64.getDecoder().decode(encoded)
            val plaintextBytes = cipher.decrypt(encryptedBytes)
            json.decodeFromString<RemoteFeedSecret>(plaintextBytes.decodeToString())
        }.getOrNull()
    }

    override suspend fun putSecret(feedId: String, secret: RemoteFeedSecret) {
        val normalizedId = feedId.trim()
        if (normalizedId.isEmpty()) return
        if (secret.isBlank()) {
            deleteSecret(normalizedId)
            return
        }
        val plaintextBytes = json.encodeToString(secret).encodeToByteArray()
        val encryptedBytes = cipher.encrypt(plaintextBytes)
        val encoded = Base64.getEncoder().encodeToString(encryptedBytes)
        dataStore.edit { prefs ->
            prefs[secretKeyFor(normalizedId)] = encoded
        }
    }

    override suspend fun deleteSecret(feedId: String) {
        val normalizedId = feedId.trim()
        if (normalizedId.isEmpty()) return
        dataStore.edit { prefs ->
            prefs.remove(secretKeyFor(normalizedId))
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun secretKeyFor(feedId: String): Preferences.Key<String> =
        stringPreferencesKey("$KEY_PREFIX$feedId")

    companion object {
        const val STORE_NAME: String = "remote_feed_secrets"
        private const val KEY_PREFIX: String = "secret_"

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(
            context: Context,
            cipher: SecretCipher = AndroidKeystoreSecretCipherFactory.create(),
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): EncryptedDataStoreRemoteFeedSecretStore {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                appContext.preferencesDataStoreFile(STORE_NAME)
            }
            return EncryptedDataStoreRemoteFeedSecretStore(
                dataStore = store,
                cipher = cipher,
            )
        }
    }
}
