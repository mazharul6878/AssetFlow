import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import * as fs from 'node:fs';
import { createAllMigrationsControlD1, createAllMigrationsShardD1, MockD1Database, createMockJWT } from './test-helper';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('Phase 2: User Governance, Roles, Client Assignments & Access Requests', () => {
  let controlDb: MockD1Database;
  let shardDb: MockD1Database;

  const OWNER_UID = 'uid_owner_master';
  const ADMIN1_UID = 'uid_admin_primary';
  const ADMIN2_UID = 'uid_admin_secondary';
  const AUDITOR1_UID = 'uid_auditor_field1';
  const AUDITOR2_UID = 'uid_auditor_field2';
  const CLIENT_ID = 'client_beta_corp';

  beforeEach(() => {
    controlDb = createAllMigrationsControlD1();
    shardDb = createAllMigrationsShardD1();

    // Seed users
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES 
         ('${OWNER_UID}', 'owner@assetflow.test', 'Owner Master', 'Owner Master', 'owner', 'Owner', 'Active'),
         ('${ADMIN1_UID}', 'admin1@assetflow.test', 'Admin One', 'Admin One', 'admin1', 'Admin', 'Active'),
         ('${ADMIN2_UID}', 'admin2@assetflow.test', 'Admin Two', 'Admin Two', 'admin2', 'Admin', 'Active'),
         ('${AUDITOR1_UID}', 'auditor1@assetflow.test', 'Auditor One', 'Auditor One', 'auditor1', 'Auditor', 'Active'),
         ('${AUDITOR2_UID}', 'auditor2@assetflow.test', 'Auditor Two', 'Auditor Two', 'auditor2', 'Auditor', 'Active')`
    ).run();

    // Seed client
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, setup_status, active_fiscal_year_id, unique_asset_key_header, created_by_uid)
       VALUES ('${CLIENT_ID}', 'Beta Corp', 'BET', 'Logistics', 'Active', 'COMPLETED', '2026_27', 'Asset ID', '${OWNER_UID}')`
    ).run();

    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES ('${CLIENT_ID}', 'SHARD_01', 'shard-01-db', 'ACTIVE')`
    ).run();

    // Owner and Admin1 have memberships in Beta Corp
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, has_review_authority, can_publish, can_sync_drive, assigned_by_uid)
       VALUES 
         ('mem_beta_owner', '${CLIENT_ID}', '${OWNER_UID}', 'Owner', 'Active', 1, 1, 1, '${OWNER_UID}'),
         ('mem_beta_admin1', '${CLIENT_ID}', '${ADMIN1_UID}', 'Admin', 'Active', 1, 1, 1, '${OWNER_UID}')`
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

  it('1. User Listing: Owner sees all users, Admin sees only non-Admins/Auditors, Auditor is rejected', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });
    const adminToken = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@assetflow.test' });
    const auditorToken = createMockJWT({ sub: AUDITOR1_UID, email: 'auditor1@assetflow.test' });

    // 1. Owner sees all 5 users
    const resOwner = await handleRequest(new Request('http://localhost/api/v1/users', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${ownerToken}` }
    }), env);
    expect(resOwner.status).toBe(200);
    const bodyOwner = await resOwner.json<any>();
    expect(bodyOwner.users.length).toBe(5);

    // 2. Admin sees Auditors + themselves (not Owner or other Admins)
    const resAdmin = await handleRequest(new Request('http://localhost/api/v1/users', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    }), env);
    expect(resAdmin.status).toBe(200);
    const bodyAdmin = await resAdmin.json<any>();
    const adminSeenUids = bodyAdmin.users.map((u: any) => u.uid);
    expect(adminSeenUids).toContain(ADMIN1_UID);
    expect(adminSeenUids).toContain(AUDITOR1_UID);
    expect(adminSeenUids).toContain(AUDITOR2_UID);
    expect(adminSeenUids).not.toContain(OWNER_UID);
    expect(adminSeenUids).not.toContain(ADMIN2_UID);

    // 3. Auditor gets 500/403
    const resAuditor = await handleRequest(new Request('http://localhost/api/v1/users', {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${auditorToken}` }
    }), env);
    expect(resAuditor.status).toBeGreaterThanOrEqual(400);
  });

  it('2. Governance: Admin cannot suspend Owner or other Admins; Owner can suspend Admin', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });
    const adminToken = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@assetflow.test' });

    // Admin tries to suspend Owner -> rejected
    const res1 = await handleRequest(new Request(`http://localhost/api/v1/users/${OWNER_UID}/status`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: 'Suspended' })
    }), env);
    expect(res1.status).toBeGreaterThanOrEqual(400);

    // Admin tries to suspend Admin2 -> rejected
    const res2 = await handleRequest(new Request(`http://localhost/api/v1/users/${ADMIN2_UID}/status`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: 'Suspended' })
    }), env);
    expect(res2.status).toBeGreaterThanOrEqual(400);

    // Owner suspends Admin2 -> succeeds
    const res3 = await handleRequest(new Request(`http://localhost/api/v1/users/${ADMIN2_UID}/status`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: 'Suspended' })
    }), env);
    expect(res3.status).toBe(200);

    const userRow = controlDb.getNativeDb().prepare('SELECT * FROM users WHERE uid = ?').get(ADMIN2_UID) as any;
    expect(userRow.account_status).toBe('Suspended');
  });

  it('3. Governance: Only Owner can promote or demote Admin role; Owner is immutable', async () => {
    const env = createEnv();
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });
    const adminToken = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@assetflow.test' });

    // Admin tries to promote Auditor1 to Admin -> rejected
    const res1 = await handleRequest(new Request(`http://localhost/api/v1/users/${AUDITOR1_UID}/role`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'Admin' })
    }), env);
    expect(res1.status).toBeGreaterThanOrEqual(400);

    // Owner promotes Auditor1 to Admin -> succeeds
    const res2 = await handleRequest(new Request(`http://localhost/api/v1/users/${AUDITOR1_UID}/role`, {
      method: 'PATCH',
      headers: { 'Authorization': `Bearer ${ownerToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: 'Admin' })
    }), env);
    expect(res2.status).toBe(200);

    const userRow = controlDb.getNativeDb().prepare('SELECT * FROM users WHERE uid = ?').get(AUDITOR1_UID) as any;
    expect(userRow.global_role).toBe('Admin');
  });

  it('4. Client Memberships: Admin assigns Auditor to Client; role and permissions are stored', async () => {
    const env = createEnv();
    const adminToken = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@assetflow.test' });

    // Admin1 assigns Auditor2 to Client Beta Corp with review authority
    const assignRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/memberships`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userUid: AUDITOR2_UID,
        role: 'Auditor',
        hasReviewAuthority: true,
        canPublish: false
      })
    }), env);
    expect(assignRes.status).toBe(201);

    // Verify membership in Control DB
    const memRow = controlDb.getNativeDb()
      .prepare('SELECT * FROM client_memberships WHERE client_id = ? AND user_uid = ?')
      .get(CLIENT_ID, AUDITOR2_UID) as any;
    expect(memRow).not.toBeNull();
    expect(memRow.role).toBe('Auditor');
    expect(memRow.has_review_authority).toBe(1);
    expect(memRow.can_publish).toBe(0);
    expect(memRow.account_status).toBe('Active');

    // List memberships for Client
    const listRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/memberships`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    }), env);
    expect(listRes.status).toBe(200);
    const listBody = await listRes.json<any>();
    expect(listBody.memberships.length).toBe(3); // Owner, Admin1, Auditor2
  });

  it('5. Client Memberships: Unassigning user revokes access; last Admin or Owner cannot be removed', async () => {
    const env = createEnv();
    const adminToken = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@assetflow.test' });
    const ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.test' });

    // Try to remove Owner from Client -> rejected
    const remOwner = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/memberships/${OWNER_UID}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    }), env);
    expect(remOwner.status).toBeGreaterThanOrEqual(400);

    // Assign Auditor2, then remove Auditor2 -> succeeds
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/memberships`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ userUid: AUDITOR2_UID, role: 'Auditor' })
    }), env);

    const remAud = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/memberships/${AUDITOR2_UID}`, {
      method: 'DELETE',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    }), env);
    expect(remAud.status).toBe(200);

    // Verify Auditor2 membership is Revoked
    const memRow = controlDb.getNativeDb()
      .prepare('SELECT * FROM client_memberships WHERE client_id = ? AND user_uid = ?')
      .get(CLIENT_ID, AUDITOR2_UID) as any;
    expect(memRow.account_status).toBe('Revoked');

    // Auditor2 attempt to access Client endpoint now returns 403 Forbidden!
    const audToken = createMockJWT({ sub: AUDITOR2_UID, email: 'auditor2@assetflow.test' });
    const accessRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/fiscal-years`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${audToken}` }
    }), env);
    expect(accessRes.status).toBe(403);
  });

  it('6. Access Request Lifecycle: Auditor submits request, Admin approves, creating active membership in D1', async () => {
    const env = createEnv();
    const auditorToken = createMockJWT({ sub: AUDITOR2_UID, email: 'auditor2@assetflow.test' });
    const adminToken = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@assetflow.test' });

    // 1. Auditor2 submits access request for Beta Corp
    const submitRes = await handleRequest(new Request('http://localhost/api/v1/access-requests', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${auditorToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: CLIENT_ID,
        fiscalYearId: '2026_27',
        reason: 'Assigned to Q4 warehouse audit'
      })
    }), env);
    expect(submitRes.status).toBe(201);
    const submitBody = await submitRes.json<any>();
    const reqId = submitBody.accessRequest.request_id;
    expect(submitBody.accessRequest.status).toBe('Pending');

    // 2. Duplicate submission by same user is idempotent
    const dupRes = await handleRequest(new Request('http://localhost/api/v1/access-requests', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${auditorToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: CLIENT_ID,
        fiscalYearId: '2026_27',
        reason: 'Duplicate request'
      })
    }), env);
    expect(dupRes.status).toBe(201);
    const dupBody = await dupRes.json<any>();
    expect(dupBody.accessRequest.request_id).toBe(reqId);

    // 3. Admin lists access requests for Client
    const listRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/access-requests`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${adminToken}` }
    }), env);
    expect(listRes.status).toBe(200);
    const listBody = await listRes.json<any>();
    expect(listBody.accessRequests.length).toBe(1);
    expect(listBody.accessRequests[0].requester_uid).toBe(AUDITOR2_UID);

    // 4. Admin approves request
    const approveRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/access-requests/${reqId}/decide`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision: 'Approved' })
    }), env);
    expect(approveRes.status).toBe(200);
    const approveBody = await approveRes.json<any>();
    expect(approveBody.accessRequest.status).toBe('Approved');

    // 5. Verify active membership was created in Control DB
    const newMem = controlDb.getNativeDb()
      .prepare('SELECT * FROM client_memberships WHERE client_id = ? AND user_uid = ?')
      .get(CLIENT_ID, AUDITOR2_UID) as any;
    expect(newMem).not.toBeNull();
    expect(newMem.role).toBe('Auditor');
    expect(newMem.account_status).toBe('Active');

    // 6. Auditor2 now has authorized access to Beta Corp
    const accessRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/fiscal-years`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${auditorToken}` }
    }), env);
    expect(accessRes.status).toBe(200);
  });

  it('7. Access Request Lifecycle: Admin rejects request, status is Rejected and no membership created', async () => {
    const env = createEnv();
    const auditorToken = createMockJWT({ sub: AUDITOR2_UID, email: 'auditor2@assetflow.test' });
    const adminToken = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@assetflow.test' });

    // Submit request
    const submitRes = await handleRequest(new Request('http://localhost/api/v1/access-requests', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${auditorToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: CLIENT_ID,
        fiscalYearId: '2026_27',
        reason: 'Random request'
      })
    }), env);
    const reqId = (await submitRes.json<any>()).accessRequest.request_id;

    // Admin rejects
    const rejectRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/access-requests/${reqId}/decide`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision: 'Rejected' })
    }), env);
    expect(rejectRes.status).toBe(200);
    const rejectBody = await rejectRes.json<any>();
    expect(rejectBody.accessRequest.status).toBe('Rejected');

    // Verify NO membership created
    const memRow = controlDb.getNativeDb()
      .prepare('SELECT * FROM client_memberships WHERE client_id = ? AND user_uid = ?')
      .get(CLIENT_ID, AUDITOR2_UID) as any;
    expect(memRow).toBeUndefined();
  });
});
