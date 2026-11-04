import { describe, it, expect, beforeEach } from 'vitest';
import { createAllMigrationsControlD1, createAllMigrationsShardD1, MockD1Database, createMockJWT } from './test-helper';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('Phase 6: Permanent Client & User Deletion Governance with Tombstones', () => {
  let env: Env;
  let controlDb: MockD1Database;
  let shardDb: MockD1Database;

  const OWNER_UID = 'uid_owner_del';
  const ADMIN_UID = 'uid_admin_del';
  const AUDITOR_UID = 'uid_auditor_del';
  const CLIENT_ID = 'client_delete_corp';

  let ownerToken: string;
  let adminToken: string;
  let auditorToken: string;

  beforeEach(() => {
    controlDb = createAllMigrationsControlD1();
    shardDb = createAllMigrationsShardD1();

    // Seed users
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES 
         ('${OWNER_UID}', 'owner@del.test', 'Owner Del', 'Owner Del', 'owner', 'Owner', 'Active'),
         ('${ADMIN_UID}', 'admin@del.test', 'Admin Del', 'Admin Del', 'admin', 'Admin', 'Active'),
         ('${AUDITOR_UID}', 'auditor@del.test', 'Auditor Del', 'Auditor Del', 'auditor', 'Auditor', 'Active')`
    ).run();

    // Seed client
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, setup_status, active_fiscal_year_id, unique_asset_key_header, created_by_uid)
       VALUES ('${CLIENT_ID}', 'Delete Corp', 'DEL-CORP', 'Audit', 'Active', 'COMPLETED', '2026_27', 'Asset ID', '${OWNER_UID}')`
    ).run();

    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES ('${CLIENT_ID}', 'SHARD_01', 'shard-01-db', 'ACTIVE')`
    ).run();

    // Seed memberships
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, has_review_authority, can_publish, can_sync_drive, assigned_by_uid)
       VALUES 
         ('m_owner', '${CLIENT_ID}', '${OWNER_UID}', 'Owner', 'Active', 1, 1, 1, '${OWNER_UID}'),
         ('m_admin', '${CLIENT_ID}', '${ADMIN_UID}', 'Admin', 'Active', 1, 1, 1, '${OWNER_UID}'),
         ('m_auditor', '${CLIENT_ID}', '${AUDITOR_UID}', 'Auditor', 'Active', 0, 0, 0, '${ADMIN_UID}')`
    ).run();

    // Seed shard data
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label)
       VALUES ('${CLIENT_ID}', '2026_27', 'FY 2026-27')`
    ).run();

    shardDb.getNativeDb().prepare(
      `INSERT INTO far_assets (client_id, fiscal_year_id, asset_key, asset_id, source_row, import_batch_id, raw_row_json, search_text, imported_by_uid)
       VALUES ('${CLIENT_ID}', '2026_27', 'AST_DEL_1', '001', 1, 'imp_del', '{}', 'AST_DEL_1', '${OWNER_UID}')`
    ).run();

    env = {
      CONTROL_DB: controlDb as any,
      SHARD_01: shardDb as any,
      ENVIRONMENT: 'test',
      FIREBASE_PROJECT_ID: 'spherical-booth-366013',
      SHARD_ALLOWLIST: 'SHARD_01',
      JWT_VERIFICATION_MOCK: 'true'
    };

    ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@del.test' });
    adminToken = createMockJWT({ sub: ADMIN_UID, email: 'admin@del.test' });
    auditorToken = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@del.test' });
  });

  it('1. Non-owner admin cannot delete client (403 Forbidden)', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/delete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${adminToken}`
      },
      body: JSON.stringify({ confirmClientCode: 'DEL-CORP' })
    }), env);

    expect(res.status).toBe(403);
    const body = await res.json<any>();
    expect(body.error).toContain('Only the System Owner');
  });

  it('2. Owner deleting client without exact client code receives 400 CONFIRMATION_REQUIRED', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/delete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${ownerToken}`
      },
      body: JSON.stringify({ confirmClientCode: 'WRONG-CODE' })
    }), env);

    expect(res.status).toBe(400);
    const body = await res.json<any>();
    expect(body.error).toBe('CONFIRMATION_REQUIRED');
    expect(body.requiredCode).toBe('DEL-CORP');
  });

  it('3. Owner with exact client code permanently deletes client: removes shard & control data, creates tombstone', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/delete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${ownerToken}`
      },
      body: JSON.stringify({ confirmClientCode: 'DEL-CORP', reason: 'End of engagement audit' })
    }), env);

    expect(res.status).toBe(200);
    const body = await res.json<any>();
    expect(body.message).toContain('permanently deleted');

    // Verify client removed from clients table
    const clientRow = controlDb.getNativeDb().prepare('SELECT * FROM clients WHERE client_id = ?').get(CLIENT_ID);
    expect(clientRow).toBeUndefined();

    // Verify memberships removed
    const memberRows = controlDb.getNativeDb().prepare('SELECT * FROM client_memberships WHERE client_id = ?').all(CLIENT_ID);
    expect(memberRows.length).toBe(0);

    // Verify shard data deleted
    const farRows = shardDb.getNativeDb().prepare('SELECT * FROM far_assets WHERE client_id = ?').all(CLIENT_ID);
    expect(farRows.length).toBe(0);

    // Verify tombstone in deleted_identities
    const tombstone = controlDb.getNativeDb().prepare(
      "SELECT * FROM deleted_identities WHERE subject_type = 'CLIENT' AND original_identifier = 'DEL-CORP'"
    ).get() as any;
    expect(tombstone).toBeDefined();
    expect(tombstone.deleted_by_uid).toBe(OWNER_UID);
    expect(tombstone.deletion_reason).toBe('End of engagement audit');
  });

  it('4. Attempting to delete the System Owner account is strictly blocked (403 Security Error)', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/users/${OWNER_UID}/delete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${ownerToken}`
      },
      body: JSON.stringify({ confirmEmail: 'owner@del.test' })
    }), env);

    expect(res.status).toBe(403);
    const body = await res.json<any>();
    expect(body.error).toContain('System Owner');
  });

  it('5. Owner deleting user without matching email confirmation receives 400 CONFIRMATION_REQUIRED', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/users/${AUDITOR_UID}/delete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${ownerToken}`
      },
      body: JSON.stringify({ confirmEmail: 'mismatch@del.test' })
    }), env);

    expect(res.status).toBe(400);
    const body = await res.json<any>();
    expect(body.error).toBe('CONFIRMATION_REQUIRED');
    expect(body.requiredEmail).toBe('auditor@del.test');
  });

  it('6. Owner with matching email permanently deletes user: removes from users & memberships, creates tombstone', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/users/${AUDITOR_UID}/delete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${ownerToken}`
      },
      body: JSON.stringify({ confirmEmail: 'auditor@del.test', reason: 'Employee offboarding' })
    }), env);

    expect(res.status).toBe(200);
    const body = await res.json<any>();
    expect(body.message).toContain('permanently deleted');

    // Verify user removed from users table
    const userRow = controlDb.getNativeDb().prepare('SELECT * FROM users WHERE uid = ?').get(AUDITOR_UID);
    expect(userRow).toBeUndefined();

    // Verify memberships removed
    const memberRows = controlDb.getNativeDb().prepare('SELECT * FROM client_memberships WHERE user_uid = ?').all(AUDITOR_UID);
    expect(memberRows.length).toBe(0);

    // Verify tombstone in deleted_identities
    const tombstone = controlDb.getNativeDb().prepare(
      "SELECT * FROM deleted_identities WHERE subject_type = 'USER' AND original_identifier = 'auditor@del.test'"
    ).get() as any;
    expect(tombstone).toBeDefined();
    expect(tombstone.deleted_by_uid).toBe(OWNER_UID);
    expect(tombstone.deletion_reason).toBe('Employee offboarding');
  });
});
