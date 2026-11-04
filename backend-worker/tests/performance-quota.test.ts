import { describe, it, expect } from 'vitest';
import * as path from 'node:path';
import * as fs from 'node:fs';
import { createLocalTestD1, MockD1Database } from './test-helper';

describe('Performance, Storage Sizing, and D1 Quota Benchmarking', () => {
  const shardMigration = path.resolve(__dirname, '../migrations/shard/0001_initial_shard.sql');
  const tempDbPath = path.resolve(__dirname, '../test_measured_capacity.sqlite');

  it('empirically measures local SQLite database size with 10,000 FAR assets & 10,000 counting records', async () => {
    // Clean any prior file
    if (fs.existsSync(tempDbPath)) {
      fs.unlinkSync(tempDbPath);
    }

    const fileDb = createLocalTestD1(shardMigration, tempDbPath);
    const nativeDb = fileDb.getNativeDb();

    // 1. Seed Fiscal Year and Schema
    nativeDb.prepare(
      `INSERT INTO input_schema_versions (
         schema_version_id, client_id, fiscal_year_id, version_number,
         status, source_workbook_name, created_by_uid
       ) VALUES ('sch_bench_01', 'cl_bench', '2026_27', 1, 'PUBLISHED', 'benchmark.xlsx', 'uid_admin')`
    ).run();

    nativeDb.prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label, active_schema_version_id)
       VALUES ('cl_bench', '2026_27', '2026-27', 'sch_bench_01')`
    ).run();

    // 2. Measure Initial Empty Database Size
    const emptyStats = fs.statSync(tempDbPath);
    console.log(`[Storage Metric] Empty D1 Shard Schema: ${emptyStats.size} bytes (${(emptyStats.size / 1024).toFixed(2)} KB)`);

    // 3. Batch Insert 10,000 FAR Assets (preserving leading zeros)
    console.time('FAR 10,000 Batch Insert');
    nativeDb.exec('BEGIN TRANSACTION;');
    const insertFarStmt = nativeDb.prepare(
      `INSERT INTO far_assets (
         client_id, fiscal_year_id, asset_key, asset_id, source_row,
         import_batch_id, raw_row_json, search_text, imported_by_uid
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
    );

    for (let i = 1; i <= 10000; i++) {
      const assetId = i.toString().padStart(6, '0'); // e.g. "000001", "009999"
      const assetKey = `clbench_2026_27_${assetId}_R${i + 1}`;
      const rawRow = JSON.stringify({
        'Asset Code': assetId,
        'Asset Description': `Industrial Machinery Unit #${i}`,
        'Cost Center': `CC-10${i % 10}`,
        'Category': 'Plant & Machinery',
        'Cost': 15000 + i * 2.5,
        'FAR Qty': 1
      });
      const searchText = `${assetId} Industrial Machinery Unit #${i} CC-10${i % 10}`;

      insertFarStmt.run(
        'cl_bench',
        '2026_27',
        assetKey,
        assetId,
        i + 1,
        'batch_bench_01',
        rawRow,
        searchText,
        'uid_admin'
      );
    }
    nativeDb.exec('COMMIT;');
    console.timeEnd('FAR 10,000 Batch Insert');

    const farPopulatedStats = fs.statSync(tempDbPath);
    const farSizeBytes = farPopulatedStats.size - emptyStats.size;
    console.log(`[Storage Metric] 10,000 FAR Assets Size: ${farSizeBytes} bytes (${(farSizeBytes / (1024 * 1024)).toFixed(2)} MB)`);

    // 4. Batch Insert 10,000 Dynamic Counting Records (Option B: values_json)
    console.time('Counting Records 10,000 Batch Insert');
    nativeDb.exec('BEGIN TRANSACTION;');
    const insertCountStmt = nativeDb.prepare(
      `INSERT INTO counting_records (
         record_id, client_id, fiscal_year_id, asset_key, schema_version_id,
         auditor_uid, auditor_name_snapshot, count_date, values_json, idempotency_key
       ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
    );

    for (let i = 1; i <= 10000; i++) {
      const assetId = i.toString().padStart(6, '0');
      const assetKey = `clbench_2026_27_${assetId}_R${i + 1}`;
      const valuesJson = JSON.stringify({
        fld_cond: i % 15 === 0 ? 'Damaged' : 'Good',
        fld_qty: 1,
        fld_location: `Building ${(i % 4) + 1}, Room ${(i % 20) + 101}`,
        fld_remarks: i % 20 === 0 ? 'Tag replaced during audit' : 'Verified OK'
      });

      insertCountStmt.run(
        `rec_${i.toString().padStart(6, '0')}`,
        'cl_bench',
        '2026_27',
        assetKey,
        'sch_bench_01',
        'uid_auditor_01',
        'Lead Auditor Jane',
        '2026-11-01',
        valuesJson,
        `idemp_${i}`
      );
    }
    nativeDb.exec('COMMIT;');
    console.timeEnd('Counting Records 10,000 Batch Insert');

    // Checkpoint SQLite WAL to measure true database size
    nativeDb.exec('PRAGMA wal_checkpoint(TRUNCATE);');

    const fullStats = fs.statSync(tempDbPath);
    const totalSizeBytes = fullStats.size;
    const totalSizeMB = totalSizeBytes / (1024 * 1024);
    console.log(`[Storage Metric] Total Database Size (1 Client, 1 Full 10k Audit Cycle): ${totalSizeBytes} bytes (${totalSizeMB.toFixed(2)} MB)`);

    // 5. Query Latency Measurement
    console.time('Indexed Single Asset Lookup');
    const assetRow = nativeDb.prepare(
      'SELECT * FROM far_assets WHERE client_id = ? AND asset_id = ?'
    ).get('cl_bench', '005432');
    console.timeEnd('Indexed Single Asset Lookup');
    expect(assetRow).toBeDefined();

    // Verification of headroom
    expect(totalSizeMB).toBeLessThan(35); // Measured total is well under 35 MB!
    expect(totalSizeMB).toBeLessThan(400); // Leaves > 90% of the 400 MB usable headroom per shard!

    // Clean up temporary measurement file
    try {
      if (fs.existsSync(tempDbPath)) fs.unlinkSync(tempDbPath);
    } catch {
      // Ignore cleanup error on windows locks
    }
  });
});
