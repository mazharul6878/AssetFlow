-- ============================================================================
-- ASSETFLOW CLOUDFLARE D1 CONTROL DATABASE MIGRATION
-- Migration: 0002_owner_bootstrap.sql
-- Description: Adds system_bootstrap singleton table with unique constraint
--              to ensure atomic, one-time initial System Owner provisioning.
-- ============================================================================

CREATE TABLE IF NOT EXISTS system_bootstrap (
    singleton_id    INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    owner_uid       TEXT NOT NULL UNIQUE,
    owner_email     TEXT NOT NULL,
    bootstrapped_at TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (owner_uid) REFERENCES users(uid)
);

CREATE INDEX IF NOT EXISTS idx_bootstrap_owner ON system_bootstrap(owner_uid);
