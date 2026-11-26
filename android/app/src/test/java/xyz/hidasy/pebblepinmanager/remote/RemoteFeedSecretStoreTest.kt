package xyz.hidasy.pebblepinmanager.remote

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class RemoteFeedSecretStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun generateTestKey(): SecretKey {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return generator.generateKey()
    }

    @Test
    fun `aes gcm cipher roundtrips with randomized IV and detects tampering`() {
        val key = generateTestKey()
        val cipher = AesGcmSecretCipher(keyProvider = { key })
        val plaintext = "super-secret-bearer-token-123".encodeToByteArray()

        val encrypted1 = cipher.encrypt(plaintext)
        val encrypted2 = cipher.encrypt(plaintext)

        assertFalse("Each encryption must use a fresh random IV", encrypted1.contentEquals(encrypted2))
        assertArrayEquals(plaintext, cipher.decrypt(encrypted1))
        assertArrayEquals(plaintext, cipher.decrypt(encrypted2))

        val tampered = encrypted1.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 0xFF).toByte()
        val tamperResult = runCatching { cipher.decrypt(tampered) }
        assertTrue("Tampered ciphertext must fail GCM authentication", tamperResult.isFailure)
    }

    @Test
    fun `encrypted datastore secret store encrypts at rest and does not write plaintext token`() = runTest {
        val key = generateTestKey()
        val file = File(tempFolder.newFolder(), "${EncryptedDataStoreRemoteFeedSecretStore.STORE_NAME}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = EncryptedDataStoreRemoteFeedSecretStore(
            dataStore = dataStore,
            cipher = AesGcmSecretCipher(keyProvider = { key }),
        )

        val secretToken = "tok_live_very_secret_value_987654"
        store.putSecret("feed-1", RemoteFeedSecret(token = secretToken))

        val loaded = store.getSecret("feed-1")
        assertEquals(secretToken, loaded?.token)
        assertEquals(setOf("feed-1"), store.observeFeedIdsWithSecrets().first())

        val rawBytes = file.readBytes().decodeToString()
        assertFalse("Raw DataStore file must never contain plaintext secret", rawBytes.contains(secretToken))
        assertNotEquals("", rawBytes)

        store.deleteSecret("feed-1")
        assertNull(store.getSecret("feed-1"))
        assertTrue(store.observeFeedIdsWithSecrets().first().isEmpty())
    }
}
