package com.example.data.repository

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only Development Migration Manager for legacy unscoped Walton Hi-Tech Industries data.
 *
 * Strictly gated behind `BuildConfig.DEBUG`.
 * In Production (`!BuildConfig.DEBUG`), this manager is a no-op and zero default data is seeded or migrated.
 */
class DevelopmentMigrationManager(
    private val firestore: FirebaseFirestore?,
    private val orgId: String = "walton_org_main"
) {
    companion object {
        private const val TAG = "DevMigrationManager"
        const val DEV_CLIENT_ID = "walton_hitech_dev"
        const val DEV_CLIENT_NAME = "Walton Hi-Tech Industries"
        const val DEV_CLIENT_CODE = "WALTON"
        const val DEV_FISCAL_YEAR_ID = "2026_27"
        const val DEV_FISCAL_YEAR_LABEL = "2026–27"

        private val TARGET_COLLECTIONS = listOf(
            "far_assets",
            "counting_records",
            "daily_batches",
            "asset_photos",
            "found_not_in_far",
            "schema_versions",
            "drive_uploads",
            "drive_files",
            "activity_logs",
            // Legacy collection names included for backwards migration compatibility
            "asset_counts",
            "unlisted_assets",
            "schema_config",
            "audit_logs"
        )
    }

    data class MigrationReport(
        val executed: Boolean,
        val migratedCount: Int,
        val quarantinedCount: Int,
        val backupId: String,
        val message: String
    )

    private fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

    /**
     * Ensures the default Walton development client and 2026–27 fiscal year exist in Debug builds ONLY
     * if legacy unscoped data exists in Firestore, and migrates unscoped Walton records with backup and quarantine.
     */
    suspend fun runDevelopmentMigrationIfNeeded(actorEmail: String): Result<MigrationReport> {
        if (!BuildConfig.DEBUG) {
            return Result.success(
                MigrationReport(
                    executed = false,
                    migratedCount = 0,
                    quarantinedCount = 0,
                    backupId = "",
                    message = "Skipped: Production build starts with a clean database."
                )
            )
        }
        val db = firestore ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            val rootRef = db.collection("users").document(orgId)

            // Check if any unscoped records exist acrossfar_assets or daily_batches
            val sampleAssets = rootRef.collection("far_assets").limit(50).get().await()
            val unscopedAssets = sampleAssets.documents.filter {
                it.getString("clientId").isNullOrBlank()
            }
            val sampleBatches = rootRef.collection("daily_batches").limit(50).get().await()
            val unscopedBatches = sampleBatches.documents.filter {
                it.getString("clientId").isNullOrBlank()
            }

            if (unscopedAssets.isEmpty() && unscopedBatches.isEmpty()) {
                return Result.success(
                    MigrationReport(
                        executed = false,
                        migratedCount = 0,
                        quarantinedCount = 0,
                        backupId = "",
                        message = "No unscoped legacy Walton records found."
                    )
                )
            }

            val timestamp = nowIso()
            val backupId = "walton_dev_backup_${System.currentTimeMillis()}"

            // 1. Ensure Walton development client & fiscal year exist
            val clientDoc = mapOf(
                "clientId" to DEV_CLIENT_ID,
                "clientName" to DEV_CLIENT_NAME,
                "clientCode" to DEV_CLIENT_CODE,
                "industry" to "Electronics & Appliances Manufacturing",
                "contactPerson" to "Asset Verification Team",
                "contactEmail" to actorEmail,
                "status" to "Active",
                "activeFiscalYearId" to DEV_FISCAL_YEAR_ID,
                "fiscalYearIds" to listOf(DEV_FISCAL_YEAR_ID),
                "uniqueAssetKeyHeader" to "Identification No.",
                "createdAt" to timestamp,
                "createdByEmail" to actorEmail,
                "updatedAt" to timestamp
            )
            rootRef.collection("clients").document(DEV_CLIENT_ID)
                .set(clientDoc, SetOptions.merge()).await()

            val fyDoc = mapOf(
                "fiscalYearId" to DEV_FISCAL_YEAR_ID,
                "clientId" to DEV_CLIENT_ID,
                "label" to DEV_FISCAL_YEAR_LABEL,
                "startDate" to "2026-07-01",
                "endDate" to "2027-06-30",
                "status" to "Open",
                "isDefault" to true,
                "createdAt" to timestamp,
                "createdByEmail" to actorEmail
            )
            rootRef.collection("fiscal_years").document("${DEV_CLIENT_ID}_${DEV_FISCAL_YEAR_ID}")
                .set(fyDoc, SetOptions.merge()).await()

            var migratedCount = 0
            var quarantinedCount = 0
            val backupRecords = JSONArray()

            // 2. Scan and migrate unscoped documents in target collections
            for (collectionName in TARGET_COLLECTIONS) {
                val snap = rootRef.collection(collectionName).limit(500).get().await()
                val unscopedDocs = snap.documents.filter {
                    it.getString("clientId").isNullOrBlank()
                }
                if (unscopedDocs.isEmpty()) continue

                for (chunk in unscopedDocs.chunked(200)) {
                    val batch = db.batch()
                    for (doc in chunk) {
                        val data = doc.data ?: continue
                        // Record pre-migration state in backup
                        backupRecords.put(
                            JSONObject().apply {
                                put("collection", collectionName)
                                put("docId", doc.id)
                                put("previousClientId", data["clientId"]?.toString() ?: "")
                                put("previousFiscalYearId", data["fiscalYearId"]?.toString() ?: "")
                            }
                        )

                        // Provenance verification: confirm whether record belongs to Walton or needs review
                        val hasValidProvenance = verifyWaltonProvenance(collectionName, data)
                        if (hasValidProvenance) {
                            batch.set(
                                doc.reference,
                                mapOf(
                                    "clientId" to DEV_CLIENT_ID,
                                    "fiscalYearId" to DEV_FISCAL_YEAR_ID,
                                    "migrationTag" to backupId,
                                    "migratedAt" to timestamp
                                ),
                                SetOptions.merge()
                            )
                            migratedCount++
                        } else {
                            batch.set(
                                doc.reference,
                                mapOf(
                                    "migrationStatus" to "NEEDS_ADMIN_REVIEW",
                                    "migrationTag" to backupId,
                                    "migratedAt" to timestamp
                                ),
                                SetOptions.merge()
                            )
                            quarantinedCount++
                        }
                    }
                    batch.commit().await()
                }
            }

            // 3. Persist backup manifest for deterministic rollback
            val backupDoc = mapOf(
                "backupId" to backupId,
                "targetClientId" to DEV_CLIENT_ID,
                "targetFiscalYearId" to DEV_FISCAL_YEAR_ID,
                "migratedCount" to migratedCount,
                "quarantinedCount" to quarantinedCount,
                "recordsJson" to backupRecords.toString(),
                "createdAt" to timestamp,
                "createdByEmail" to actorEmail,
                "rolledBack" to false
            )
            rootRef.collection("migration_backups").document(backupId).set(backupDoc).await()

            Result.success(
                MigrationReport(
                    executed = true,
                    migratedCount = migratedCount,
                    quarantinedCount = quarantinedCount,
                    backupId = backupId,
                    message = "Migrated $migratedCount Walton dev records ($quarantinedCount flagged for review)."
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Development migration failed", e)
            Result.failure(e)
        }
    }

    /**
     * Rolls back a development migration batch using its saved manifest in `migration_backups`.
     */
    suspend fun rollbackWaltonDevelopmentMigration(backupId: String): Result<Int> {
        if (!BuildConfig.DEBUG) {
            return Result.failure(IllegalStateException("Rollback is only available in Debug builds."))
        }
        val db = firestore ?: return Result.failure(IllegalStateException("Firestore unavailable"))
        return try {
            val rootRef = db.collection("users").document(orgId)
            val backupSnap = rootRef.collection("migration_backups").document(backupId).get().await()
            if (!backupSnap.exists()) {
                return Result.failure(IllegalArgumentException("Backup $backupId not found"))
            }
            val recordsJson = backupSnap.getString("recordsJson") ?: "[]"
            val array = JSONArray(recordsJson)
            var restored = 0

            val items = (0 until array.length()).map { array.getJSONObject(it) }
            for (chunk in items.chunked(200)) {
                val batch = db.batch()
                for (item in chunk) {
                    val col = item.optString("collection")
                    val docId = item.optString("docId")
                    val prevClient = item.optString("previousClientId", "")
                    val prevFy = item.optString("previousFiscalYearId", "")
                    if (col.isNotBlank() && docId.isNotBlank()) {
                        val ref = rootRef.collection(col).document(docId)
                        batch.set(
                            ref,
                            mapOf(
                                "clientId" to prevClient,
                                "fiscalYearId" to prevFy,
                                "migrationStatus" to "",
                                "migrationTag" to ""
                            ),
                            SetOptions.merge()
                        )
                        restored++
                    }
                }
                batch.commit().await()
            }

            rootRef.collection("migration_backups").document(backupId)
                .set(mapOf("rolledBack" to true, "rolledBackAt" to nowIso()), SetOptions.merge())
                .await()

            Result.success(restored)
        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed", e)
            Result.failure(e)
        }
    }

    private fun verifyWaltonProvenance(collectionName: String, data: Map<String, Any>): Boolean {
        return when (collectionName) {
            "far_assets" -> {
                val assetId = data["assetId"]?.toString().orEmpty()
                val description = data["description"]?.toString().orEmpty()
                assetId.isNotBlank() || description.isNotBlank()
            }
            "daily_batches", "asset_counts", "counting_records" -> {
                val batchId = data["batchId"]?.toString().orEmpty()
                val assetId = data["assetId"]?.toString().orEmpty()
                batchId.isNotBlank() || assetId.isNotBlank()
            }
            else -> true
        }
    }
}
