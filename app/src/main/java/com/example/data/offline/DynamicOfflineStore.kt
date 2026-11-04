package com.example.data.offline

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Entity caching the exact Dynamic Schema pinned to a specific Client and Audit Cycle.
 */
@Entity(tableName = "cached_schemas")
data class CachedSchemaEntity(
    @PrimaryKey val cacheKey: String, // "$clientId:$auditCycleId"
    val clientId: String,
    val auditCycleId: String,
    val schemaVersionId: String,
    val versionNumber: Int,
    val status: String,
    val sourceWorkbookName: String,
    val uniqueAssetKeyHeader: String,
    val schemaJson: String,
    val cachedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Entity caching the imported FAR asset baseline for a specific Client, Cycle, and Asset Key.
 * Preserves the complete original FAR row JSON representation with arbitrary Master Input Sheet columns.
 */
@Entity(tableName = "cached_far_baselines")
data class CachedFarBaselineEntity(
    @PrimaryKey val cacheKey: String, // "$clientId:$auditCycleId:$assetKey"
    val clientId: String,
    val auditCycleId: String,
    val assetKey: String,
    val assetId: String, // Text, strictly preserving leading zeroes
    val assetDescription: String,
    val location: String,
    val department: String,
    val baselineDataJson: String,
    val rawRowJson: String = "{}", // Complete dynamic key-value representation of all imported columns
    val cachedAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface DynamicOfflineCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedSchema(entity: CachedSchemaEntity)

    @Query("SELECT * FROM cached_schemas WHERE clientId = :clientId AND auditCycleId = :cycleId LIMIT 1")
    suspend fun getCachedSchema(clientId: String, cycleId: String): CachedSchemaEntity?

    @Query("DELETE FROM cached_schemas WHERE clientId = :clientId AND auditCycleId = :cycleId")
    suspend fun deleteCachedSchema(clientId: String, cycleId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedFarBaseline(entity: CachedFarBaselineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedFarBaselines(entities: List<CachedFarBaselineEntity>)

    @Query("SELECT * FROM cached_far_baselines WHERE clientId = :clientId AND auditCycleId = :cycleId AND assetKey = :assetKey LIMIT 1")
    suspend fun getCachedFarBaseline(clientId: String, cycleId: String, assetKey: String): CachedFarBaselineEntity?

    @Query("SELECT * FROM cached_far_baselines WHERE clientId = :clientId AND auditCycleId = :cycleId")
    suspend fun getCachedFarBaselinesForCycle(clientId: String, cycleId: String): List<CachedFarBaselineEntity>

    @Query("DELETE FROM cached_far_baselines WHERE clientId = :clientId")
    suspend fun deleteFarBaselinesForClient(clientId: String)

    @Query("DELETE FROM cached_far_baselines WHERE clientId = :clientId AND auditCycleId = :cycleId")
    suspend fun deleteFarBaselinesForCycle(clientId: String, cycleId: String)
}

/**
 * Additive Room database dedicated strictly to offline schema and FAR baseline caching.
 * Keeps [OfflineSyncDatabase] protected and isolated from cache mutations.
 */
@Database(
    entities = [
        CachedSchemaEntity::class,
        CachedFarBaselineEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class DynamicOfflineStore : RoomDatabase() {
    abstract fun cacheDao(): DynamicOfflineCacheDao

    companion object {
        @Volatile
        private var INSTANCE: DynamicOfflineStore? = null

        /**
         * Migration from version 1 to version 2:
         * Adds [rawRowJson] column to [cached_far_baselines] to store arbitrary Master Input Sheet columns.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cached_far_baselines ADD COLUMN rawRowJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        fun getInstance(context: Context): DynamicOfflineStore {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DynamicOfflineStore::class.java,
                    "dynamic_offline_cache_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
