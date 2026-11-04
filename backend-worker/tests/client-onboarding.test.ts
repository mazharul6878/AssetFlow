import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import * as fs from 'node:fs';
import { createLocalTestD1, createAllMigrationsControlD1, createAllMigrationsShardD1, MockD1Database, createMockJWT } from './test-helper';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('Phase 1: Client Onboarding and Tenant Isolation', () => {
  let controlDb: MockD1Database;
  let shardDb: MockD1Database;

  const controlMigration01 = path.resolve(__dirname, '../migrations/control/0001_initial_control.sql');
  const controlMigration02 = path.resolve(__dirname, '../migrations/control/0002_owner_bootstrap.sql');
  const shardMigration01 = path.resolve(__dirname, '../migrations/shard/0001_initial_shard.sql');

  const OWNER_UID = 'uid_system_owner_999';
  const ADMIN_UID = 'uid_tenant_admin_888';
  const AUDITOR_UID = 'uid_field_auditor_777';

  beforeEach(() => {
    controlDb = createAllMigrationsControlD1();
    shardDb = createAllMigrationsShardD1();

    // Seed Owner, Admin, Auditor users in Control DB
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES 
         ('${OWNER_UID}', 'owner@assetflow.test', 'System Owner', 'System Owner', 'owner', 'Owner', 'Active'),
         ('${ADMIN_UID}', 'admin@assetflow.test', 'Tenant Admin', 'Tenant Admin', 'admin', 'Admin', 'Active'),
         ('${AUDITOR_UID}', 'auditor@assetflow.test', 'Field Auditor', 'Field Auditor', 'auditor', 'Auditor', 'Active')`
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

  it('1. System Owner creates client with initial fiscal year and creator membership', async () => {
    const env = createEnv();
    const token = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });

    const req = new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        clientId: 'client_acme_corp',
        clientName: 'Acme Corporation Ltd',
        clientCode: 'ACM',
        fiscalYear: '2026_27',
        fiscalYearLabel: '2026–27',
        industry: 'Manufacturing',
        uniqueAssetKeyHeader: 'Asset Barcode',
        contactPerson: 'Jane Doe',
        contactEmail: 'jane@acme.com',
        driveRootFolderId: 'folder_drive_123'
      })
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(201);

    const body = await res.json<any>();
    expect(body.message).toBe('Client created successfully');
    expect(body.clientId).toBe('client_acme_corp');
    expect(body.client.client_name).toBe('Acme Corporation Ltd');
    expect(body.client.client_code).toBe('ACM');
    expect(body.client.unique_asset_key_header).toBe('Asset Barcode');
    expect(body.client.drive_root_folder_id).toBe('folder_drive_123');
    expect(body.fiscalYear.fiscal_year_id).toBe('2026_27');

    // Verify Control DB clients row
    const clientRow = controlDb.getNativeDb()
      .prepare('SELECT * FROM clients WHERE client_id = ?')
      .get('client_acme_corp') as any;
    expect(clientRow).not.toBeNull();
    expect(clientRow.client_name).toBe('Acme Corporation Ltd');
    expect(clientRow.client_code).toBe('ACM');
    expect(clientRow.industry).toBe('Manufacturing');
    expect(clientRow.unique_asset_key_header).toBe('Asset Barcode');
    expect(clientRow.contact_person).toBe('Jane Doe');
    expect(clientRow.contact_email).toBe('jane@acme.com');
    expect(clientRow.status).toBe('Active');

    // Verify Control DB client_shard_routing
    const routeRow = controlDb.getNativeDb()
      .prepare('SELECT * FROM client_shard_routing WHERE client_id = ?')
      .get('client_acme_corp') as any;
    expect(routeRow).not.toBeNull();
    expect(routeRow.shard_binding).toBe('SHARD_01');
    expect(routeRow.routing_status).toBe('ACTIVE');

    // Verify Control DB client_memberships for creator
    const memberRow = controlDb.getNativeDb()
      .prepare('SELECT * FROM client_memberships WHERE client_id = ? AND user_uid = ?')
      .get('client_acme_corp', OWNER_UID) as any;
    expect(memberRow).not.toBeNull();
    expect(memberRow.role).toBe('Owner');
    expect(memberRow.has_review_authority).toBe(1);
    expect(memberRow.can_publish).toBe(1);
    expect(memberRow.can_sync_drive).toBe(1);
    expect(memberRow.account_status).toBe('Active');

    // Verify Shard DB fiscal_years row
    const fyRow = shardDb.getNativeDb()
      .prepare('SELECT * FROM fiscal_years WHERE client_id = ? AND fiscal_year_id = ?')
      .get('client_acme_corp', '2026_27') as any;
    expect(fyRow).not.toBeNull();
    expect(fyRow.label).toBe('2026–27');
    expect(fyRow.is_closed).toBe(0);
  });

  it('2. Idempotent replay of client creation returns 200 with existing client info', async () => {
    const env = createEnv();
    const token = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });

    const payload = {
      clientId: 'client_idempotent_test',
      clientName: 'Idempotent Corp',
      clientCode: 'IDM',
      fiscalYear: '2026_27',
      fiscalYearLabel: '2026–27'
    };

    const req1 = new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const res1 = await handleRequest(req1, env);
    expect(res1.status).toBe(201);

    // Replay
    const req2 = new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const res2 = await handleRequest(req2, env);
    expect(res2.status).toBe(200);
    const body2 = await res2.json<any>();
    expect(body2.clientId).toBe('client_idempotent_test');
    expect(body2.client.client_code).toBe('IDM');
  });

  it('3. Regular Auditor is forbidden from creating a Client', async () => {
    const env = createEnv();
    const token = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@assetflow.test' });

    const req = new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_auditor_illegal',
        clientName: 'Illegal Corp',
        clientCode: 'ILL'
      })
    });

    const res = await handleRequest(req, env);
    expect(res.status).toBe(403);
    const body = await res.json<any>();
    expect(body.error).toMatch(/requires Admin or Owner role/i);
  });

  it('4. System Owner can list all active clients across the system', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });
    const adminToken = createMockJWT({ sub: ADMIN_UID, email: 'admin@assetflow.test' });

    // Admin creates Client 1
    const req1 = new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_one',
        clientName: 'Client One Ltd',
        clientCode: 'CL1'
      })
    });
    expect((await handleRequest(req1, env)).status).toBe(201);

    // Owner creates Client 2
    const req2 = new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_two',
        clientName: 'Client Two Ltd',
        clientCode: 'CL2'
      })
    });
    expect((await handleRequest(req2, env)).status).toBe(201);

    // Owner lists clients: should see both
    const listReq = new Request('http://localhost/api/v1/clients', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${ownerToken}` }
    });
    const listRes = await handleRequest(listReq, env);
    expect(listRes.status).toBe(200);

    const listBody = await listRes.json<any>();
    expect(listBody.clients.length).toBe(2);
    const clientIds = listBody.clients.map((c: any) => c.client_id);
    expect(clientIds).toContain('client_one');
    expect(clientIds).toContain('client_two');
  });

  it('5. Tenant Admin only sees assigned clients when listing', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });
    const adminToken = createMockJWT({ sub: ADMIN_UID, email: 'admin@assetflow.test' });

    // Admin creates Client A
    await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ clientId: 'client_a', clientName: 'Client A', clientCode: 'CLA' })
    }), env);

    // Owner creates Client B (admin is not a member of Client B)
    await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ clientId: 'client_b', clientName: 'Client B', clientCode: 'CLB' })
    }), env);

    // Admin lists clients: should see only Client A
    const listRes = await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    }), env);
    expect(listRes.status).toBe(200);

    const listBody = await listRes.json<any>();
    expect(listBody.clients.length).toBe(1);
    expect(listBody.clients[0].client_id).toBe('client_a');
  });

  it('6. Client-scoped fiscal year listing and creation', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });

    // Create client
    await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_fy_test',
        clientName: 'FY Test Corp',
        clientCode: 'FYT',
        fiscalYear: '2025_26',
        fiscalYearLabel: '2025–26'
      })
    }), env);

    // List fiscal years for client
    const fyListRes = await handleRequest(new Request('http://localhost/api/v1/clients/client_fy_test/fiscal-years', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${ownerToken}` }
    }), env);
    expect(fyListRes.status).toBe(200);
    const fyListBody = await fyListRes.json<any>();
    expect(fyListBody.fiscalYears.length).toBe(1);
    expect(fyListBody.fiscalYears[0].fiscal_year_id).toBe('2025_26');
    expect(fyListBody.fiscalYears[0].label).toBe('2025–26');

    // Create new fiscal year for client
    const addFyRes = await handleRequest(new Request('http://localhost/api/v1/clients/client_fy_test/fiscal-years', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        fiscalYearId: '2026_27',
        label: '2026–27'
      })
    }), env);
    expect(addFyRes.status).toBe(201);

    // Verify list now has 2 fiscal years
    const fyListRes2 = await handleRequest(new Request('http://localhost/api/v1/clients/client_fy_test/fiscal-years', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${ownerToken}` }
    }), env);
    expect(fyListRes2.status).toBe(200);
    const fyListBody2 = await fyListRes2.json<any>();
    expect(fyListBody2.fiscalYears.length).toBe(2);
  });

  it('7. Get single client metadata returns client record', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });

    await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_single_get',
        clientName: 'Single Get Corp',
        clientCode: 'SGC',
        industry: 'Healthcare'
      })
    }), env);

    const getRes = await handleRequest(new Request('http://localhost/api/v1/clients/client_single_get', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${ownerToken}` }
    }), env);
    expect(getRes.status).toBe(200);
    const getBody = await getRes.json<any>();
    expect(getBody.client.client_id).toBe('client_single_get');
    expect(getBody.client.industry).toBe('Healthcare');
  });

  it('8. Conflicting client code from another user throws 409 CLIENT_CODE_CONFLICT and does not return existing client', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });
    const adminToken = createMockJWT({ sub: ADMIN_UID, email: 'admin@assetflow.test' });

    // Owner creates client with code 'XYZ'
    const res1 = await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_xyz_owner',
        clientName: 'XYZ Industries',
        clientCode: 'XYZ'
      })
    }), env);
    expect(res1.status).toBe(201);

    // Another user (Admin) attempts to create client with same code 'XYZ'
    const res2 = await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_xyz_admin',
        clientName: 'Different Name Corp',
        clientCode: 'XYZ'
      })
    }), env);
    expect(res2.status).toBe(409);
    const body2 = await res2.json<any>();
    expect(body2.code).toBe('CLIENT_CODE_CONFLICT');
    expect(body2.error).toMatch(/already exists/i);
  });

  it('9. Cross-D1 saga: Shard FY failure leaves client in SETUP_INCOMPLETE state without deleting it, and retry recovers', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });

    // Intentionally corrupt shardDb fiscal_years table temporarily to trigger a failure during shard FY creation
    shardDb.getNativeDb().exec('DROP TABLE fiscal_years;');

    const req1 = new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_saga_partial',
        clientName: 'Partial Saga Corp',
        clientCode: 'PSC'
      })
    });

    const res1 = await handleRequest(req1, env);
    // Should fail with 500 because shard step failed
    expect(res1.status).toBe(500);

    // Verify client is NOT deleted in Control DB, but left in SETUP_INCOMPLETE state
    const clientRow = controlDb.getNativeDb()
      .prepare('SELECT * FROM clients WHERE client_id = ?')
      .get('client_saga_partial') as any;
    expect(clientRow).not.toBeNull();
    expect(clientRow.setup_status).toBe('SETUP_INCOMPLETE');

    // Verify onboarding operation is logged as FAILED_RETRYABLE
    const opRow = controlDb.getNativeDb()
      .prepare('SELECT * FROM client_onboarding_operations WHERE client_id = ?')
      .get('client_saga_partial') as any;
    expect(opRow).not.toBeNull();
    expect(opRow.stage).toBe('FAILED_RETRYABLE');

    // Restore shardDb fiscal_years table to simulate repair
    const shard01Sql = fs.readFileSync(shardMigration01, 'utf8');
    shardDb.getNativeDb().exec(shard01Sql);

    // Retrying the same creation request resumes provisioning and succeeds!
    const req2 = new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_saga_partial',
        clientName: 'Partial Saga Corp',
        clientCode: 'PSC'
      })
    });

    const res2 = await handleRequest(req2, env);
    expect(res2.status).toBe(201);
    const body2 = await res2.json<any>();
    expect(body2.client.setup_status).toBe('COMPLETED');

    // Control DB client is now COMPLETED
    const clientRowAfter = controlDb.getNativeDb()
      .prepare('SELECT * FROM clients WHERE client_id = ?')
      .get('client_saga_partial') as any;
    expect(clientRowAfter.setup_status).toBe('COMPLETED');
  });

  it('10. SETUP_INCOMPLETE client is hidden from ordinary auditor list but visible to Owner with setup status', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });
    const auditorToken = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@assetflow.test' });

    // Manually insert an incomplete client and membership for auditor
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, setup_status, active_fiscal_year_id, unique_asset_key_header, created_by_uid)
       VALUES ('client_incomplete', 'Incomplete Client', 'INC', 'Tech', 'Active', 'SETUP_INCOMPLETE', '2026_27', 'Asset ID', '${OWNER_UID}')`
    ).run();

    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, assigned_by_uid)
       VALUES ('mem_inc_aud', 'client_incomplete', '${AUDITOR_UID}', 'Auditor', 'Active', '${OWNER_UID}')`
    ).run();

    // Auditor lists clients: must NOT see the setup-incomplete client
    const audRes = await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${auditorToken}` }
    }), env);
    expect(audRes.status).toBe(200);
    const audBody = await audRes.json<any>();
    expect(audBody.clients.length).toBe(0);

    // Owner lists clients: sees the setup-incomplete client and its status
    const ownerRes = await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${ownerToken}` }
    }), env);
    expect(ownerRes.status).toBe(200);
    const ownerBody = await ownerRes.json<any>();
    const found = ownerBody.clients.find((c: any) => c.client_id === 'client_incomplete');
    expect(found).not.toBeUndefined();
    expect(found.setup_status).toBe('SETUP_INCOMPLETE');
  });

  it('11. Drive folder provisioning preference without folder ID persists PENDING_AUTHORIZATION, and NOT_REQUESTED when false', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });

    // 1. Requested Drive provisioning, but disconnected (folder ID not yet available)
    const res1 = await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_drive_pending',
        clientName: 'Drive Pending Corp',
        clientCode: 'DPC',
        provisionDriveFolders: true
      })
    }), env);
    expect(res1.status).toBe(201);
    const body1 = await res1.json<any>();
    expect(body1.client.drive_provisioning_status).toBe('PENDING_AUTHORIZATION');

    // 2. Did not request Drive provisioning
    const res2 = await handleRequest(new Request('http://localhost/api/v1/clients', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: 'client_drive_none',
        clientName: 'Drive None Corp',
        clientCode: 'DNC',
        provisionDriveFolders: false
      })
    }), env);
    expect(res2.status).toBe(201);
    const body2 = await res2.json<any>();
    expect(body2.client.drive_provisioning_status).toBe('NOT_REQUESTED');
  });
});
