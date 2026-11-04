-- ============================================================================
-- ASSETFLOW CLOUDFLARE D1 DATA SHARD MIGRATION
-- Migration: 0002_evidence_metadata.sql
-- Description: Enhances asset_photos with complete audit evidence metadata:
--              Asset ID (text preserving leading zeroes), uploader name snapshot,
--              photo category, caption, MIME type, file size, SHA-256 hash,
--              Drive folder references, and upload timestamps.
-- ============================================================================

ALTER TABLE asset_photos ADD COLUMN asset_id TEXT;
ALTER TABLE asset_photos ADD COLUMN audit_record_id TEXT;
ALTER TABLE asset_photos ADD COLUMN uploader_uid TEXT;
ALTER TABLE asset_photos ADD COLUMN uploader_name_snapshot TEXT;
ALTER TABLE asset_photos ADD COLUMN photo_category TEXT DEFAULT 'GENERAL';
ALTER TABLE asset_photos ADD COLUMN caption TEXT;
ALTER TABLE asset_photos ADD COLUMN mime_type TEXT DEFAULT 'image/jpeg';
ALTER TABLE asset_photos ADD COLUMN file_extension TEXT DEFAULT 'jpg';
ALTER TABLE asset_photos ADD COLUMN file_size INTEGER DEFAULT 0;
ALTER TABLE asset_photos ADD COLUMN sha256 TEXT;
ALTER TABLE asset_photos ADD COLUMN drive_root_folder_id TEXT;
ALTER TABLE asset_photos ADD COLUMN drive_date_folder_id TEXT;
ALTER TABLE asset_photos ADD COLUMN uploaded_at TEXT;

CREATE INDEX IF NOT EXISTS idx_photos_client_asset_id ON asset_photos(client_id, asset_id);
CREATE INDEX IF NOT EXISTS idx_photos_client_cycle ON asset_photos(client_id, fiscal_year_id);
