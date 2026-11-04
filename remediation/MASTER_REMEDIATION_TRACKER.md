# ASSETFLOW — MASTER REMEDIATION TRACKER
**Project:** AssetFlow — Fixed Asset Verification System  
**Backend:** Cloudflare Workers + D1 (`assetflow-control-db` & `assetflow-shard-01`)  
**Frontend:** Android Jetpack Compose + Material 3 + Room + WorkManager  
**Completion Date:** 2026-11-03  
**Overall Status:** `CRITICAL_REMEDIATION_COMPLETE` — AF-AUDIT-001 through AF-AUDIT-005 Implemented & Verified  

---

## 0.4 Critical Remediation Execution Summary (AF-AUDIT-001 through AF-AUDIT-005)
- **Workstream A — AF-AUDIT-001: Master Workbook Import / D1 Authoritative & Firestore Isolation:**
  - **Authoritative Worker D1 Ingestion:** Refactored `AuditRepository.importClientMasterWorkbook()` to make `client.importFarAssets(...)` authoritative. Fails immediately and fast with an informative error if the backend rejects the import.
  - **Room Cache Offline Continuity:** Reconciled imported records into `DynamicOfflineCacheDao.upsertCachedFarBaselines(...)` (`CachedFarBaselineEntity`), ensuring full offline search and counting capability without requiring immediate network re-fetch.
  - **In-Memory Feed Synchronization:** Updated `_cachedFarAssets` StateFlow immediately so observers reflect new imports without manual refresh.
  - **Worker D1 Drive State Alignment:** Queries `client.getMasterWorkbookState(clientId, fy)` to resolve any existing Drive file ID before upload, preventing orphaned file creation.
  - **D1 Lease Lifecycle on Upload:** Upon successful Drive upload, acquires and releases the master sync lease with `COMPLETED` status, `driveFileId`, and `md5Checksum` on Worker D1.
  - **Firestore Permission Isolation:** Wrapped legacy Firestore dual-writes (`col("far_assets")`, `col("master_workbook_state")`, `col("clients")`) in non-fatal `runCatching` blocks with safe logging. Devices operating under non-default org IDs or lacking Firestore roles are no longer aborted by `PERMISSION_DENIED: Missing or insufficient permissions.`.

- **Workstream B — AF-AUDIT-004: Physical Device Debug Worker URL & Emulator Loopback Detection:**
  - **Gradle BuildConfig Configuration:** Updated `app/build.gradle.kts` to safely resolve `WORKER_DEBUG_URL` / `ASSETFLOW_WORKER_DEBUG_URL` and `WORKER_PROD_URL`, enforcing that release builds strictly reject cleartext or loopback (`10.0.2.2`, `localhost`, `127.0.0.1`) endpoints.
  - **Dynamic Diagnostic Guidance:** Added `WorkerApiClient.isConfigured()` and `WorkerApiClient.isEmulatorLoopbackUrl()`.
  - **Physical Device Troubleshooting Hints:** When network operations fail on an endpoint pointing to loopback, `formatNetworkErrorMessage` automatically injects a diagnostic notice instructing the developer to set `WORKER_DEBUG_URL` to their host PC's LAN IP address (e.g., `http://192.168.1.X:8787`).

- **Workstream C — AF-AUDIT-002: PhotoUploadWorker Token Provider Injection & Safe Handling:**
  - **Dynamic Token Provider Injection:** Injected a dynamic `driveTokenProvider: suspend () -> String?` in `AppContainer.kt` via `DynamicWorkerFactory`.
  - **Google Play Services Token Acquisition:** Resolves the active Drive account email from `assetflow_drive_session` SharedPreferences or `FirebaseAuth.currentUser.email` and requests a short-lived token via `GoogleAuthUtil.getToken(context, account, scope)`.
  - **Zero Plaintext Storage:** Stored zero plaintext tokens on disk or in SharedPreferences; tokens are minted ephemerally by Google Play Services.
  - **Retry-Safe Status Handling:** When no token or account is present, `PhotoUploadWorker` transitions the queued photo to `"Sign-in Required"` without incrementing or exhausting retry counts, allowing immediate resumption when the user authorizes Drive.

- **Workstream D — AF-AUDIT-003: User Role, Status, and Membership Migration to Worker D1:**
  - **New Worker Client Endpoints:** Implemented `updateUserRole(targetUid, role)`, `updateUserStatus(targetUid, status)`, and `updateClientMembershipPermissions(clientId, targetUid, ...)` in `WorkerApiClient.kt` mapping to `PATCH /api/v1/users/:uid/role`, `PATCH /api/v1/users/:uid/status`, and `PATCH /api/v1/clients/:clientId/memberships/:uid`.
  - **Authoritative Repository Methods:** Migrated `AuditRepository.saveRoleAssignment` and `AuditRepository.updateAccountStatus` to make Worker D1 authoritative for global role assignment, account status ('Active' | 'Suspended'), and client tenant membership permissions.
  - **Dead Firebase Fallback Removal:** Removed operational dependence on dead `FirebaseFunctionsService.manageUserRoleAndPermissions` and wrapped legacy Firestore writes in non-fatal `runCatching` blocks.
  - **ViewModel Integration:** Wired `AuditViewModel.updateAccountStatus` to launch background coroutines calling `repository.updateAccountStatus(targetUid, newStatus)`.

- **Workstream E — AF-AUDIT-005: Approval Workflow Authoritative Worker D1 & Lease Fencing:**
  - **Authoritative Backend Approval:** Refactored `AuditRepository.approveBatchAndSyncMasterWorkbook()` to call `workerClient.approveDailySheet()` authoritatively, failing fast if the backend rejects approval or detects a Separation of Duties violation.
  - **Worker D1 State Resolution:** Queries `workerClient.getMasterWorkbookState(clientId, fy)` to resolve authoritative `masterDriveFileId`, `fileChecksum`, and lease state.
  - **Firestore Permission Isolation:** Wrapped legacy Firestore updates for `daily_batches`, `master_workbook_state`, `clients`, notifications, and activity logs in `runCatching` blocks. A Firestore `PERMISSION_DENIED` rejection never prevents sheet approval or in-place Drive Master Workbook patching.
  - **Fencing & Recovery Preservation:** Preserved AF-006 lease acquisition (`POST .../acquire-lease`) and conditional fenced release (`POST .../release-lease`) on Worker D1 with head revision and MD5 checksum checks.

- **Verification Evidence:**
  - **Critical Remediation Unit Suite:** 10/10 tests passed in `CriticalRemediationAuditTest` covering all five findings and protected module invariants.
  - **Full Android Test Suite:** 239/239 tests passed, 0 failed, 4 skipped (`./gradlew testDebugUnitTest`).
  - **Backend Worker Tests:** 15/15 test files passed, 122/122 unit tests passed (`npm test`).
  - **Backend TypeScript Check:** 0 errors (`tsc --noEmit`).
  - **Protected Modules Integrity:** 6/6 protected modules verified 100% bit-for-bit against SHA-256 baseline hashes.

---

## 0.3 Stage 3 Execution Summary (Evidence & Workbook Integrity AF-003, AF-005, AF-006, AF-007)
- **Workstream A — AF-003: Photo Evidence Attribution:**
  - **Asset ID & UserProfile Snapshot:** Updated `DynamicFormRenderer` and `DynamicCountingFormScreen` to pass the actual selected Asset ID (strictly preserving leading zeros such as `"004092"`) and authenticated `userProfile` snapshot down to `PhotoEvidenceSection`.
  - **Worker D1 Contract Match:** Verified D1 metadata schema fields (`photoId`, `cycleId`, `assetKey`, `assetId`, `driveFileId`, `driveRootFolderId`, `sha256`, `mimeType`, `fileExtension`, `fileSize`, `capturedAt`, `uploadedAt`) match between Android `PhotoEvidenceMetadataDto` and backend `evidenceService.ts`.
  - **Queue Deduplication / Idempotency:** Implemented deduplication in `PhotoEvidenceRepository.enqueueCapturedPhoto` to verify existing queued photo records matching local file path before creating a new entity, preventing duplicate metadata rows during retries or re-saves.
- **Workstream B — AF-005: Google Drive Folder Provisioning Alignment:**
  - **Drive Root Folder Alignment:** Resolved `driveRootFolderId` from client records and fallback lookups in `PhotoUploadWorker`, ensuring Android requests align with Client's designated Drive root folder in D1 Control DB and eliminating 400 validation rejections.
  - **Client-Specific Folder Isolation:** Verified that Drive folder structures (`AssetFlow/[Client Name]/Image Evidence/`, `Master Input File/`, `Daily Input Sheets/`) enforce strict isolation between clients.
  - **Retry Idempotency:** Verified that Google Drive folder provisioning checks for existing folders before creation, avoiding duplicate folders on retries.
- **Workstream C — AF-006: Master Workbook Concurrency Lease:**
  - **Single-Writer Lease Enforcement:** Refactored `AuditRepository.approveBatchAndSyncMasterWorkbook` to acquire the single-writer concurrency lease from Worker D1 (`POST /api/v1/clients/:clientId/master-workbook/acquire-lease`) using `getWorkerClient()`.
  - **Atomic Lease Check & 409 Conflict Abort:** Throws `IllegalStateException` on 409 Conflict or any lease error, immediately aborting without modifying the Master Workbook or marking batches as published.
  - **Lease Release Lifecycle:** Releases lease with `status = "COMPLETED"` and file checksum upon successful patch and upload, and `status = "FAILED"` with error message upon any sync exception.
  - **Protected Engine Intact:** Protected module `ExcelWorkbookEngine.kt` remained 100% untouched.
- **Workstream D — AF-007: Submission Idempotency:**
  - **Idempotency Header Injection:** Injected `Idempotency-Key` HTTP header across mutation endpoints in `WorkerApiClient.kt`:
    - `registerPhotoEvidence`: sends `payload.photoId`
    - `submitDailySheet`: sends `batchId`
    - `submitCount`: sends `submission.idempotencyKey`
  - **End-to-End Idempotency:** Ensured Room queue keys persist across application restarts, preventing duplicate logical entities upon retry or re-sync.

---

## 0.2 Stage 2 Execution Summary (D1 Operational Data Feeds AF-002 & AF-004 Read Bridges)
- **Workstream A — FAR Asset Data Feed (Fix AF-002):**
  - **Firestore-Empty-Collection Starvation Resolved:** Refactored `AuditRepository.observeFarAssets()` to make Cloudflare Worker / D1 the authoritative operational source whenever `workerClient` is present. An empty Firestore collection can no longer starve the UI or block Worker fetching.
  - **Android Read Bridge:** Completed `WorkerApiClient.searchFarAssets()` with support for `bearerTokenOverride`, query search, and cursor-based pagination.
  - **Leading-Zero Preservation:** Kept `assetId` strictly as text across D1 SQLite shards, DTOs (`FarAssetItemDto`), Room entities (`CachedFarBaselineEntity`), and UI domain models (`FARAsset`), preserving values such as `"004092"`.
  - **Room Offline Cache Reconciliation:** Implemented seamless upsert into `DynamicOfflineCacheDao` on successful network fetches, with automatic fallback to Room cached records upon network disconnect or failure.
  - **Feed State Machine:** Introduced `FarDataFeedState` (`Idle`, `Loading`, `Empty`, `Success(count, isOfflineCache)`, `Error(message, statusCode, hasOfflineCache)`) ensuring network errors are strictly distinct from empty datasets.
- **Workstream B — Daily Sheets Data Feed (Fix AF-002):**
  - **Firestore Starvation Eliminated:** Refactored `AuditRepository.observeDailyBatches()` to source batches directly from Worker D1 (`GET /api/v1/clients/:clientId/daily-sheets`).
  - **Multi-Client Scoping & Separation of Duties:** Batches are scoped strictly by client and fiscal year without cross-client leakage. Auditor submissions remain filtered to own sheets while Admins view client-wide review queues.
  - **Feed State Machine:** Introduced `DailySheetsDataFeedState` (`Idle`, `Loading`, `Empty`, `Success(count)`, `Error(message, statusCode)`). Network failure never masquerades as an empty submission list.
- **Workstream C — AF-004 Required Read API Bridges:**
  - **DTOs:** Added `DailySheetDetailDto` and `DailySheetRecordDto` with robust JSON parsing, photo count tracking, and evidence metadata flags.
  - **API Client:** Added `WorkerApiClient.getDailySheetDetail(clientId, batchId)` mapping to `GET /api/v1/clients/:clientId/daily-sheets/:batchId`.
  - **Repository & ViewModel:** Exposed `getDailySheetDetail` in `AuditRepository` and `AuditViewModel`, along with `refreshFarAssets()`, `searchFarAssets(query)`, and `refreshDailyBatches()`.

---

## 0.1 Stage 1 Execution Summary (AF-001, AF-004 Schema Bridges, AF-023 Owner Workflow)
- **Workstream A — Dynamic Schema Integration (AF-001 & AF-004 Bridges):**
  - **`WorkerApiClient` Endpoints:** Implemented `publishSchema()`, `analyzeHeaders()`, and `getActiveSchema()` matching existing Worker routes (`POST /api/v1/clients/:clientId/schemas/publish`, `POST /api/v1/clients/:clientId/schemas/analyze-headers`, `GET /api/v1/clients/:clientId/schemas/active`).
  - **DTO Enhancements & D1 Constraints:** Added `sourceType` (required by D1 CHECK constraint), `promotedFromFar`, `sourceWorksheetName`, `sourceWorksheetIndex`, `headerRowIndex`, and `options` mapping in `DynamicSchemaDto.kt`. Added `AnalyzeHeadersRequestDto` and `HeaderClassificationResultDto`.
  - **Bi-directional Conversion:** Added extension mappers `WorkbookSchemaVersion.toDynamicSchemaDto()` and `DynamicSchemaDto.toWorkbookSchemaVersion()` ensuring full fidelity between Jetpack Compose dynamic forms and D1 SQLite tables (`input_schema_versions`, `input_field_definitions`, `input_field_options`).
  - **Offline Cache Invalidation:** Implemented `DynamicOfflineCacheRepository.invalidateSchemaCache(clientId, cycleId)` to purge Room cache on publication so Auditors receive newly published schemas immediately.
  - **Admin Publishing & Auditor Retrieval:** Migrated `AuditViewModel.publishCurrentDraftSchema()` and `AuditRepository.saveSchemaVersion()` from Firestore to Worker D1 with Room cache invalidation and UI notification.
- **Workstream B — AF-023: Owner Create Client Workflow Fixes:**
  - **Premature Selection & Screen Unmount Fixed:** Removed immediate `_activeClientId.value = created.clientId` mutation from `AuditViewModel.createClientWithDefaultFiscalYear()`. `ClientSelectionScreen` remains mounted, displays green creation success banner, dismisses dialog cleanly, and preserves the user journey: Create Client → Select Client → Select Fiscal Year → Enter Workspace via explicit `selectClientAndFiscalYear()`.
  - **Residual Firestore Write Removed:** Deleted redundant Firestore write (`col("clients")` and `col("fiscal_years")`) following successful Worker client creation in `AuditRepository.createOrUpdateClient()`.
  - **Multi-Client Fiscal Year Retention:** Updated `fetchFiscalYearsFromWorker()` and `observeFiscalYears(clientId)` to merge and retain multi-client fiscal years without cross-client clobbering.

---

## 2. Protected Code Inventory & Hashes

| Protected Module | Repository Relative Path | Baseline SHA-256 Hash | Post-Remediation SHA-256 Hash | Status |
| :--- | :--- | :--- | :--- | :--- |
| `ExcelWorkbookEngine.kt` | `app/src/main/java/com/example/data/excel/ExcelWorkbookEngine.kt` | `dee96c0e5c994dcb09e1684025303a9cbec0e9f92e4bbc112aa643aea4fb3c6a` | `dee96c0e5c994dcb09e1684025303a9cbec0e9f92e4bbc112aa643aea4fb3c6a` | **100% MATCH** |
| `AuditCalculationEngine.kt` | `app/src/main/java/com/example/domain/AuditCalculationEngine.kt` | `970bcd80e3bc1eac737dad6fbece0ce86cbda40b3d9df018f81be3c14d876d1c` | `970bcd80e3bc1eac737dad6fbece0ce86cbda40b3d9df018f81be3c14d876d1c` | **100% MATCH** |
| `AuditModels.kt` | `app/src/main/java/com/example/data/model/AuditModels.kt` | `a9f7517c52c5865cf4b291bcfbf22788faf4ee4c46b450f1faf2864da48c1c33` | `a9f7517c52c5865cf4b291bcfbf22788faf4ee4c46b450f1faf2864da48c1c33` | **100% MATCH** |
| `OfflineSyncDatabase.kt` | `app/src/main/java/com/example/data/offline/OfflineSyncDatabase.kt` | `a20bfc2562d8d0f48af2b8727abdda12b2d9825c039c0d19603afaddc2c62cd0` | `a20bfc2562d8d0f48af2b8727abdda12b2d9825c039c0d19603afaddc2c62cd0` | **100% MATCH** |
| `AdminReviewQueueTab.kt` | `app/src/main/java/com/example/ui/screens/admin/AdminReviewQueueTab.kt` | `bac96ed7916ba22bfb94f9920fdce6db983f6e6164a81ceed9ca44f89cf77d9e` | `bac96ed7916ba22bfb94f9920fdce6db983f6e6164a81ceed9ca44f89cf77d9e` | **100% MATCH** |
| `ClientSelectionScreen.kt` | `app/src/main/java/com/example/ui/screens/ClientSelectionScreen.kt` | `b52f00b0037496775cb2996134e751a6ce1de7a6452c589f72fa882d52ecd50b` | `b52f00b0037496775cb2996134e751a6ce1de7a6452c589f72fa882d52ecd50b` | **100% MATCH** |

---

## 3. Findings Remediation Matrix

| Finding ID | Title & Scope | Affected Modules | Status | Planned Change / Verification Evidence | Deployment Status | Remaining Risk |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **AF-AUDIT-001** | Master Workbook Import / Firestore Dual-Persistence Failure | `AuditRepository.kt`, `AuditViewModel.kt`, `WorkerApiClient.kt` | **VERIFIED_COMPLETE** | Worker D1 `importFarAssets` made authoritative; Room cache reconciled; Drive upload updates D1 state; Firestore dual-writes wrapped in non-fatal `runCatching`. | Verified with `CriticalRemediationAuditTest` | None |
| **AF-AUDIT-002** | `PhotoUploadWorker` Null Token Provider in Container | `AppContainer.kt`, `PhotoUploadWorker.kt`, `AuditViewModel.kt` | **VERIFIED_COMPLETE** | Dynamic token provider injected via `DynamicWorkerFactory`; `GoogleAuthUtil` generates ephemeral tokens; "Sign-in Required" status preserves retries. | Verified with `CriticalRemediationAuditTest` | None |
| **AF-AUDIT-003** | User Role & Permission Migration to Worker D1 | `WorkerApiClient.kt`, `AuditRepository.kt`, `AuditViewModel.kt` | **VERIFIED_COMPLETE** | Implemented `updateUserRole`, `updateUserStatus`, `updateClientMembershipPermissions`; Worker D1 authoritative; removed dead Cloud Functions dependency. | Verified with `CriticalRemediationAuditTest` | None |
| **AF-AUDIT-004** | Physical Device Debug Worker URL Configuration | `app/build.gradle.kts`, `WorkerApiClient.kt` | **VERIFIED_COMPLETE** | Configured `WORKER_DEBUG_URL` & `WORKER_PROD_URL` with safe fallback; loopback detection & physical device LAN IP troubleshooting hints injected. | Verified with `CriticalRemediationAuditTest` | None |
| **AF-AUDIT-005** | Approval Workflow Direct Firestore Writes | `AuditRepository.kt`, `WorkerApiClient.kt` | **VERIFIED_COMPLETE** | Made `approveDailySheet` authoritative on Worker D1; queried `getMasterWorkbookState` from D1; isolated Firestore writes in `runCatching`; preserved lease fencing. | Verified with `CriticalRemediationAuditTest` | None |
| **P0-01** | Client Creation & Listing Disconnect | `ClientSelectionScreen`, `WorkerApiClient`, `AuditRepository`, `routes.ts` | VERIFIED_COMPLETE | Cross-D1 saga with 409 collision prevention; Android wired; remote D1 migration applied. | Deployed to Prod Worker (`0e0f5c45`) | Pending physical device test |
| **P0-02** | Operational Backend Disconnect | `AuditRepository`, `WorkerApiClient`, `routes.ts`, D1 Shards | VERIFIED_COMPLETE | Migrated Sessions, FAR register, Daily Sheets, Master Sync, Access Requests & Deletions to Worker + D1. | Deployed to Prod Worker | None |
| **P0-03** | Android Auto-Backup Security Risk | `data_extraction_rules.xml`, `backup_rules.xml` | VERIFIED_COMPLETE | Explicit XML exclude rules for Room DBs and `audit_photos` implemented. | In Repo | None |
| **P1-01** | Asset Register Batch Import Missing | `routes.ts`, `shardRepository.ts`, `AuditRepository.kt` | VERIFIED_COMPLETE | Implemented `POST /api/v1/clients/:clientId/far/import` & paginated search. 10k performance benchmark verified. | Deployed to Prod Worker | None |
| **P1-02** | Daily Sheet Aggregation & Review Missing | `routes.ts`, `shardRepository.ts`, `AdminReviewQueueTab.kt` | VERIFIED_COMPLETE | Implemented submit, list, review remarks, approve on D1 Shard with Separation of Duties check. | Deployed to Prod Worker | None |
| **P1-03** | Permanent Client/User Deletion Missing | `AuditRepository.kt`, `routes.ts`, `controlRepository.ts`, `shardRepository.ts` | VERIFIED_COMPLETE | Migrated deletion jobs to Worker + D1 Control/Shard audit trail (`deleted_identities`). | Deployed to Prod Worker | None |
| **P1-04** | Master Workbook State in Firestore | `AuditRepository.kt`, `routes.ts`, `shardRepository.ts` | VERIFIED_COMPLETE | Migrated `master_sync_lease` and idempotency ledger to D1 Shard (`0005_master_sync_lease.sql`). | Deployed to Prod Worker | None |
| **P2-01** | Missing Self-Service Password Reset | `AuthScreen.kt` | VERIFIED_COMPLETE | Added "Forgot Password?" dialog with `sendPasswordResetEmail`. | Built in Debug APK | None |
| **P2-02** | Missing POST_NOTIFICATIONS Permission | `AndroidManifest.xml` | VERIFIED_COMPLETE | Declared in manifest; runtime handling safe. | Built in Debug APK | None |
| **P2-03** | Scalability: Register Search Memory Filter | `WorkerApiClient.kt`, `AuditRepository.kt`, Worker search API | VERIFIED_COMPLETE | Implemented cursor-based search API in Worker and wired into repository. | Deployed to Prod Worker | None |
| **P2-04** | Drive Folder Provisioning Guidance | `ClientSelectionScreen.kt`, `AuditRepository.kt` | VERIFIED_COMPLETE | Persisted recoverable Drive provisioning state; surfaced UI notice. | Built in Debug APK | None |
| **P3-01** | Password Visibility Toggle Missing | `AuthScreen.kt` | VERIFIED_COMPLETE | Added visibility toggle to password and confirm password input fields. | Built in Debug APK | None |
| **P3-02** | Registration Lacks Confirm & Terms | `AuthScreen.kt` | VERIFIED_COMPLETE | Added confirm password field, equality validation, and terms acceptance checkbox. | Built in Debug APK | None |
| **P3-03** | Deprecated Compose Icon Warnings | Compose UI screens | VERIFIED_COMPLETE | Migrated to AutoMirrored icons & new MenuAnchorType across all screens. | Built in Debug APK | None |
| **REQ-FNIF-001** | Unlisted Assets ("Found Not In FAR") D1 Persistence & API | `shardRepository.ts`, `routes.ts`, `ClientDtos.kt`, `WorkerApiClient.kt`, `AuditRepository.kt` | **VERIFIED_COMPLETE** | Implemented D1 Shard repository CRUD, `POST`/`GET /api/v1/clients/:clientId/found-not-in-far`, Worker authentication & multi-tenant isolation, Android DTOs and API client methods, authoritative Worker ingestion with offline Room queue fallback and leading-zero preservation. | Verified with `found-not-in-far.test.ts` (Vitest) & `PostRemediationWorkflowTest.kt` (Robolectric) | None |
