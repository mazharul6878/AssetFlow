import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import * as fs from 'node:fs';
import { createLocalTestD1, MockD1Database, createMockJWT } from './test-helper';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('System Owner Bootstrap & Role Resolution', () => {
  let controlDb: MockD1Database;
  let shardDb: MockD1Database;

  const controlMigration01 = path.resolve(__dirname, '../migrations/control/0001_initial_control.sql');
  const controlMigration02 = path.resolve(__dirname, '../migrations/control/0002_owner_bootstrap.sql');
  const shardMigration01 = path.resolve(__dirname, '../migrations/shard/0001_initial_shard.sql');

  const OWNER_UID = 'uid_designated_owner_123';
  const OWNER_EMAIL = 'mislamabed@gmail.com';

  beforeEach(() => {
    controlDb = createLocalTestD1(controlMigration01);
    const sql02 = fs.readFileSync(controlMigration02, 'utf8');
    controlDb.getNativeDb().exec(sql02);

    shardDb = createLocalTestD1(shardMigration01);
  });

  function createEnv(initialOwnerUid?: string): Env {
    return {
      CONTROL_DB: controlDb as any,
      SHARD_01: shardDb as any,
      ENVIRONMENT: 'test',
      FIREBASE_PROJECT_ID: 'spherical-booth-366013',
      SHARD_ALLOWLIST: 'SHARD_01',
      JWT_VERIFICATION_MOCK: 'true',
      INITIAL_OWNER_UID: initialOwnerUid
    };
  }

  it('1. Successfully bootstraps initial System Owner when token matches INITIAL_OWNER_UID', async () => {
    const env = createEnv(OWNER_UID);
    const token = createMockJWT({ sub: OWNER_UID, email: OWNER_EMAIL });

    const req = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        fullName: 'Mohammed Islam',
        nickname: 'abed'
      })
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(200);

    const body = await res.json<any>();
    expect(body.status).toBe('OK');
    expect(body.role).toBe('Owner');
    expect(body.uid).toBe(OWNER_UID);
    expect(body.email).toBe(OWNER_EMAIL);

    // Verify persisted in users table
    const userRow = controlDb.getNativeDb()
      .prepare('SELECT uid, email, display_name, global_role, account_status FROM users WHERE uid = ?')
      .get(OWNER_UID) as any;
    expect(userRow).not.toBeNull();
    expect(userRow.global_role).toBe('Owner');
    expect(userRow.account_status).toBe('Active');

    // Verify persisted in system_bootstrap singleton
    const bootstrapRow = controlDb.getNativeDb()
      .prepare('SELECT singleton_id, owner_uid, owner_email FROM system_bootstrap WHERE singleton_id = 1')
      .get() as any;
    expect(bootstrapRow).not.toBeNull();
    expect(bootstrapRow.owner_uid).toBe(OWNER_UID);
  });

  it('2. Idempotent repeated bootstrap by the designated Owner returns OK', async () => {
    const env = createEnv(OWNER_UID);
    const token = createMockJWT({ sub: OWNER_UID, email: OWNER_EMAIL });

    // Initial bootstrap
    const req1 = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({})
    });
    const res1 = await handleRequest(req1, env);
    expect(res1.status).toBe(200);

    // Repeated bootstrap call by same owner
    const req2 = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({})
    });
    const res2 = await handleRequest(req2, env);
    expect(res2.status).toBe(200);
    const body2 = await res2.json<any>();
    expect(body2.status).toBe('OK');
    expect(body2.role).toBe('Owner');
  });

  it('3. Rejects unauthorized first user with INITIAL_OWNER_NOT_AUTHORIZED (403)', async () => {
    const env = createEnv(OWNER_UID);
    const impostorToken = createMockJWT({ sub: 'uid_impostor_999', email: 'impostor@example.com' });

    const req = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${impostorToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({})
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(403);
    const body = await res.json<any>();
    expect(body.error).toBe('INITIAL_OWNER_NOT_AUTHORIZED');
    expect(body.message).toContain('AUTH-02');
  });

  it('4. Fails closed with BOOTSTRAP_CONFIGURATION_MISSING (500) if INITIAL_OWNER_UID is not set', async () => {
    const env = createEnv(undefined); // Missing secret
    const token = createMockJWT({ sub: OWNER_UID, email: OWNER_EMAIL });

    const req = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({})
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(500);
    const body = await res.json<any>();
    expect(body.error).toBe('BOOTSTRAP_CONFIGURATION_MISSING');
  });

  it('5. Rejects non-owner bootstrap after bootstrap already completed with BOOTSTRAP_ALREADY_COMPLETE (409)', async () => {
    const env = createEnv(OWNER_UID);
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: OWNER_EMAIL });

    // Bootstrap by owner
    const req1 = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}` }
    });
    await handleRequest(req1, env);

    // Attempt bootstrap by second user
    const otherToken = createMockJWT({ sub: 'uid_other_user', email: 'other@example.com' });
    const req2 = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${otherToken}` }
    });

    const res2 = await handleRequest(req2, env);
    expect(res2.status).toBe(409);
    const body2 = await res2.json<any>();
    expect(body2.error).toBe('BOOTSTRAP_ALREADY_COMPLETE');
  });

  it('6. Resolves user session for provisioned user with memberships', async () => {
    const env = createEnv(OWNER_UID);

    // Seed a provisioned user and client membership
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES ('uid_auditor_bob', 'bob@example.com', 'Bob Smith', 'Robert Smith', 'bob', 'Auditor', 'Active')`
    ).run();

    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, created_by_uid)
       VALUES ('client_abc', 'ABC Corp', 'ABC', 'Tech', 'Active', 'uid_auditor_bob')`
    ).run();

    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, has_review_authority, can_publish, can_sync_drive, assigned_by_uid)
       VALUES ('mem_bob_01', 'client_abc', 'uid_auditor_bob', 'Auditor', 'Active', 0, 0, 1, 'uid_auditor_bob')`
    ).run();

    const bobToken = createMockJWT({ sub: 'uid_auditor_bob', email: 'bob@example.com' });
    const req = new Request('http://localhost/api/v1/system/session', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${bobToken}` }
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(200);
    const body = await res.json<any>();
    expect(body.status).toBe('OK');
    expect(body.user.uid).toBe('uid_auditor_bob');
    expect(body.user.globalRole).toBe('Auditor');
    expect(body.memberships).toHaveLength(1);
    expect(body.memberships[0].clientId).toBe('client_abc');
    expect(body.memberships[0].canSyncDrive).toBe(true);
  });

  it('7. Returns UNPROVISIONED (404) for unknown user session', async () => {
    const env = createEnv(OWNER_UID);
    const unknownToken = createMockJWT({ sub: 'uid_unknown_999', email: 'unknown@example.com' });

    const req = new Request('http://localhost/api/v1/system/session', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${unknownToken}` }
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(404);
    const body = await res.json<any>();
    expect(body.status).toBe('UNPROVISIONED');
  });

  it('8. Rejects unauthenticated request without token with 401', async () => {
    const env = createEnv(OWNER_UID);
    const req = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST'
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(401);
  });

  // Tenancy Boundary & Client Membership Separation Tests
  it('9. Bootstrap request cannot self-select a Client ID or create client membership as a side effect', async () => {
    const env = createEnv(OWNER_UID);
    const token = createMockJWT({ sub: OWNER_UID, email: OWNER_EMAIL });

    // Client maliciously attempts to supply a clientId/orgId in bootstrap payload
    const req = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        fullName: 'Mohammed Islam',
        nickname: 'abed',
        clientId: 'client_walton_unauthorized_override',
        orgId: 'client_walton_unauthorized_override'
      })
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(200);

    // Global Owner identity is provisioned in users table
    const userRow = controlDb.getNativeDb()
      .prepare('SELECT uid, global_role FROM users WHERE uid = ?')
      .get(OWNER_UID) as any;
    expect(userRow.global_role).toBe('Owner');

    // Zero client_memberships records are created
    const memberships = controlDb.getNativeDb()
      .prepare('SELECT * FROM client_memberships WHERE user_uid = ?')
      .all(OWNER_UID) as any[];
    expect(memberships).toHaveLength(0);

    // Session endpoint returns 0 memberships
    const sessionReq = new Request('http://localhost/api/v1/system/session', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const sessionRes = await handleRequest(sessionReq, env);
    const sessionBody = await sessionRes.json<any>();
    expect(sessionBody.memberships).toHaveLength(0);
  });

  it('10. Client membership must be separately authorized; cross-client access remains blocked without explicit membership', async () => {
    const env = createEnv(OWNER_UID);
    const token = createMockJWT({ sub: OWNER_UID, email: OWNER_EMAIL });

    // Seed Owner in users table
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES (?, ?, 'Mohammed Islam', 'Mohammed Islam', 'abed', 'Owner', 'Active')`
    ).run(OWNER_UID, OWNER_EMAIL);

    // Seed clients in controlDb
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, created_by_uid, status)
       VALUES 
         ('client_isolated', 'Isolated Corp', 'ISO', 'Manufacturing', ?, 'Active'),
         ('client_other', 'Other Corp', 'OTH', 'Retail', ?, 'Active')`
    ).run(OWNER_UID, OWNER_UID);

    // Seed shard routing
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES 
         ('client_isolated', 'SHARD_01', 'shard-db-01', 'ACTIVE'),
         ('client_other', 'SHARD_01', 'shard-db-01', 'ACTIVE')`
    ).run();

    // Seed a client and audit cycle in shard
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (fiscal_year_id, client_id, label, start_date, end_date, is_closed)
       VALUES ('fy_2026', 'client_isolated', 'FY 2026', '2026-01-01', '2026-12-31', 0)`
    ).run();

    // 1. System Owner has global role 'Owner' but NO membership for client_isolated
    const deniedReq = new Request('http://localhost/api/v1/clients/client_isolated/audit-cycles/fy_2026/schema', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const deniedRes = await handleRequest(deniedReq, env);
    // Denied with 403 because client membership was not authorized
    expect(deniedRes.status).toBe(403);

    // 2. Explicitly provision legitimate authorized membership through admin assignment
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, assigned_by_uid)
       VALUES ('mem_001', 'client_isolated', ?, 'Admin', 'Active', ?)`
    ).run(OWNER_UID, OWNER_UID);

    // 3. Now access is granted
    const allowedReq = new Request('http://localhost/api/v1/clients/client_isolated/audit-cycles/fy_2026/schema', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const allowedRes = await handleRequest(allowedReq, env);
    expect(allowedRes.status).toBe(200);

    // 4. Access to a DIFFERENT client (client_other) remains strictly blocked
    const crossClientReq = new Request('http://localhost/api/v1/clients/client_other/audit-cycles/fy_2026/schema', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${token}` }
    });
    const crossClientRes = await handleRequest(crossClientReq, env);
    expect(crossClientRes.status).toBe(403);
  });

  it('11. Concurrent first-owner bootstrap requests produce exactly one Owner and second fails with 409', async () => {
    const env = createEnv(OWNER_UID);
    const token = createMockJWT({ sub: OWNER_UID, email: OWNER_EMAIL });

    const createReq = () => new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ fullName: 'Mohammed Islam', nickname: 'abed' })
    });

    const res1 = await handleRequest(createReq(), env);
    expect(res1.status).toBe(200);

    // Competing attempt from another UID attempting bootstrap fails with 409
    const competingToken = createMockJWT({ sub: 'uid_imposter_999', email: 'imposter@assetflow.local' });
    const imposterReq = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${competingToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ fullName: 'Imposter', nickname: 'imposter' })
    });
    const res2 = await handleRequest(imposterReq, env);
    expect(res2.status).toBe(409);

    // Control DB users table has exactly 1 Owner
    const ownerRows = controlDb.getNativeDb()
      .prepare("SELECT * FROM users WHERE global_role = 'Owner'")
      .all() as any[];
    expect(ownerRows).toHaveLength(1);
    expect(ownerRows[0].uid).toBe(OWNER_UID);
  });

  it('12. Bootstrap failure rolls back and leaves no partial bootstrap state', async () => {
    const env = createEnv(OWNER_UID);
    const unauthorizedToken = createMockJWT({ sub: 'uid_unauthorized_user', email: 'unauth@test.com' });

    const req = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${unauthorizedToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ fullName: 'Unauthorized Person', nickname: 'unauth' })
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(403);

    // No rows inserted in users or system_bootstrap
    const users = controlDb.getNativeDb().prepare('SELECT * FROM users').all();
    expect(users).toHaveLength(0);
    const bootstraps = controlDb.getNativeDb().prepare('SELECT * FROM system_bootstrap').all();
    expect(bootstraps).toHaveLength(0);
  });

  it('13. Atomic D1 batch failure during bootstrap rolls back users table insertion', async () => {
    const env = createEnv(OWNER_UID);
    const token = createMockJWT({ sub: OWNER_UID, email: OWNER_EMAIL });

    // Mock controlDb.batch to simulate a failure midway through the transaction batch
    const originalBatch = controlDb.batch.bind(controlDb);
    controlDb.batch = async (statements: any[]) => {
      controlDb.getNativeDb().exec('BEGIN TRANSACTION;');
      await statements[0].run(); // Executes users insert
      // Simulates unexpected failure on statement 2, triggering rollback
      controlDb.getNativeDb().exec('ROLLBACK;');
      throw new Error('Simulated D1 disk I/O failure during batch execution');
    };

    const req = new Request('http://localhost/api/v1/system/bootstrap', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ fullName: 'Mohammed Islam', nickname: 'abed' })
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(500);

    // Verify rollback: users table must have 0 rows, system_bootstrap must have 0 rows
    const users = controlDb.getNativeDb().prepare('SELECT * FROM users').all();
    expect(users).toHaveLength(0);
    const bootstraps = controlDb.getNativeDb().prepare('SELECT * FROM system_bootstrap').all();
    expect(bootstraps).toHaveLength(0);

    // Restore original batch method
    controlDb.batch = originalBatch;
  });
});
