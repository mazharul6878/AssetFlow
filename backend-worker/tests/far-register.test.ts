import { describe, it, expect, beforeEach } from 'vitest';
import { createAllMigrationsControlD1, createAllMigrationsShardD1, MockD1Database, createMockJWT } from './test-helper';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('Phase 3: Asset Register Batch Import, D1 Persistence & Paginated Search', () => {
  let controlDb: MockD1Database;
  let shardDb: MockD1Database;

  const OWNER_UID = 'uid_owner_reg';
  const ADMIN_UID = 'uid_admin_reg';
  const AUDITOR_UID = 'uid_auditor_reg';
  const CLIENT_ID = 'client_gamma_corp';

  beforeEach(() => {
    controlDb = createAllMigrationsControlD1();
    shardDb = createAllMigrationsShardD1();

    // Seed users
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES 
         ('${OWNER_UID}', 'owner@gamma.test', 'Owner Gamma', 'Owner Gamma', 'owner', 'Owner', 'Active'),
         ('${ADMIN_UID}', 'admin@gamma.test', 'Admin Gamma', 'Admin Gamma', 'admin', 'Admin', 'Active'),
         ('${AUDITOR_UID}', 'auditor@gamma.test', 'Auditor Gamma', 'Auditor Gamma', 'auditor', 'Auditor', 'Active')`
    ).run();

    // Seed client
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, setup_status, active_fiscal_year_id, unique_asset_key_header, created_by_uid)
       VALUES ('${CLIENT_ID}', 'Gamma Corp', 'GAM', 'Energy', 'Active', 'COMPLETED', '2026_27', 'Tag Number', '${OWNER_UID}')`
    ).run();

    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES ('${CLIENT_ID}', 'SHARD_01', 'shard-01-db', 'ACTIVE')`
    ).run();

    // Memberships
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, has_review_authority, can_publish, can_sync_drive, assigned_by_uid)
       VALUES 
         ('mem_gam_owner', '${CLIENT_ID}', '${OWNER_UID}', 'Owner', 'Active', 1, 1, 1, '${OWNER_UID}'),
         ('mem_gam_admin', '${CLIENT_ID}', '${ADMIN_UID}', 'Admin', 'Active', 1, 1, 1, '${OWNER_UID}'),
         ('mem_gam_auditor', '${CLIENT_ID}', '${AUDITOR_UID}', 'Auditor', 'Active', 0, 0, 0, '${ADMIN_UID}')`
    ).run();

    // Seed fiscal year in Shard DB
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label, is_closed)
       VALUES ('${CLIENT_ID}', '2026_27', '2026–27', 0)`
    ).run();
  });

  function createEnv(): Env {
    return {
      CONTROL_DB: controlDb as any,
      SHARD_01: shardDb as any,
      ENVIRONMENT: 'test',
      FIREBASE_PROJECT_ID: 'spherical-booth-366013',
      SHARD_ALLOWLIST: 'SHARD_01',
      JWT_VERIFICATION_MOCK: 'true'
    };
  }

  it('1. Admin imports a batch of FAR assets; leading zeros in asset_id preserved as TEXT', async () => {
    const env = createEnv();
    const adminToken = createMockJWT({ sub: ADMIN_UID, email: 'admin@gamma.test' });

    const assets = [
      {
        assetKey: `${CLIENT_ID}_2026_27_000101_R2`,
        assetId: '000101', // Leading zeros!
        sourceRow: 2,
        rawRowJson: JSON.stringify({ 'Tag Number': '000101', 'Description': 'Diesel Generator 500kVA', 'Location': 'Building A' }),
        searchText: '000101 Diesel Generator 500kVA Building A'
      },
      {
        assetKey: `${CLIENT_ID}_2026_27_000102_R3`,
        assetId: '000102',
        sourceRow: 3,
        rawRowJson: JSON.stringify({ 'Tag Number': '000102', 'Description': 'HVAC Chiller Unit', 'Location': 'Roof' }),
        searchText: '000102 HVAC Chiller Unit Roof'
      },
      {
        assetKey: `${CLIENT_ID}_2026_27_000103_R4`,
        assetId: '000103',
        sourceRow: 4,
        rawRowJson: JSON.stringify({ 'Tag Number': '000103', 'Description': 'Solar Inverter 100kW', 'Location': 'Building B' }),
        searchText: '000103 Solar Inverter 100kW Building B'
      }
    ];

    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/far/import`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fiscalYearId: '2026_27',
        importBatchId: 'batch_gamma_01',
        assets
      })
    }), env);

    expect(res.status).toBe(201);
    const body = await res.json<any>();
    expect(body.importedCount).toBe(3);
    expect(body.importBatchId).toBe('batch_gamma_01');

    // Verify leading zeros in SQLite Shard DB
    const row = shardDb.getNativeDb()
      .prepare('SELECT * FROM far_assets WHERE client_id = ? AND asset_id = ?')
      .get(CLIENT_ID, '000101') as any;
    expect(row).not.toBeNull();
    expect(row.asset_id).toBe('000101');
    expect(typeof row.asset_id).toBe('string');
    expect(row.source_row).toBe(2);
    expect(JSON.parse(row.raw_row_json)['Description']).toBe('Diesel Generator 500kVA');
  });

  it('2. Auditor cannot import FAR assets (403 Forbidden)', async () => {
    const env = createEnv();
    const auditorToken = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@gamma.test' });

    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/far/import`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${auditorToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        assets: [{ assetKey: 'k1', assetId: '01', sourceRow: 2, rawRowJson: '{}', searchText: 'test' }]
      })
    }), env);

    expect(res.status).toBe(403);
  });

  it('3. Batch import rejects payloads exceeding 500 items limit', async () => {
    const env = createEnv();
    const adminToken = createMockJWT({ sub: ADMIN_UID, email: 'admin@gamma.test' });

    const oversized = Array.from({ length: 501 }, (_, i) => ({
      assetKey: `k_${i}`,
      assetId: `${i}`,
      sourceRow: i + 1,
      rawRowJson: '{}',
      searchText: `item ${i}`
    }));

    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/far/import`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ assets: oversized })
    }), env);

    expect(res.status).toBe(400);
    const body = await res.json<any>();
    expect(body.error).toMatch(/exceeds maximum limit of 500/i);
  });

  it('4. Paginated search: Filters by text query and supports cursor pagination', async () => {
    const env = createEnv();
    const auditorToken = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@gamma.test' });

    // Seed 10 assets in Shard DB
    const nativeDb = shardDb.getNativeDb();
    const insertStmt = nativeDb.prepare(
      `INSERT INTO far_assets (client_id, fiscal_year_id, asset_key, asset_id, source_row, import_batch_id, raw_row_json, search_text, imported_by_uid)
       VALUES (?, '2026_27', ?, ?, ?, 'batch_test', '{}', ?, '${ADMIN_UID}')`
    );

    for (let i = 1; i <= 10; i++) {
      const id = i.toString().padStart(4, '0');
      const key = `GAM_2026_27_${id}`;
      const desc = i <= 5 ? `Forklift Truck Unit #${i}` : `Pallet Jack Unit #${i}`;
      insertStmt.run(CLIENT_ID, key, id, i, `${id} ${desc}`);
    }

    // Search query for "Forklift"
    const searchRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/far/assets?fiscalYearId=2026_27&q=Forklift`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${auditorToken}` }
    }), env);

    expect(searchRes.status).toBe(200);
    const searchBody = await searchRes.json<any>();
    expect(searchBody.assets.length).toBe(5);
    expect(searchBody.assets.every((a: any) => a.search_text.includes('Forklift'))).toBe(true);

    // Cursor pagination with limit = 3
    const page1Res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/far/assets?fiscalYearId=2026_27&limit=3`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${auditorToken}` }
    }), env);
    expect(page1Res.status).toBe(200);
    const page1Body = await page1Res.json<any>();
    expect(page1Body.assets.length).toBe(3);
    expect(page1Body.nextCursor).toBeDefined();

    // Fetch page 2 using cursor
    const page2Res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/far/assets?fiscalYearId=2026_27&limit=3&cursor=${page1Body.nextCursor}`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${auditorToken}` }
    }), env);
    expect(page2Res.status).toBe(200);
    const page2Body = await page2Res.json<any>();
    expect(page2Body.assets.length).toBe(3);
    expect(page2Body.assets[0].asset_key > page1Body.nextCursor).toBe(true);
  });
});
