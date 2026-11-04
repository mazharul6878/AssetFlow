-- ============================================================================
-- ASSETFLOW CLOUDFLARE D1 DATA SHARD MIGRATION
-- Migration: 0001_initial_shard.sql
-- Description: Client-scoped data storage including dynamic input schema,
--              FAR baseline (JSON), dynamic counting records (Option B),
--              batches, submissions, photos, exceptions, and audit logs.
-- ============================================================================

-- 1. INPUT SCHEMA VERSIONS TABLE
CREATE TABLE IF NOT EXISTS input_schema_versions (
    schema_version_id           TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    version_number              INTEGER NOT NULL,
    status                      TEXT NOT NULL CHECK(status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    source_workbook_name        TEXT NOT NULL,
    source_workbook_drive_file_id TEXT,
    source_worksheet_name       TEXT NOT NULL DEFAULT 'Count Sheet',
    source_worksheet_index      INTEGER NOT NULL DEFAULT 0,
    header_row_index            INTEGER NOT NULL DEFAULT 0,
    unique_asset_key_header     TEXT NOT NULL DEFAULT 'Asset ID',
    change_summary              TEXT,
    created_by_uid              TEXT NOT NULL,
    created_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    published_by_uid            TEXT,
    published_at                TEXT,
    previous_version_id         TEXT,
    UNIQUE(client_id, fiscal_year_id, version_number),
    FOREIGN KEY (previous_version_id) REFERENCES input_schema_versions(schema_version_id)
);

CREATE INDEX IF NOT EXISTS idx_schemas_client ON input_schema_versions(client_id, fiscal_year_id, status);

-- 2. INPUT FIELD DEFINITIONS TABLE
CREATE TABLE IF NOT EXISTS input_field_definitions (
    field_id                    TEXT NOT NULL,
    schema_version_id           TEXT NOT NULL,
    client_id                   TEXT NOT NULL,
    field_key                   TEXT NOT NULL,
    display_label               TEXT NOT NULL,
    original_header             TEXT,
    source_type                 TEXT NOT NULL CHECK(source_type IN ('EXCEL_POPULATED', 'EXCEL_EMPTY', 'CUSTOM', 'SYSTEM')),
    source_worksheet_index      INTEGER,
    source_column_index         INTEGER,
    source_column_letter        TEXT,
    input_mode                  TEXT NOT NULL CHECK(input_mode IN ('FIXED_IMPORTED', 'MANUAL', 'AUTO', 'DROPDOWN', 'HIDDEN', 'INACTIVE')),
    data_type                   TEXT NOT NULL CHECK(data_type IN ('TEXT', 'MULTILINE_TEXT', 'INTEGER', 'DECIMAL', 'DATE', 'TIME', 'BOOLEAN', 'SINGLE_SELECT', 'MULTI_SELECT', 'PHOTO_EVIDENCE')),
    is_required                 INTEGER NOT NULL DEFAULT 0 CHECK(is_required IN (0, 1)),
    is_visible                  INTEGER NOT NULL DEFAULT 1 CHECK(is_visible IN (0, 1)),
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    validation_rules_json       TEXT,
    auto_source_rule            TEXT,
    promoted_from_far           INTEGER NOT NULL DEFAULT 0 CHECK(promoted_from_far IN (0, 1)),
    destination_excel_header    TEXT,
    semantic_role               TEXT,
    allow_other_option          INTEGER NOT NULL DEFAULT 0 CHECK(allow_other_option IN (0, 1)),
    is_archived                 INTEGER NOT NULL DEFAULT 0 CHECK(is_archived IN (0, 1)),
    created_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (schema_version_id, field_id),
    FOREIGN KEY (schema_version_id) REFERENCES input_schema_versions(schema_version_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_fields_lookup ON input_field_definitions(schema_version_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_fields_client ON input_field_definitions(client_id, field_id);

-- 3. INPUT FIELD OPTIONS TABLE (DROPDOWN CHOICES)
CREATE TABLE IF NOT EXISTS input_field_options (
    option_id                   TEXT NOT NULL PRIMARY KEY,
    field_id                    TEXT NOT NULL,
    schema_version_id           TEXT NOT NULL,
    client_id                   TEXT NOT NULL,
    option_label                TEXT NOT NULL,
    option_value                TEXT NOT NULL,
    sort_order                  INTEGER NOT NULL DEFAULT 0,
    is_active                   INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0, 1)),
    created_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (schema_version_id, field_id) REFERENCES input_field_definitions(schema_version_id, field_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_options_field ON input_field_options(schema_version_id, field_id, is_active, sort_order);

-- 4. FISCAL YEARS / AUDIT CYCLES TABLE
CREATE TABLE IF NOT EXISTS fiscal_years (
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    label                       TEXT NOT NULL,
    active_schema_version_id    TEXT,
    start_date                  TEXT,
    end_date                    TEXT,
    is_closed                   INTEGER NOT NULL DEFAULT 0 CHECK(is_closed IN (0, 1)),
    created_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (client_id, fiscal_year_id),
    FOREIGN KEY (active_schema_version_id) REFERENCES input_schema_versions(schema_version_id)
);

-- 5. DYNAMIC FAR ASSETS (BASELINE STORAGE)
CREATE TABLE IF NOT EXISTS far_assets (
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    asset_key                   TEXT NOT NULL,
    asset_id                    TEXT NOT NULL,
    source_row                  INTEGER NOT NULL,
    source_worksheet            TEXT NOT NULL DEFAULT 'Count Sheet',
    source_worksheet_index      INTEGER NOT NULL DEFAULT 0,
    import_batch_id             TEXT NOT NULL,
    raw_row_json                TEXT NOT NULL,
    search_text                 TEXT NOT NULL,
    imported_by_uid             TEXT NOT NULL,
    imported_at                 TEXT NOT NULL DEFAULT (datetime('now')),
    is_active                   INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0, 1)),
    PRIMARY KEY (client_id, fiscal_year_id, asset_key),
    FOREIGN KEY (client_id, fiscal_year_id) REFERENCES fiscal_years(client_id, fiscal_year_id)
);

CREATE INDEX IF NOT EXISTS idx_far_lookup ON far_assets(client_id, fiscal_year_id, asset_id);
CREATE INDEX IF NOT EXISTS idx_far_active ON far_assets(client_id, fiscal_year_id, is_active);

-- 6. DYNAMIC COUNTING RECORDS TABLE (OPTION B: STRUCTURED JSON)
CREATE TABLE IF NOT EXISTS counting_records (
    record_id                   TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    asset_key                   TEXT NOT NULL,
    schema_version_id           TEXT NOT NULL,
    auditor_uid                 TEXT NOT NULL,
    auditor_name_snapshot       TEXT NOT NULL,
    record_status               TEXT NOT NULL DEFAULT 'DRAFT' CHECK(record_status IN ('DRAFT', 'SUBMITTED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    batch_id                    TEXT,
    count_date                  TEXT NOT NULL,
    values_json                 TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(values_json)),
    idempotency_key             TEXT UNIQUE,
    record_version              INTEGER NOT NULL DEFAULT 1,
    created_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    FOREIGN KEY (schema_version_id) REFERENCES input_schema_versions(schema_version_id),
    FOREIGN KEY (client_id, fiscal_year_id, asset_key) REFERENCES far_assets(client_id, fiscal_year_id, asset_key)
);

CREATE INDEX IF NOT EXISTS idx_cnt_asset ON counting_records(client_id, fiscal_year_id, asset_key);
CREATE INDEX IF NOT EXISTS idx_cnt_auditor ON counting_records(client_id, auditor_uid, count_date);
CREATE INDEX IF NOT EXISTS idx_cnt_status ON counting_records(client_id, fiscal_year_id, record_status);
CREATE INDEX IF NOT EXISTS idx_cnt_batch ON counting_records(client_id, batch_id);

-- 7. DAILY BATCHES TABLE
CREATE TABLE IF NOT EXISTS daily_batches (
    batch_id                    TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    auditor_uid                 TEXT NOT NULL,
    auditor_name                TEXT NOT NULL,
    batch_date                  TEXT NOT NULL,
    status                      TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    total_items                 INTEGER NOT NULL DEFAULT 0,
    created_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at                  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_batches_auditor ON daily_batches(client_id, auditor_uid, batch_date);

-- 8. SUBMISSIONS TABLE
CREATE TABLE IF NOT EXISTS submissions (
    submission_id               TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    batch_id                    TEXT,
    submitted_by_uid            TEXT NOT NULL,
    submitted_by_name           TEXT NOT NULL,
    status                      TEXT NOT NULL DEFAULT 'PENDING_REVIEW' CHECK(status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    item_count                  INTEGER NOT NULL DEFAULT 0,
    submitted_at                TEXT NOT NULL DEFAULT (datetime('now')),
    reviewed_by_uid             TEXT,
    reviewed_at                 TEXT
);

CREATE INDEX IF NOT EXISTS idx_submissions_client ON submissions(client_id, fiscal_year_id, status);

-- 9. ASSET PHOTOS TABLE
CREATE TABLE IF NOT EXISTS asset_photos (
    photo_id                    TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    asset_key                   TEXT NOT NULL,
    local_filename              TEXT NOT NULL,
    drive_file_id               TEXT,
    drive_web_view_link         TEXT,
    captured_by_uid             TEXT NOT NULL,
    captured_at                 TEXT NOT NULL,
    status                      TEXT NOT NULL DEFAULT 'PENDING_UPLOAD' CHECK(status IN ('PENDING_UPLOAD', 'UPLOADED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_photos_asset ON asset_photos(client_id, asset_key);

-- 10. EXCEPTIONS TABLE
CREATE TABLE IF NOT EXISTS exceptions (
    exception_id                TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    asset_key                   TEXT NOT NULL,
    exception_type              TEXT NOT NULL,
    description                 TEXT NOT NULL,
    status                      TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
    logged_by_uid               TEXT NOT NULL,
    created_at                  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_exceptions_asset ON exceptions(client_id, fiscal_year_id, asset_key);

-- 11. FOUND NOT IN FAR TABLE
CREATE TABLE IF NOT EXISTS found_not_in_far (
    temp_id                     TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    description                 TEXT NOT NULL,
    raw_values_json             TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(raw_values_json)),
    auditor_uid                 TEXT NOT NULL,
    auditor_name                TEXT NOT NULL,
    count_date                  TEXT NOT NULL,
    created_at                  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_fnif_client ON found_not_in_far(client_id, fiscal_year_id);

-- 12. MASTER DATA TABLE (LOOKUP LISTS)
CREATE TABLE IF NOT EXISTS master_data (
    config_id                   TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    category_type               TEXT NOT NULL,
    items_json                  TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(items_json)),
    updated_at                  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_md_client ON master_data(client_id, category_type);

-- 13. ACCESS REQUESTS TABLE
CREATE TABLE IF NOT EXISTS access_requests (
    request_id                  TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    requester_uid               TEXT NOT NULL,
    requester_email             TEXT NOT NULL,
    reason                      TEXT,
    status                      TEXT NOT NULL DEFAULT 'Pending' CHECK(status IN ('Pending', 'Approved', 'Rejected')),
    requested_at                TEXT NOT NULL DEFAULT (datetime('now')),
    decided_at                  TEXT,
    decided_by_uid              TEXT
);

-- 14. COLUMN MAPPING TABLE
CREATE TABLE IF NOT EXISTS column_mapping (
    mapping_id                  TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    template_name               TEXT NOT NULL,
    mapping_rules_json          TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(mapping_rules_json)),
    updated_at                  TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 15. MASTER WORKBOOK STATE TABLE
CREATE TABLE IF NOT EXISTS master_workbook_state (
    state_id                    TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    sync_state                  TEXT NOT NULL DEFAULT 'NOT_SYNCED',
    last_synced_at              TEXT,
    file_checksum               TEXT
);

-- 16. MASTER SYNC JOBS TABLE
CREATE TABLE IF NOT EXISTS master_sync_jobs (
    job_id                      TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    job_type                    TEXT NOT NULL,
    status                      TEXT NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    error_message               TEXT,
    created_at                  TEXT NOT NULL DEFAULT (datetime('now')),
    completed_at                TEXT
);

-- 17. EXPORT JOBS TABLE
CREATE TABLE IF NOT EXISTS export_jobs (
    export_id                   TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    format                      TEXT NOT NULL DEFAULT 'XLSX',
    status                      TEXT NOT NULL DEFAULT 'QUEUED' CHECK(status IN ('QUEUED', 'IN_PROGRESS', 'READY', 'FAILED')),
    download_url                TEXT,
    expires_at                  TEXT,
    created_at                  TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 18. ACTIVITY LOGS TABLE
CREATE TABLE IF NOT EXISTS activity_logs (
    log_id                      TEXT NOT NULL PRIMARY KEY,
    client_id                   TEXT NOT NULL,
    fiscal_year_id              TEXT NOT NULL,
    user_uid                    TEXT NOT NULL,
    action                      TEXT NOT NULL,
    entity_type                 TEXT NOT NULL,
    entity_id                   TEXT NOT NULL,
    details_json                TEXT,
    created_at                  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_activity_client ON activity_logs(client_id, fiscal_year_id, created_at);
