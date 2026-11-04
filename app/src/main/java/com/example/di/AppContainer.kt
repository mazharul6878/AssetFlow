package com.example.di

import android.accounts.Account
import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.data.network.FirebaseAuthTokenProvider
import com.example.data.network.WorkerApiClient
import com.example.data.network.WorkerTokenProvider
import com.example.data.offline.DynamicOfflineStore
import com.example.data.offline.DynamicSubmissionDaoAdapter
import com.example.data.offline.OfflineSyncDatabase
import com.example.data.repository.DynamicOfflineCacheRepository
import com.example.data.repository.DynamicSyncRepository
import com.example.data.worker.DynamicSubmissionWorker
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Application-level dependency container providing singleton instances of databases,
 * repositories, network clients, and the WorkManager WorkerFactory.
 */
class AppContainer(private val context: Context) {

    val offlineDatabase: OfflineSyncDatabase by lazy {
        OfflineSyncDatabase.getInstance(context)
    }

    val dynamicOfflineStore: DynamicOfflineStore by lazy {
        DynamicOfflineStore.getInstance(context)
    }

    val daoAdapter: DynamicSubmissionDaoAdapter by lazy {
        DynamicSubmissionDaoAdapter(offlineDatabase.offlineSyncDao(), offlineDatabase)
    }

    val tokenProvider: WorkerTokenProvider by lazy {
        FirebaseAuthTokenProvider()
    }

    val apiClient: WorkerApiClient by lazy {
        WorkerApiClient(
            baseUrl = com.example.BuildConfig.WORKER_BASE_URL,
            tokenProvider = tokenProvider
        )
    }

    val offlineCacheRepository: DynamicOfflineCacheRepository by lazy {
        DynamicOfflineCacheRepository(
            apiClient = apiClient,
            cacheDao = dynamicOfflineStore.cacheDao()
        )
    }

    val syncRepository: DynamicSyncRepository by lazy {
        DynamicSyncRepository(
            daoAdapter = daoAdapter,
            apiClient = apiClient,
            offlineCacheRepository = offlineCacheRepository,
            context = context
        )
    }

    val photoEvidenceRepository: com.example.data.repository.PhotoEvidenceRepository by lazy {
        com.example.data.repository.PhotoEvidenceRepository(
            context = context,
            dao = offlineDatabase.offlineSyncDao()
        )
    }

    val driveTokenProvider: suspend () -> String? = {
        val prefs = context.getSharedPreferences("assetflow_drive_session", Context.MODE_PRIVATE)
        val savedEmail = prefs.getString("connected_drive_account_email", null)
            ?: runCatching { FirebaseAuth.getInstance().currentUser?.email }.getOrNull()
        if (!savedEmail.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val scope = "oauth2:https://www.googleapis.com/auth/drive.file"
                    val account = Account(savedEmail, "com.google")
                    GoogleAuthUtil.getToken(context, account, scope)
                } catch (e: Exception) {
                    Log.w("AppContainer", "Failed to retrieve Google Drive token for $savedEmail: ${e.message}")
                    null
                }
            }
        } else {
            null
        }
    }

    val workerFactory: WorkerFactory by lazy {
        DynamicWorkerFactory(
            apiClient = apiClient,
            daoAdapter = daoAdapter,
            tokenProvider = tokenProvider,
            offlineDatabase = offlineDatabase,
            driveTokenProvider = driveTokenProvider
        )
    }
}

/**
 * Custom WorkerFactory constructing workers with application-scoped dependencies.
 */
class DynamicWorkerFactory(
    private val apiClient: WorkerApiClient,
    private val daoAdapter: DynamicSubmissionDaoAdapter,
    private val tokenProvider: WorkerTokenProvider,
    private val offlineDatabase: OfflineSyncDatabase? = null,
    private val driveTokenProvider: suspend () -> String? = { null }
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            DynamicSubmissionWorker::class.java.name -> {
                DynamicSubmissionWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    apiClient = apiClient,
                    daoAdapter = daoAdapter,
                    tokenProvider = tokenProvider
                )
            }
            com.example.data.worker.PhotoUploadWorker::class.java.name -> {
                val db = offlineDatabase ?: OfflineSyncDatabase.getInstance(appContext)
                com.example.data.worker.PhotoUploadWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    dao = db.offlineSyncDao(),
                    apiClient = apiClient,
                    tokenProvider = driveTokenProvider
                )
            }
            else -> null
        }
    }
}
