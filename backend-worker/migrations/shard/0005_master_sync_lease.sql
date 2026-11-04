-- ============================================================================
-- ASSETFLOW CLOUDFLARE D1 DATA SHARD MIGRATION
-- Migration: 0005_master_sync_lease.sql
-- Description: Enhances master_workbook_state and master_sync_jobs with
--              single-writer concurrency lease lock columns, Drive file tracking,
--              and applied submission idempotency ledger.
-- ============================================================================

ALTER TABLE master_workbook_state ADD COLUMN master_drive_file_id TEXT;
ALTER TABLE master_workbook_state ADD COLUMN master_drive_file_name TEXT;
ALTER TABLE master_workbook_state ADD COLUMN head_revision_id TEXT;
ALTER TABLE master_workbook_state ADD COLUMN lease_owner_uid TEXT;
ALTER TABLE master_workbook_state ADD COLUMN lease_owner_job_id TEXT;
ALTER TABLE master_workbook_state ADD COLUMN lease_expires_at_ms INTEGER DEFAULT 0;
ALTER TABLE master_workbook_state ADD COLUMN applied_submissions_json TEXT DEFAULT '{}';
ALTER TABLE master_workbook_state ADD COLUMN updated_at TEXT DEFAULT (datetime('now'));

ALTER TABLE master_sync_jobs ADD COLUMN triggered_by_uid TEXT;
ALTER TABLE master_sync_jobs ADD COLUMN batch_id TEXT;
ALTER TABLE master_sync_jobs ADD COLUMN details_json TEXT DEFAULT '{}';
