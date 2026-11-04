# AssetFlow Database Schema Specification

## Part 1: Control Database (`CONTROL_DB`)

### 1. `users`
Global identity store populated upon initial user sign-in.
- `uid TEXT PRIMARY KEY` — Firebase Authentication UID.
- `email TEXT NOT NULL UNIQUE` — Normalized user email.
- `display_name TEXT NOT NULL` — Display name.
- `full_name TEXT NOT NULL` — Legal full name.
- `nickname TEXT NOT NULL` — Compact normalized handle.
- `global_role TEXT NOT NULL DEFAULT 'Auditor'` — `CHECK(global_role IN ('Owner', 'Admin', 'Auditor', 'Reviewer'))`.
- `account_status TEXT NOT NULL DEFAULT 'Active'` — `CHECK(account_status IN ('Active', 'Suspended', 'Deleted'))`.
- `created_at TEXT NOT NULL DEFAULT (datetime('now'))`
- `updated_at TEXT NOT NULL DEFAULT (datetime('now'))`

### 2. `clients`
Master tenant registry.
- `client_id TEXT PRIMARY KEY` — Stable client slug or UUID.
- `client_name TEXT NOT NULL` — Legal company or corporate name.
- `client_code TEXT NOT NULL UNIQUE` — 3-6 letter unique ticker (e.g., `WLT`).
- `industry TEXT NOT NULL` — Industry domain.
- `status TEXT NOT NULL DEFAULT 'Active'` — `CHECK(status IN ('Active', 'Archived', 'Deleting', 'Deleted'))`.
- `active_fiscal_year_id TEXT NOT NULL DEFAULT '2026_27'`
- `unique_asset_key_header TEXT NOT NULL DEFAULT 'Asset ID'`
- `created_by_uid TEXT NOT NULL REFERENCES users(uid)`

### 3. `client_memberships`
Single authoritative role and permission store for client-scoped operations.
- `membership_id TEXT PRIMARY KEY`
- `client_id TEXT NOT NULL REFERENCES clients(client_id) ON DELETE CASCADE`
- `user_uid TEXT NOT NULL REFERENCES users(uid)`
- `role TEXT NOT NULL CHECK(role IN ('Owner', 'Admin', 'Auditor', 'Reviewer'))`
- `account_status TEXT NOT NULL DEFAULT 'Active' CHECK(account_status IN ('Active', 'Suspended', 'Revoked'))`
- `has_review_authority INTEGER NOT NULL DEFAULT 0 CHECK(has_review_authority IN (0, 1))`
- `can_publish INTEGER NOT NULL DEFAULT 0 CHECK(can_publish IN (0, 1))`
- `can_sync_drive INTEGER NOT NULL DEFAULT 0 CHECK(can_sync_drive IN (0, 1))`
- `assigned_by_uid TEXT NOT NULL REFERENCES users(uid)`
- `UNIQUE(client_id, user_uid)`

### 4. `client_shard_routing`
Tenant-to-database routing registry.
- `client_id TEXT PRIMARY KEY REFERENCES clients(client_id) ON DELETE CASCADE`
- `shard_binding TEXT NOT NULL` — Must match `SHARD_01` ... `SHARD_09`.
- `database_id TEXT NOT NULL` — Cloudflare D1 UUID.
- `routing_status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(routing_status IN ('ACTIVE', 'READ_ONLY', 'MIGRATING', 'DELETING'))`

### 5. `shard_metadata`
Physical capacity and operational status tracking for all provisioned shards.
- `shard_binding TEXT PRIMARY KEY`
- `database_id TEXT NOT NULL`
- `status TEXT NOT NULL DEFAULT 'ACTIVE'`
- `client_count INTEGER NOT NULL DEFAULT 0`
- `approximate_storage_bytes INTEGER NOT NULL DEFAULT 0`

### 6. `system_config`
System-level configuration and immutable Owner identity bootstrap.
- `config_key TEXT PRIMARY KEY`
- `config_value TEXT NOT NULL`
- `description TEXT`

### 7. `deleted_identities` & `deletion_audit_events`
Tombstone ledger and asynchronous Saga deletion lifecycle events.

---

## Part 2: Data Shard (`SHARD_01` ... `SHARD_09`)

### 1. `input_schema_versions`
Dynamic input schema release versions.
- `schema_version_id TEXT NOT NULL PRIMARY KEY` (e.g. `sch_wlt_fy26_v1`)
- `client_id TEXT NOT NULL`
- `fiscal_year_id TEXT NOT NULL`
- `version_number INTEGER NOT NULL`
- `status TEXT NOT NULL CHECK(status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))`
- `source_workbook_name TEXT NOT NULL`
- `source_worksheet_name TEXT NOT NULL DEFAULT 'Count Sheet'`
- `unique_asset_key_header TEXT NOT NULL DEFAULT 'Asset ID'`
- `UNIQUE(client_id, fiscal_year_id, version_number)`

### 2. `input_field_definitions`
Schema-pinned dynamic field definitions.
- `field_id TEXT NOT NULL` — Immutable stable slug (e.g. `fld_cond_01`).
- `schema_version_id TEXT NOT NULL REFERENCES input_schema_versions(schema_version_id) ON DELETE CASCADE`
- `client_id TEXT NOT NULL`
- `field_key TEXT NOT NULL` — JSON object key (e.g. `condition`).
- `display_label TEXT NOT NULL` — Human UI label.
- `source_type TEXT NOT NULL CHECK(source_type IN ('EXCEL_POPULATED', 'EXCEL_EMPTY', 'CUSTOM', 'SYSTEM'))`
- `input_mode TEXT NOT NULL CHECK(input_mode IN ('FIXED_IMPORTED', 'MANUAL', 'AUTO', 'DROPDOWN', 'HIDDEN', 'INACTIVE'))`
- `data_type TEXT NOT NULL CHECK(data_type IN ('TEXT', 'MULTILINE_TEXT', 'INTEGER', 'DECIMAL', 'DATE', 'TIME', 'BOOLEAN', 'SINGLE_SELECT', 'MULTI_SELECT', 'PHOTO_EVIDENCE'))`
- `is_required INTEGER NOT NULL DEFAULT 0`
- `is_visible INTEGER NOT NULL DEFAULT 1`
- `sort_order INTEGER NOT NULL DEFAULT 0`
- `promoted_from_far INTEGER NOT NULL DEFAULT 0`
- `allow_other_option INTEGER NOT NULL DEFAULT 0`
- `is_archived INTEGER NOT NULL DEFAULT 0`
- `PRIMARY KEY (schema_version_id, field_id)`

### 3. `input_field_options`
Dropdown option items.
- `option_id TEXT NOT NULL PRIMARY KEY`
- `field_id TEXT NOT NULL`
- `schema_version_id TEXT NOT NULL`
- `option_label TEXT NOT NULL`
- `option_value TEXT NOT NULL`
- `sort_order INTEGER NOT NULL DEFAULT 0`
- `is_active INTEGER NOT NULL DEFAULT 1`

### 4. `far_assets`
Master Fixed Asset Register row storage.
- `client_id TEXT NOT NULL`
- `fiscal_year_id TEXT NOT NULL`
- `asset_key TEXT NOT NULL` — Composite key (`client_year_assetid_row`).
- `asset_id TEXT NOT NULL` — Guaranteed TEXT type to strictly preserve leading zeros.
- `source_row INTEGER NOT NULL`
- `raw_row_json TEXT NOT NULL` — Complete imported row data as JSON object.
- `search_text TEXT NOT NULL` — Normalized text search token.
- `PRIMARY KEY (client_id, fiscal_year_id, asset_key)`

### 5. `counting_records`
Dynamic field verification records.
- `record_id TEXT NOT NULL PRIMARY KEY`
- `client_id TEXT NOT NULL`
- `fiscal_year_id TEXT NOT NULL`
- `asset_key TEXT NOT NULL`
- `schema_version_id TEXT NOT NULL REFERENCES input_schema_versions(schema_version_id)`
- `auditor_uid TEXT NOT NULL`
- `auditor_name_snapshot TEXT NOT NULL`
- `record_status TEXT NOT NULL DEFAULT 'DRAFT'`
- `count_date TEXT NOT NULL`
- `values_json TEXT NOT NULL DEFAULT '{}' CHECK(json_valid(values_json))`
- `idempotency_key TEXT UNIQUE`
- `record_version INTEGER NOT NULL DEFAULT 1`
