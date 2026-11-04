package com.example.data.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedResumableSessionStoreTest {

    private lateinit var context: Context
    private lateinit var sessionStore: ResumableSessionStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sessionStore = ResumableSessionStore(context)
        sessionStore.clearAllSessions()
    }

    @After
    fun tearDown() {
        sessionStore.clearAllSessions()
    }

    @Test
    fun test01_roundTripEncryptionAndDecryption() {
        val photoId = "photo_test_123"
        val sessionUri = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=secret_sess_abc123"
        val totalBytes = 1024L * 500L

        sessionStore.saveSession(photoId, sessionUri, totalBytes)

        val retrieved = sessionStore.getSession(photoId)
        assertNotNull(retrieved)
        assertEquals(photoId, retrieved?.photoId)
        assertEquals(sessionUri, retrieved?.sessionUri)
        assertEquals(totalBytes, retrieved?.totalBytes)
        assertTrue((retrieved?.createdAtMillis ?: 0L) > 0L)
    }

    @Test
    fun test02_uniqueIvPerEncryption_producesDifferentCiphertext() {
        val cipher = AesGcmSessionCipher()
        val plaintext = "https://www.googleapis.com/upload/drive/v3/files?upload_id=identical_token"

        val enc1 = cipher.encrypt(plaintext)
        val enc2 = cipher.encrypt(plaintext)

        assertNotEquals(enc1, enc2)
        assertEquals(plaintext, cipher.decrypt(enc1))
        assertEquals(plaintext, cipher.decrypt(enc2))
    }

    @Test
    fun test03_zeroPlaintextUriStoredInSharedPreferences() {
        val photoId = "photo_leak_check"
        val secretToken = "very_sensitive_upload_token_987654321"
        val sessionUri = "https://www.googleapis.com/upload/drive/v3/files?upload_id=$secretToken"

        sessionStore.saveSession(photoId, sessionUri, 1000L)

        val prefs = context.getSharedPreferences(ResumableSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
        val allEntries = prefs.all

        // Ensure raw secretToken is not present in any stored preference value
        for ((key, value) in allEntries) {
            val strVal = value.toString()
            assertTrue("Key $key must not contain secretToken", !key.contains(secretToken))
            assertTrue("Value for $key must not contain secretToken in plaintext", !strVal.contains(secretToken))
            assertTrue("Value for $key must not contain full URI in plaintext", !strVal.contains("https://www.googleapis.com"))
        }
    }

    @Test
    fun test04_corruptedCiphertext_failsGracefullyWithoutCrashing() {
        val photoId = "photo_corrupt"
        val sessionUri = "https://www.googleapis.com/upload/drive/v3/files?upload_id=corrupt_test"

        sessionStore.saveSession(photoId, sessionUri, 2048L)

        // Intentionally tamper with the encrypted value in SharedPreferences
        val prefs = context.getSharedPreferences(ResumableSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("session_enc_uri_$photoId", "corrupted_invalid_base64:corrupted_bytes").apply()

        // Calling getSession should gracefully return null and clear the corrupted session
        val result = sessionStore.getSession(photoId)
        assertNull(result)

        // Verify session was cleared from SharedPreferences
        assertTrue(!prefs.contains("session_enc_uri_$photoId"))
    }

    @Test
    fun test05_authTagMismatch_failsGracefullyWithoutCrashing() {
        val cipher = AesGcmSessionCipher()
        val plaintext = "https://www.googleapis.com/upload/drive/v3/files?upload_id=tamper_test"
        val encrypted = cipher.encrypt(plaintext)

        // Tamper with last character of ciphertext (altering auth tag)
        val tampered = encrypted.dropLast(2) + "=="
        val decrypted = cipher.decrypt(tampered)
        assertNull("Decryption with tampered auth tag must return null", decrypted)
    }

    @Test
    fun test06_differentKeyFailsDecryptionGracefully() {
        val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
        val key1 = keyGen.generateKey()
        val key2 = keyGen.generateKey()

        val cipher1 = AesGcmSessionCipher(fallbackKey = key1)
        val cipher2 = AesGcmSessionCipher(fallbackKey = key2)

        val plaintext = "https://www.googleapis.com/upload/drive/v3/files?upload_id=key_rotation"
        val encryptedWithKey1 = cipher1.encrypt(plaintext)

        val decryptWithKey2 = cipher2.decrypt(encryptedWithKey1)
        assertNull("Decrypting with different key must return null without crashing", decryptWithKey2)
    }

    @Test
    fun test07_clearSessionRemovesEntries() {
        val photoId = "photo_clear_test"
        sessionStore.saveSession(photoId, "https://www.googleapis.com/upload/drive/v3/files?upload_id=clear_me", 100L)
        assertNotNull(sessionStore.getSession(photoId))

        sessionStore.clearSession(photoId)
        assertNull(sessionStore.getSession(photoId))
    }
}
