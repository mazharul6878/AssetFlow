-- ============================================================================
-- ASSETFLOW CLOUDFLARE D1 CONTROL DATABASE MIGRATION
-- Migration: 0003_client_onboarding_saga.sql
-- Description: Adds setup_status and drive_provisioning_status to clients,
--              and creates client_onboarding_operations table for durable cross-D1 sagas.
-- ============================================================================

ALTER TABLE clients ADD COLUMN setup_status TEXT NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE clients ADD COLUMN drive_provisioning_status TEXT NOT NULL DEFAULT 'NOT_REQUESTED';
ALTER TABLE clients ADD COLUMN drive_provisioning_error TEXT;

CREATE TABLE IF NOT EXISTS client_onboarding_operations (
    operation_id        TEXT NOT NULL PRIMARY KEY,
    client_id           TEXT NOT NULL,
    client_code         TEXT NOT NULL,
    stage               TEXT NOT NULL CHECK(stage IN ('INITIATED', 'CONTROL_PROVISIONED', 'SHARD_PROVISIONED', 'COMPLETED', 'FAILED_RETRYABLE')),
    created_by_uid      TEXT NOT NULL,
    fiscal_year_label   TEXT NOT NULL,
    error_message       TEXT,
    created_at          TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at          TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_onboarding_client ON client_onboarding_operations(client_id);
CREATE INDEX IF NOT EXISTS idx_onboarding_code ON client_onboarding_operations(client_code);
