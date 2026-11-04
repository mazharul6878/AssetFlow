-- ============================================================================
-- ASSETFLOW CLOUDFLARE D1 DATA SHARD MIGRATION
-- Migration: 0003_evidence_hardening.sql
-- Description: Adds counting_records summary tracking columns and timestamps
--              for atomic evidence metadata persistence.
-- ============================================================================

ALTER TABLE asset_photos ADD COLUMN created_at TEXT DEFAULT (datetime('now'));
ALTER TABLE asset_photos ADD COLUMN updated_at TEXT DEFAULT (datetime('now'));

ALTER TABLE counting_records ADD COLUMN photo_count INTEGER DEFAULT 0;
ALTER TABLE counting_records ADD COLUMN has_photo_evidence INTEGER DEFAULT 0;
ALTER TABLE counting_records ADD COLUMN last_photo_uploaded_at TEXT;

CREATE INDEX IF NOT EXISTS idx_counting_evidence_summary ON counting_records(client_id, fiscal_year_id, has_photo_evidence);
CREATE INDEX IF NOT EXISTS idx_photos_client_photo_id ON asset_photos(client_id, photo_id);
