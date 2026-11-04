# Cloudflare D1 Backup Design Contract

## 1. Overview
This specification defines the external backup pipeline for AssetFlow Cloudflare D1 databases without compromising field auditor operations or violating cross-tenant data isolation.

## 2. Backup Categorization & Multi-Tenancy Isolation
Three distinct backup types are maintained:

1. **Complete Admin-Controlled Shard Backups:**
   - Full SQL dumps of physical data shards (`SHARD_01` ... `SHARD_09`).
   - Each shard contains data for multiple clients (e.g., 8-16 clients per shard).
   - **CRITICAL SECURITY REQUIREMENT:** Complete multi-client Shard Backups must **NEVER** be placed in an individual client's Google Drive folder. They must only be stored in a secured, system-level Admin Backup Drive repository or Cloudflare R2 bucket.
2. **Complete Control Database Backups:**
   - Full SQL dump of `CONTROL_DB`.
   - Contains global users, tenant configurations, and deletion audit logs.
   - Stored in the system-level Admin repository.
3. **Client-Specific Data Exports:**
   - Filtered, client-scoped Excel workbooks and JSON archives.
   - Contains only rows where `client_id = ?`.
   - Uploaded directly into the client's designated Google Drive folder (`/AssetFlow/[Client Name]/Master Files/`).

## 3. D1 Export API Mechanics and Lock Window Limitation
- **Export Trigger:** `POST https://api.cloudflare.com/client/v4/accounts/{account_id}/d1/database/{database_id}/export`
- **Lock Window Limitation:** While Cloudflare D1 operates on SQLite WAL mode, executing an export places an ephemeral snapshot read lock across database pages.
- **Operational Rule:** Scheduled backups must **ONLY** execute during off-hours low-activity maintenance windows (e.g., 02:00 AM local audit time) via an external scheduled runner (GitHub Actions / Cloudflare Workflow).
- **Execution CPU Limits:** Workers Free Tier enforces a strict 10ms-50ms CPU limit. Export streaming must be orchestrated via Cloudflare's asynchronous Export REST API, where Cloudflare generates the dump artifact into a signed R2 URL, rather than streaming 500 MB inside a standard Worker request.

## 4. Verification and Checksums
- Every backup file must have its SHA-256 checksum calculated before and after transmission.
- Checksums and byte counts are permanently logged in `shard_metadata` (`last_backup_at`) and verified before any restore operation.

## 5. Official References
- Cloudflare D1 Database Export API: https://developers.cloudflare.com/api/resources/d1/subresources/database/methods/export/
- Cloudflare D1 Platform Limits: https://developers.cloudflare.com/d1/platform/limits/
