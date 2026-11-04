package com.example.data.network

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Interface providing authentication tokens for the Cloudflare Worker API.
 * Enables zero-dependency mocking in unit tests and local fixtures.
 */
interface WorkerTokenProvider {
    suspend fun getIdToken(forceRefresh: Boolean = false): String?
}

/**
 * Production Firebase Authentication token provider.
 * Extracts the cryptographically signed RS256 Firebase ID token from the current user session.
 */
class FirebaseAuthTokenProvider(
    private val authProvider: () -> FirebaseAuth? = {
        try {
            FirebaseAuth.getInstance()
        } catch (_: Throwable) {
            null
        }
    }
) : WorkerTokenProvider {
    constructor(auth: FirebaseAuth) : this({ auth })

    override suspend fun getIdToken(forceRefresh: Boolean): String? {
        val auth = authProvider() ?: return null
        val user = auth.currentUser ?: return null
        return try {
            user.getIdToken(forceRefresh).await().token
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Local test token provider for isolated unit testing and local debug vertical slices.
 * Strictly prohibited in release builds.
 */
class LocalMockTokenProvider(
    private var mockToken: String = "mock.debug.jwt.token",
    private val refreshedMockToken: String = "mock.debug.jwt.token.refreshed"
) : WorkerTokenProvider {

    init {
        if (!com.example.BuildConfig.DEBUG) {
            throw SecurityException("LocalMockTokenProvider is strictly prohibited in release builds.")
        }
    }

    override suspend fun getIdToken(forceRefresh: Boolean): String {
        if (!com.example.BuildConfig.DEBUG) {
            throw SecurityException("LocalMockTokenProvider is strictly prohibited in release builds.")
        }
        if (forceRefresh) {
            mockToken = refreshedMockToken
        }
        return mockToken
    }

    fun setToken(token: String) {
        mockToken = token
    }
}
