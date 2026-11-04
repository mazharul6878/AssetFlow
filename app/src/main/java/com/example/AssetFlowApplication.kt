package com.example

import android.app.Application
import androidx.work.Configuration
import com.example.data.worker.DynamicSyncRecovery
import com.example.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Custom Application class initializing the dependency container, WorkManager custom WorkerFactory,
 * and startup durable synchronization recovery.
 */
class AssetFlowApplication : Application(), Configuration.Provider {

    lateinit var appContainer: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(appContainer.workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        appContainer = AppContainer(this)

        // Durable recovery: inspect Room on startup to guarantee any submissions persisted
        // before process termination, crash, or reboot are automatically scheduled in WorkManager.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dbStatus = com.example.data.offline.OfflineDatabaseGate.initialize(this@AssetFlowApplication)
                if (dbStatus is com.example.data.offline.OfflineDatabaseStatus.Available) {
                    DynamicSyncRecovery.recoverPendingSubmissions(
                        context = this@AssetFlowApplication,
                        dao = dbStatus.database.offlineSyncDao()
                    )
                    com.example.data.worker.PhotoUploadRecovery.recoverPendingPhotos(
                        context = this@AssetFlowApplication,
                        dao = dbStatus.database.offlineSyncDao()
                    )
                }
            } catch (e: Exception) {
                // Safeguard against unhandled exceptions during early startup
            }
        }
    }

    companion object {
        lateinit var instance: AssetFlowApplication
            private set
    }
}
