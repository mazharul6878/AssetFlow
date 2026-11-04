import { describe, it, expect, beforeEach } from 'vitest';
import { createAllMigrationsControlD1, createAllMigrationsShardD1, MockD1Database, createMockJWT } from './test-helper';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('Phase 3.5: Found Not In FAR (Unlisted Assets) D1 Persistence & Client Isolation', () => {
  let controlDb: MockD1Database;
  let shardDb: MockD1Database;

  const OWNER_UID = 'uid_owner_fnif';
  const AUDITOR_UID = 'uid_auditor_fnif';
  const STRANGER_UID = 'uid_stranger_fnif';
  const CLIENT_ID_A = 'client_fnif_alpha';
  const CLIENT_ID_B = 'client_fnif_beta';

  beforeEach(() => {
    controlDb = createAllMigrationsControlD1();
    shardDb = createAllMigrationsShardD1();

    // Seed users
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES 
         ('${OWNER_UID}', 'owner@fnif.test', 'Owner FNIF', 'Owner FNIF', 'owner', 'Owner', 'Active'),
         ('${AUDITOR_UID}', 'auditor@fnif.test', 'Auditor FNIF', 'Auditor FNIF', 'auditor', 'Auditor', 'Active'),
         ('${STRANGER_UID}', 'stranger@fnif.test', 'Stranger', 'Stranger', 'stranger', 'Auditor', 'Active')`
    ).run();

    // Seed Client A & B
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, setup_status, active_fiscal_year_id, unique_asset_key_header, created_by_uid)
       VALUES 
         ('${CLIENT_ID_A}', 'Client Alpha', 'CLA', 'Tech', 'Active', 'COMPLETED', '2026_27', 'Tag Number', '${OWNER_UID}'),
         ('${CLIENT_ID_B}', 'Client Beta', 'CLB', 'Tech', 'Active', 'COMPLETED', '2026_27', 'Tag Number', '${OWNER_UID}')`
    ).run();

    // Shard routing
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES 
         ('${CLIENT_ID_A}', 'SHARD_01', 'shard-01-db', 'ACTIVE'),
         ('${CLIENT_ID_B}', 'SHARD_01', 'shard-01-db', 'ACTIVE')`
    ).run();

    // Memberships: Auditor in Client A only
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, has_review_authority, can_publish, can_sync_drive, assigned_by_uid)
       VALUES 
         ('mem_fnif_owner_a', '${CLIENT_ID_A}', '${OWNER_UID}', 'Owner', 'Active', 1, 1, 1, '${OWNER_UID}'),
         ('mem_fnif_auditor_a', '${CLIENT_ID_A}', '${AUDITOR_UID}', 'Auditor', 'Active', 0, 0, 0, '${OWNER_UID}')`
    ).run();

    // Seed fiscal year in Shard DB
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label, is_closed)
       VALUES 
         ('${CLIENT_ID_A}', '2026_27', '2026–27', 0),
         ('${CLIENT_ID_B}', '2026_27', '2026–27', 0)`
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
    } as any;
  }

  it('1. Auditor successfully submits an unlisted asset ("Found Not In FAR") record to D1 shard', async () => {
    const env = createEnv();
    const token = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@fnif.test' });

    const payload = {
      tempId: 'UNLISTED_A_001',
      fiscalYearId: '2026_27',
      description: 'Emergency Generator 50kW',
      rawValuesJson: JSON.stringify({
        assetClass: 'Plant & Machinery',
        buildingSite: 'Factory A',
        floor: 'Ground',
        condition: 'Good',
        found: 'Yes'
      }),
      countDate: '2026-11-03'
    };

    const req = new Request(`https://worker.local/api/v1/clients/${CLIENT_ID_A}/found-not-in-far`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(payload)
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(201);

    const body = await res.json<any>();
    expect(body.message).toBe('Unlisted asset recorded successfully');
    expect(body.record.temp_id).toBe('UNLISTED_A_001');
    expect(body.record.client_id).toBe(CLIENT_ID_A);
    expect(body.record.description).toBe('Emergency Generator 50kW');
    expect(body.record.auditor_uid).toBe(AUDITOR_UID);

    // Verify stored directly in D1 shard database
    const stored = shardDb.getNativeDb().prepare(
      `SELECT * FROM found_not_in_far WHERE client_id = ? AND temp_id = ?`
    ).get(CLIENT_ID_A, 'UNLISTED_A_001') as any;

    expect(stored).toBeDefined();
    expect(stored.temp_id).toBe('UNLISTED_A_001');
    expect(stored.description).toBe('Emergency Generator 50kW');
    expect(JSON.parse(stored.raw_values_json).buildingSite).toBe('Factory A');
  });

  it('2. Submitting without description returns 400 Bad Request', async () => {
    const env = createEnv();
    const token = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@fnif.test' });

    const req = new Request(`https://worker.local/api/v1/clients/${CLIENT_ID_A}/found-not-in-far`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        tempId: 'UNLISTED_EMPTY',
        description: ''
      })
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(400);
    const body = await res.json<any>();
    expect(body.error).toContain('description');
  });

  it('3. Submitting with invalid JSON in rawValuesJson returns 400 Bad Request', async () => {
    const env = createEnv();
    const token = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@fnif.test' });

    const req = new Request(`https://worker.local/api/v1/clients/${CLIENT_ID_A}/found-not-in-far`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        tempId: 'UNLISTED_BAD_JSON',
        description: 'Server Rack',
        rawValuesJson: '{not-json'
      })
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(400);
    const body = await res.json<any>();
    expect(body.error).toContain('Invalid JSON');
  });

  it('4. Listing unlisted assets returns only records belonging to the requested client and cycle', async () => {
    const env = createEnv();
    const token = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@fnif.test' });

    // Seed records directly into shard DB for Client A and Client B
    shardDb.getNativeDb().prepare(
      `INSERT INTO found_not_in_far (temp_id, client_id, fiscal_year_id, description, raw_values_json, auditor_uid, auditor_name, count_date)
       VALUES 
         ('UNLISTED_A_1', '${CLIENT_ID_A}', '2026_27', 'Chair A', '{}', '${AUDITOR_UID}', 'Auditor', '2026-11-01'),
         ('UNLISTED_A_2', '${CLIENT_ID_A}', '2026_27', 'Desk A', '{}', '${AUDITOR_UID}', 'Auditor', '2026-11-02'),
         ('UNLISTED_B_1', '${CLIENT_ID_B}', '2026_27', 'Printer B', '{}', '${OWNER_UID}', 'Owner', '2026-11-01')`
    ).run();

    const req = new Request(`https://worker.local/api/v1/clients/${CLIENT_ID_A}/found-not-in-far?fiscalYearId=2026_27`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(200);

    const body = await res.json<any>();
    expect(body.records).toHaveLength(2);
    expect(body.records.map((r: any) => r.temp_id)).toEqual(['UNLISTED_A_1', 'UNLISTED_A_2']);
  });

  it('5. User without membership in Client receives 403 Forbidden on both POST and GET', async () => {
    const env = createEnv();
    const strangerToken = createMockJWT({ sub: STRANGER_UID, email: 'stranger@fnif.test' });

    // GET attempt on Client A by non-member
    const getReq = new Request(`https://worker.local/api/v1/clients/${CLIENT_ID_A}/found-not-in-far`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${strangerToken}` }
    });
    const getRes = await handleRequest(getReq, env);
    expect(getRes.status).toBe(403);

    // POST attempt on Client A by non-member
    const postReq = new Request(`https://worker.local/api/v1/clients/${CLIENT_ID_A}/found-not-in-far`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${strangerToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ tempId: 'HACK', description: 'Unauthorized' })
    });
    const postRes = await handleRequest(postReq, env);
    expect(postRes.status).toBe(403);
  });
});
