-- ============================================================================
-- ASSETFLOW CLOUDFLARE D1 CONTROL DATABASE MIGRATION
-- Migration: 0001_initial_control.sql
-- Description: Core global users, tenant clients, authoritative memberships,
--              shard routing, system config, deletion audit, and project settings.
-- ============================================================================

-- 1. GLOBAL USERS TABLE
CREATE TABLE IF NOT EXISTS users (
    uid             TEXT NOT NULL PRIMARY KEY,
    email           TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    full_name       TEXT NOT NULL,
    nickname        TEXT NOT NULL,
    global_role     TEXT NOT NULL DEFAULT 'Auditor' CHECK(global_role IN ('Owner', 'Admin', 'Auditor', 'Reviewer')),
    account_status  TEXT NOT NULL DEFAULT 'Active' CHECK(account_status IN ('Active', 'Suspended', 'Deleted')),
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_status ON users(account_status);

-- 2. CLIENTS (TENANTS) TABLE
CREATE TABLE IF NOT EXISTS clients (
    client_id               TEXT NOT NULL PRIMARY KEY,
    client_name             TEXT NOT NULL,
    client_code             TEXT NOT NULL UNIQUE,
    industry                TEXT NOT NULL,
    contact_person          TEXT,
    contact_email           TEXT,
    status                  TEXT NOT NULL DEFAULT 'Active' CHECK(status IN ('Active', 'Archived', 'Deleting', 'Deleted')),
    active_fiscal_year_id   TEXT NOT NULL DEFAULT '2026_27',
    unique_asset_key_header TEXT NOT NULL DEFAULT 'Asset ID',
    drive_root_folder_id    TEXT,
    created_by_uid          TEXT NOT NULL,
    created_at              TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at              TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (created_by_uid) REFERENCES users(uid)
);

CREATE INDEX IF NOT EXISTS idx_clients_code ON clients(client_code);
CREATE INDEX IF NOT EXISTS idx_clients_status ON clients(status);

-- 3. AUTHORITATIVE CLIENT MEMBERSHIPS TABLE
-- Single authoritative role and permission store for Client operations
CREATE TABLE IF NOT EXISTS client_memberships (
    membership_id           TEXT NOT NULL PRIMARY KEY,
    client_id               TEXT NOT NULL,
    user_uid                TEXT NOT NULL,
    role                    TEXT NOT NULL CHECK(role IN ('Owner', 'Admin', 'Auditor', 'Reviewer')),
    account_status          TEXT NOT NULL DEFAULT 'Active' CHECK(account_status IN ('Active', 'Suspended', 'Revoked')),
    has_review_authority    INTEGER NOT NULL DEFAULT 0 CHECK(has_review_authority IN (0, 1)),
    can_publish             INTEGER NOT NULL DEFAULT 0 CHECK(can_publish IN (0, 1)),
    can_sync_drive          INTEGER NOT NULL DEFAULT 0 CHECK(can_sync_drive IN (0, 1)),
    assigned_by_uid         TEXT NOT NULL,
    created_at              TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at              TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(client_id, user_uid),
    FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE,
    FOREIGN KEY (user_uid) REFERENCES users(uid),
    FOREIGN KEY (assigned_by_uid) REFERENCES users(uid)
);

CREATE INDEX IF NOT EXISTS idx_memberships_client ON client_memberships(client_id, account_status);
CREATE INDEX IF NOT EXISTS idx_memberships_user ON client_memberships(user_uid, account_status);

-- 4. CLIENT-TO-SHARD ROUTING TABLE
CREATE TABLE IF NOT EXISTS client_shard_routing (
    client_id       TEXT NOT NULL PRIMARY KEY,
    shard_binding   TEXT NOT NULL,
    database_id     TEXT NOT NULL,
    routing_status  TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(routing_status IN ('ACTIVE', 'READ_ONLY', 'MIGRATING', 'DELETING')),
    assigned_at     TEXT NOT NULL DEFAULT (datetime('now')),
    migrated_at     TEXT,
    FOREIGN KEY (client_id) REFERENCES clients(client_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_routing_shard ON client_shard_routing(shard_binding, routing_status);

-- 5. SHARD METADATA TABLE
CREATE TABLE IF NOT EXISTS shard_metadata (
    shard_binding              TEXT NOT NULL PRIMARY KEY,
    database_id                TEXT NOT NULL,
    status                     TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'FULL', 'MAINTENANCE', 'OFFLINE')),
    client_count               INTEGER NOT NULL DEFAULT 0,
    approximate_storage_bytes  INTEGER NOT NULL DEFAULT 0,
    last_backup_at             TEXT,
    last_health_check_at       TEXT
);

-- Pre-seed default SHARD_01 metadata
INSERT OR IGNORE INTO shard_metadata (shard_binding, database_id, status, client_count, approximate_storage_bytes)
VALUES ('SHARD_01', '11111111-1111-1111-1111-111111111111', 'ACTIVE', 0, 0);

-- 6. SYSTEM CONFIGURATION TABLE
CREATE TABLE IF NOT EXISTS system_config (
    config_key      TEXT NOT NULL PRIMARY KEY,
    config_value    TEXT NOT NULL,
    description     TEXT,
    updated_by_uid  TEXT,
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Seed Initial System Config Baseline
INSERT OR IGNORE INTO system_config (config_key, config_value, description)
VALUES 
    ('system_initialized', 'true', 'Flag indicating whether system bootstrap has occurred'),
    ('designated_system_owner_email', 'admin@assetflow.local', 'Designated first System Owner email'),
    ('allow_secondary_admin_creation', 'true', 'Whether primary Admin can provision secondary Admins'),
    ('default_client_shard', 'SHARD_01', 'Default target shard for newly created clients');

-- 7. GLOBAL DELETION LOGS (TOMBSTONE & AUDIT)
CREATE TABLE IF NOT EXISTS deleted_identities (
    tombstone_id        TEXT NOT NULL PRIMARY KEY,
    subject_type        TEXT NOT NULL CHECK(subject_type IN ('USER', 'CLIENT')),
    subject_id_hash     TEXT NOT NULL,
    original_identifier TEXT NOT NULL,
    deleted_by_uid      TEXT NOT NULL,
    deletion_reason     TEXT,
    deleted_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_deleted_hash ON deleted_identities(subject_id_hash);

CREATE TABLE IF NOT EXISTS deletion_audit_events (
    event_id            TEXT NOT NULL PRIMARY KEY,
    saga_id             TEXT NOT NULL,
    client_id           TEXT NOT NULL,
    stage               TEXT NOT NULL CHECK(stage IN ('REQUESTED', 'CONFIRMED', 'ACCESS_BLOCKED', 'SHARD_DELETION_IN_PROGRESS', 'SHARD_DATA_DELETED', 'CONTROL_DATA_CLEANUP', 'COMPLETED', 'FAILED_RETRYABLE')),
    status              TEXT NOT NULL,
    details_json        TEXT,
    initiated_by_uid    TEXT NOT NULL,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_deletion_saga ON deletion_audit_events(saga_id, client_id);

-- 8. PROJECT SETTINGS TABLE
CREATE TABLE IF NOT EXISTS project_settings (
    setting_key     TEXT NOT NULL PRIMARY KEY,
    setting_value   TEXT NOT NULL,
    category        TEXT NOT NULL DEFAULT 'GENERAL',
    updated_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

INSERT OR IGNORE INTO project_settings (setting_key, setting_value, category)
VALUES
    ('offline_sync_batch_limit', '100', 'SYNC'),
    ('max_photos_per_asset', '10', 'EVIDENCE'),
    ('session_inactivity_timeout_minutes', '60', 'SECURITY');
