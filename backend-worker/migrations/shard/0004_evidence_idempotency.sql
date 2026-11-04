-- ============================================================================
-- ASSETFLOW CLOUDFLARE D1 DATA SHARD MIGRATION
-- Migration: 0004_evidence_idempotency.sql
-- Description: Indexes asset_photos status and composite keys for
--              high-performance idempotent counting_records summary updates.
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_photos_summary_calc ON asset_photos(client_id, fiscal_year_id, asset_key, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_photos_global_id ON asset_photos(photo_id);
