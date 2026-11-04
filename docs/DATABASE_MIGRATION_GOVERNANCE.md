# ASSETFLOW — DATABASE MIGRATION GOVERNANCE & OFFLINE DATA PRESERVATION POLICY

## 1. Core Principles

AssetFlow is an offline-first physical fixed asset auditing platform. Auditors frequently operate in industrial plants, remote warehouses, and network-isolated facilities for days without internet connectivity. During these periods, physical count verifications and photo evidence records exist **only** on the local device.

1. **Zero Destructive Migration in Production Builders:**
   Under no circumstances may `.fallbackToDestructiveMigration()` or any variant (`fallbackToDestructiveMigrationFrom`, `fallbackToDestructiveMigrationOnDowngrade`) be introduced into production Room database builders (`OfflineSyncDatabase.getInstance()`, etc.).
   
2. **Offline Data is Sacrosanct:**
   User-generated audit verifications, unsynchronized daily batches, and queued photo metadata must never be deleted, cleared, or overwritten by automated software updates.

3. **Mandatory Explicit Migration:**
   Every schema modification or `@Database(version = N)` increment must be accompanied by an explicit, tested `Migration(N-1, N)` object or verified Room `AutoMigration`.

4. **Fail-Closed Semantics:**
   If the Room runtime encounters an unrecognized, future, corrupted, or unmigrated database schema, the application must **fail closed**:
   - The database opening is rejected.
   - The original SQLite file on disk is preserved byte-for-byte.
   - The centralized `OfflineDatabaseGate` enters `OfflineDatabaseStatus.Unavailable`.
   - New submissions and photo captures requiring offline persistence are blocked.
   - Background sync workers and startup recovery are inhibited from accessing the database.
   - A non-technical recovery message is presented to the user instructing them not to clear storage.

---

## 2. Room Schema Export & Version Control

1. **Mandatory Schema Export:**
   All Room `@Database` definitions must declare `exportSchema = true`.
2. **Stable Repository Storage:**
   Room schemas must be exported to a version-controlled directory (`app/schemas/`) via the KSP compiler configuration:
   ```kotlin
   ksp {
       arg("room.schemaLocation", "$projectDir/schemas")
   }
   ```
3. **Immutable Historical Schemas:**
   Once a schema JSON (e.g. `2.json`) is committed to the repository, it represents a published production release contract and must never be altered or deleted.
4. **Historical Version 1 Limitation:**
   Due to legacy `exportSchema = false` configuration, authentic Version 1 schema JSON was not exported. Version 1 is officially documented as unsupported and fails closed to prevent data destruction.

---

## 3. Migration Development & Verification Rules

1. **Populated Historical Database Testing:**
   Every new migration must be validated against a pre-populated SQLite database fixture containing realistic user data, leading-zero string identifiers, special characters, and arbitrary JSON payloads.
2. **Test Automation:**
   Automated unit tests (in `OfflineDatabaseSafetyTest` and `DynamicReliabilityAndMigrationTest`) must assert that:
   - All columns and records from version `N-1` survive to version `N`.
   - Idempotency keys are unaltered.
   - Primary keys and unique constraints are enforced.
   - Nullability and default values match the new Room specification.
3. **Release Gate:**
   Continuous integration and release compilation tasks (`compileReleaseSources`, `testDebugUnitTest`) must verify migration coverage before any APK or AAB artifact is produced for production distribution.

---

## 4. Disposable Test Databases vs. Production

1. **Production Builders:**
   `OfflineSyncDatabase.getInstance(context)` builds the live production database `audit_offline_sync_db` without destructive fallbacks.
2. **Development & Isolation:**
   Disposable test databases in automated unit tests may be created in-memory or deleted between test executions using `context.deleteDatabase(...)`. Development reset routines must never operate inside the production singleton path.
