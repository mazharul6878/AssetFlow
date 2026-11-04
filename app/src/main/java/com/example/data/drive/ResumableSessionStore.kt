package com.example.data.drive

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ResumableSessionInfo(
    val photoId: String,
    val sessionUri: String,
    val totalBytes: Long,
    val createdAtMillis: Long
)

/**
 * Interface abstracting session URI encryption for security and testability.
 */
interface SessionCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(encryptedPayload: String): String?
}

/**
 * Keystore-backed AES-256-GCM cipher for protecting Google Drive resumable upload session URIs.
 * Uses a unique 12-byte IV per encryption and 128-bit authentication tag.
 * Automatically falls back to standard JCE in unit-test/Robolectric environments when AndroidKeyStore is absent.
 */
class AesGcmSessionCipher(
    private val keyAlias: String = KEY_ALIAS,
    private val fallbackKey: SecretKey? = null
) : SessionCipher {

    init {
        if (!com.example.BuildConfig.DEBUG && fallbackKey != null) {
            throw SecurityException("Supplying an external fallback key is strictly prohibited in production release builds.")
        }
    }

    companion object {
        const val KEY_ALIAS = "assetflow_drive_session_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    private val secureRandom = SecureRandom()
    private var resolvedKey: SecretKey? = fallbackKey

    @Synchronized
    fun getOrCreateSecretKey(): SecretKey {
        resolvedKey?.let { return it }

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                val keyGen = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    keyAlias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
            val key = keyStore.getKey(keyAlias, null) as SecretKey
            resolvedKey = key
            key
        } catch (e: Exception) {
            // In release / production builds, AndroidKeyStore is strictly required. No software JCE fallback is permitted.
            if (!com.example.BuildConfig.DEBUG) {
                throw SecurityException("AndroidKeyStore is strictly required in production release builds. Insecure test fallback rejected.", e)
            }
            // Environment fallback strictly for Robolectric / pure JVM testing
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256, secureRandom)
            val key = keyGen.generateKey()
            resolvedKey = key
            key
        }
    }

    override fun encrypt(plaintext: String): String {
        val key = getOrCreateSecretKey()
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "$ivBase64:$cipherBase64"
    }

    override fun decrypt(encryptedPayload: String): String? {
        return try {
            val colonIndex = encryptedPayload.indexOf(':')
            if (colonIndex <= 0) return null
            val ivBase64 = encryptedPayload.substring(0, colonIndex)
            val cipherBase64 = encryptedPayload.substring(colonIndex + 1)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipherBytes = Base64.decode(cipherBase64, Base64.NO_WRAP)
            if (iv.size != GCM_IV_LENGTH_BYTES) return null

            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            // Failsafe: return null on any decryption error (auth tag mismatch, altered bytes, wrong key)
            null
        }
    }
}

/**
 * Durable persistence for active Google Drive Resumable Upload sessions with Keystore-backed AES-GCM encryption.
 * Preserves upload session URLs across worker interruptions, network changes, and app restarts
 * without exposing plaintext session URIs in SharedPreferences or logs.
 * Fail-safe recovery: if decryption fails (e.g. key invalidated or data corrupted), clears the session and returns null.
 */
class ResumableSessionStore(
    private val sharedPreferences: SharedPreferences,
    private val cipher: SessionCipher = AesGcmSessionCipher()
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        AesGcmSessionCipher()
    )

    companion object {
        const val PREFS_NAME = "assetflow_drive_resumable_sessions"
        private const val KEY_PREFIX_ENC_URI = "session_enc_uri_"
        private const val KEY_PREFIX_SIZE = "session_size_"
        private const val KEY_PREFIX_TIME = "session_time_"

        @Volatile
        private var INSTANCE: ResumableSessionStore? = null

        fun getInstance(context: Context): ResumableSessionStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResumableSessionStore(context).also { INSTANCE = it }
            }
        }
    }

    @Synchronized
    fun saveSession(photoId: String, sessionUri: String, totalBytes: Long) {
        if (photoId.isBlank() || sessionUri.isBlank()) return
        val encryptedUri = cipher.encrypt(sessionUri)
        sharedPreferences.edit()
            .putString(KEY_PREFIX_ENC_URI + photoId, encryptedUri)
            .putLong(KEY_PREFIX_SIZE + photoId, totalBytes)
            .putLong(KEY_PREFIX_TIME + photoId, System.currentTimeMillis())
            // Remove legacy plaintext key if previously existed
            .remove("session_uri_$photoId")
            .apply()
    }

    @Synchronized
    fun getSession(photoId: String): ResumableSessionInfo? {
        if (photoId.isBlank()) return null
        val encUri = sharedPreferences.getString(KEY_PREFIX_ENC_URI + photoId, null) ?: return null
        val decryptedUri = cipher.decrypt(encUri)
        if (decryptedUri.isNullOrBlank()) {
            // Decryption failure / corrupted record / invalidated key: clear safely and return null
            clearSession(photoId)
            return null
        }
        val size = sharedPreferences.getLong(KEY_PREFIX_SIZE + photoId, 0L)
        val time = sharedPreferences.getLong(KEY_PREFIX_TIME + photoId, 0L)
        return ResumableSessionInfo(
            photoId = photoId,
            sessionUri = decryptedUri,
            totalBytes = size,
            createdAtMillis = time
        )
    }

    @Synchronized
    fun clearSession(photoId: String) {
        if (photoId.isBlank()) return
        sharedPreferences.edit()
            .remove(KEY_PREFIX_ENC_URI + photoId)
            .remove(KEY_PREFIX_SIZE + photoId)
            .remove(KEY_PREFIX_TIME + photoId)
            .remove("session_uri_$photoId")
            .apply()
    }

    @Synchronized
    fun clearAllSessions() {
        sharedPreferences.edit().clear().apply()
    }
}
