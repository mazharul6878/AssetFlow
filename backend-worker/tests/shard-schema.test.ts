import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import { createLocalTestD1, MockD1Database } from './test-helper';

describe('Data Shard Schema and Constraints (SHARD_01)', () => {
  let shardDb: MockD1Database;
  const migrationPath = path.resolve(__dirname, '../migrations/shard/0001_initial_shard.sql');

  beforeEach(() => {
    shardDb = createLocalTestD1(migrationPath);
  });

  it('creates all 19 client-scoped shard tables', () => {
    const tables = shardDb.getNativeDb().prepare(
      "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    ).all() as { name: string }[];

    const tableNames = tables.map(t => t.name);
    expect(tableNames).toContain('input_schema_versions');
    expect(tableNames).toContain('input_field_definitions');
    expect(tableNames).toContain('input_field_options');
    expect(tableNames).toContain('fiscal_years');
    expect(tableNames).toContain('far_assets');
    expect(tableNames).toContain('counting_records');
    expect(tableNames).toContain('daily_batches');
    expect(tableNames).toContain('submissions');
    expect(tableNames).toContain('asset_photos');
    expect(tableNames).toContain('exceptions');
    expect(tableNames).toContain('found_not_in_far');
    expect(tableNames).toContain('master_data');
    expect(tableNames).toContain('access_requests');
    expect(tableNames).toContain('column_mapping');
    expect(tableNames).toContain('master_workbook_state');
    expect(tableNames).toContain('master_sync_jobs');
    expect(tableNames).toContain('export_jobs');
    expect(tableNames).toContain('activity_logs');
  });

  it('enforces SQLite json_valid() constraint on counting_records.values_json', () => {
    // 1. Seed Fiscal Year and Schema
    shardDb.getNativeDb().prepare(
      `INSERT INTO input_schema_versions (
         schema_version_id, client_id, fiscal_year_id, version_number,
         status, source_workbook_name, created_by_uid
       ) VALUES ('sch_01', 'cl_01', '2026_27', 1, 'PUBLISHED', 'test.xlsx', 'uid_admin')`
    ).run();

    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label, active_schema_version_id)
       VALUES ('cl_01', '2026_27', '2026-27', 'sch_01')`
    ).run();

    // 2. Seed FAR Asset
    shardDb.getNativeDb().prepare(
      `INSERT INTO far_assets (
         client_id, fiscal_year_id, asset_key, asset_id, source_row,
         import_batch_id, raw_row_json, search_text, imported_by_uid
       ) VALUES ('cl_01', '2026_27', 'key_001', '00142', 2, 'b_01', '{"id":"00142"}', '00142', 'uid_admin')`
    ).run();

    // 3. Inserting valid JSON in values_json succeeds
    expect(() => {
      shardDb.getNativeDb().prepare(
        `INSERT INTO counting_records (
           record_id, client_id, fiscal_year_id, asset_key, schema_version_id,
           auditor_uid, auditor_name_snapshot, count_date, values_json
         ) VALUES ('rec_01', 'cl_01', '2026_27', 'key_001', 'sch_01', 'uid_auditor', 'John Doe', '2026-11-01', '{"fld_01": "Good"}')`
      ).run();
    }).not.toThrow();

    // 4. Inserting invalid / malformed JSON must fail CHECK(json_valid(values_json))
    expect(() => {
      shardDb.getNativeDb().prepare(
        `INSERT INTO counting_records (
           record_id, client_id, fiscal_year_id, asset_key, schema_version_id,
           auditor_uid, auditor_name_snapshot, count_date, values_json
         ) VALUES ('rec_02', 'cl_01', '2026_27', 'key_001', 'sch_01', 'uid_auditor', 'John Doe', '2026-11-01', '{malformed json')`
      ).run();
    }).toThrow(/CHECK constraint failed/i);
  });

  it('strictly preserves leading zeros in Asset IDs (TEXT type guarantee)', async () => {
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label)
       VALUES ('cl_01', '2026_27', '2026-27')`
    ).run();

    const leadingZeroId = '0004920';
    await shardDb.prepare(
      `INSERT INTO far_assets (
         client_id, fiscal_year_id, asset_key, asset_id, source_row,
         import_batch_id, raw_row_json, search_text, imported_by_uid
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        'cl_01',
        '2026_27',
        'cl01_2026_27_0004920_R12',
        leadingZeroId,
        12,
        'batch_init',
        JSON.stringify({ 'Asset ID': leadingZeroId, 'Description': 'High Precision Laser' }),
        leadingZeroId,
        'uid_importer'
      )
      .run();

    const retrieved = await shardDb
      .prepare('SELECT asset_id FROM far_assets WHERE asset_key = ?')
      .bind('cl01_2026_27_0004920_R12')
      .first<{ asset_id: string }>();

    expect(retrieved?.asset_id).toBe('0004920');
    expect(typeof retrieved?.asset_id).toBe('string');
    expect(retrieved?.asset_id.startsWith('000')).toBe(true);
  });

  it('detects duplicate Asset ID attempts and prevents silent row overwrites', () => {
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label)
       VALUES ('cl_01', '2026_27', '2026-27')`
    ).run();

    shardDb.getNativeDb().prepare(
      `INSERT INTO far_assets (
         client_id, fiscal_year_id, asset_key, asset_id, source_row,
         import_batch_id, raw_row_json, search_text, imported_by_uid
       ) VALUES ('cl_01', '2026_27', 'asset_dup_01', 'DUP-01', 5, 'b1', '{}', 'DUP-01', 'u1')`
    ).run();

    // Duplicate insertion with same asset_key must throw PRIMARY KEY constraint violation
    expect(() => {
      shardDb.getNativeDb().prepare(
        `INSERT INTO far_assets (
           client_id, fiscal_year_id, asset_key, asset_id, source_row,
           import_batch_id, raw_row_json, search_text, imported_by_uid
         ) VALUES ('cl_01', '2026_27', 'asset_dup_01', 'DUP-01', 5, 'b1', '{}', 'DUP-01', 'u1')`
      ).run();
    }).toThrow(/UNIQUE constraint failed/i);
  });
});
