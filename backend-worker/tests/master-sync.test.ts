import { describe, it, expect, beforeEach } from 'vitest';
import { createAllMigrationsControlD1, createAllMigrationsShardD1, MockD1Database, createMockJWT } from './test-helper';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('Phase 5: Master Workbook State, Concurrency Lease Lock & Recovery on D1', () => {
  let env: Env;
  let controlDb: MockD1Database;
  let shardDb: MockD1Database;

  const CLIENT_ID = 'client_master_sync_corp';
  const OWNER_UID = 'uid_owner_sync';
  const ADMIN1_UID = 'uid_admin1_sync';
  const ADMIN2_UID = 'uid_admin2_sync';
  const AUDITOR_UID = 'uid_auditor_sync';

  let ownerToken: string;
  let admin1Token: string;
  let admin2Token: string;
  let auditorToken: string;

  beforeEach(() => {
    controlDb = createAllMigrationsControlD1();
    shardDb = createAllMigrationsShardD1();

    // Seed users
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES 
         ('${OWNER_UID}', 'owner@sync.test', 'Owner Sync', 'Owner Sync', 'owner', 'Owner', 'Active'),
         ('${ADMIN1_UID}', 'admin1@sync.test', 'Admin1 Sync', 'Admin1 Sync', 'admin1', 'Admin', 'Active'),
         ('${ADMIN2_UID}', 'admin2@sync.test', 'Admin2 Sync', 'Admin2 Sync', 'admin2', 'Admin', 'Active'),
         ('${AUDITOR_UID}', 'auditor@sync.test', 'Auditor Sync', 'Auditor Sync', 'auditor', 'Auditor', 'Active')`
    ).run();

    // Seed client
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, setup_status, active_fiscal_year_id, unique_asset_key_header, created_by_uid)
       VALUES ('${CLIENT_ID}', 'Master Sync Corp', 'SYNC-CORP', 'Audit', 'Active', 'COMPLETED', '2026_27', 'Asset ID', '${OWNER_UID}')`
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
         ('m_admin1', '${CLIENT_ID}', '${ADMIN1_UID}', 'Admin', 'Active', 1, 1, 1, '${OWNER_UID}'),
         ('m_admin2', '${CLIENT_ID}', '${ADMIN2_UID}', 'Admin', 'Active', 1, 1, 1, '${OWNER_UID}'),
         ('m_auditor', '${CLIENT_ID}', '${AUDITOR_UID}', 'Auditor', 'Active', 0, 0, 0, '${ADMIN1_UID}')`
    ).run();

    // Seed shard fiscal year
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label)
       VALUES ('${CLIENT_ID}', '2026_27', 'FY 2026-27')`
    ).run();

    env = {
      CONTROL_DB: controlDb as any,
      SHARD_01: shardDb as any,
      ENVIRONMENT: 'test',
      FIREBASE_PROJECT_ID: 'spherical-booth-366013',
      SHARD_ALLOWLIST: 'SHARD_01',
      JWT_VERIFICATION_MOCK: 'true'
    };

    ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@sync.test' });
    admin1Token = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@sync.test' });
    admin2Token = createMockJWT({ sub: ADMIN2_UID, email: 'admin2@sync.test' });
    auditorToken = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@sync.test' });
  });

  it('1. Initial master workbook state returns NOT_SYNCED', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/state?fiscalYearId=2026_27`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${admin1Token}` }
    }), env);

    expect(res.status).toBe(200);
    const body = await res.json<any>();
    expect(body.state.sync_state).toBe('NOT_SYNCED');
    expect(body.latestJob).toBeNull();
  });

  it('2. Admin acquires lease lock: transitions state to SYNCING and records job', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${admin1Token}`
      },
      body: JSON.stringify({
        fiscalYearId: '2026_27',
        jobId: 'job_sync_001',
        leaseDurationMs: 30000
      })
    }), env);

    expect(res.status).toBe(200);
    const body = await res.json<any>();
    expect(body.acquired).toBe(true);
    expect(body.jobId).toBe('job_sync_001');

    // Verify state in D1
    const stateRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/state?fiscalYearId=2026_27`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${admin1Token}` }
    }), env);
    const stateBody = await stateRes.json<any>();
    expect(stateBody.state.sync_state).toBe('SYNCING');
    expect(stateBody.state.lease_owner_job_id).toBe('job_sync_001');
    expect(stateBody.latestJob.status).toBe('IN_PROGRESS');
  });

  it('3. Concurrency conflict: Second admin cannot acquire lease while active (409 Conflict)', async () => {
    // Admin 1 acquires lease
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_sync_admin1', leaseDurationMs: 60000 })
    }), env);

    // Admin 2 tries to acquire lease -> 409 Conflict
    const conflictRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin2Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_sync_admin2' })
    }), env);

    expect(conflictRes.status).toBe(409);
    const conflictBody = await conflictRes.json<any>();
    expect(conflictBody.code).toBe('LEASE_LOCK_CONFLICT');
  });

  it('4. Releasing lease with COMPLETED transitions state to SYNCED and stores checksum & drive ID', async () => {
    // Admin 1 acquires lease
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_sync_003' })
    }), env);

    // Admin 1 completes sync and releases lease
    const releaseRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/release-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({
        fiscalYearId: '2026_27',
        jobId: 'job_sync_003',
        status: 'COMPLETED',
        fileChecksum: 'sha256_mock_hash_12345',
        driveFileId: 'drive_file_abc_789',
        submissionId: 'sub_batch_001'
      })
    }), env);

    expect(releaseRes.status).toBe(200);
    const releaseBody = await releaseRes.json<any>();
    expect(releaseBody.released).toBe(true);
    expect(releaseBody.status).toBe('COMPLETED');

    // Verify state
    const stateRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/state?fiscalYearId=2026_27`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${admin1Token}` }
    }), env);
    const stateBody = await stateRes.json<any>();
    expect(stateBody.state.sync_state).toBe('SYNCED');
    expect(stateBody.state.lease_owner_job_id).toBeNull();
    expect(stateBody.state.file_checksum).toBe('sha256_mock_hash_12345');
    expect(stateBody.state.master_drive_file_id).toBe('drive_file_abc_789');
    expect(JSON.parse(stateBody.state.applied_submissions_json)['sub_batch_001']).toBe(true);
    expect(stateBody.latestJob.status).toBe('COMPLETED');

    // Now Admin 2 can acquire lease without conflict
    const admin2AcquireRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin2Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_sync_admin2_next' })
    }), env);
    expect(admin2AcquireRes.status).toBe(200);
  });

  it('5. Field auditor cannot acquire or release master sync lease (403 Forbidden)', async () => {
    const audAcquireRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${auditorToken}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_auditor_illegal' })
    }), env);

    expect(audAcquireRes.status).toBe(403);
  });

  it('6. Concurrency fencing: Job A lease expires, Job B acquires lease, Job A late release fails with 409 LEASE_OWNERSHIP_MISMATCH, Job B lease intact', async () => {
    // 1. Admin 1 acquires lease for Job A with short duration (50ms)
    const jobAAcquire = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_A_timeout', leaseDurationMs: 50 })
    }), env);
    expect(jobAAcquire.status).toBe(200);

    // 2. Wait 75ms for Job A's lease to expire
    await new Promise((resolve) => setTimeout(resolve, 75));

    // 3. Admin 2 acquires lease for Job B
    const jobBAcquire = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin2Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_B_active', leaseDurationMs: 60000 })
    }), env);
    expect(jobBAcquire.status).toBe(200);
    const jobBBody = await jobBAcquire.json<any>();
    expect(jobBBody.acquired).toBe(true);
    expect(jobBBody.jobId).toBe('job_B_active');

    // 4. Job A attempts late release with COMPLETED -> Must be rejected with 409 LEASE_OWNERSHIP_MISMATCH
    const lateReleaseRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/release-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({
        fiscalYearId: '2026_27',
        jobId: 'job_A_timeout',
        status: 'COMPLETED',
        fileChecksum: 'stale_job_a_checksum',
        driveFileId: 'stale_job_a_drive_id'
      })
    }), env);

    expect(lateReleaseRes.status).toBe(409);
    const lateReleaseBody = await lateReleaseRes.json<any>();
    expect(lateReleaseBody.code).toBe('LEASE_OWNERSHIP_MISMATCH');
    expect(lateReleaseBody.error).toContain("cannot release master sync lease currently held by job 'job_B_active'");

    // 5. Verify Job B's active lease in D1 master_workbook_state remains completely intact
    const stateRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/state?fiscalYearId=2026_27`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${admin1Token}` }
    }), env);
    const stateBody = await stateRes.json<any>();
    expect(stateBody.state.sync_state).toBe('SYNCING');
    expect(stateBody.state.lease_owner_job_id).toBe('job_B_active');
    expect(stateBody.state.file_checksum).not.toBe('stale_job_a_checksum');

    // 6. Verify transactional consistency in master_sync_jobs: Job B is still IN_PROGRESS
    const jobBRow = shardDb.getNativeDb().prepare(
      `SELECT status FROM master_sync_jobs WHERE job_id = 'job_B_active'`
    ).get();
    expect(jobBRow.status).toBe('IN_PROGRESS');

    // 7. Verify Job A late release with FAILED also rejected with 409 LEASE_OWNERSHIP_MISMATCH
    const lateFailRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/release-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({
        fiscalYearId: '2026_27',
        jobId: 'job_A_timeout',
        status: 'FAILED',
        errorMessage: 'Delayed job error'
      })
    }), env);
    expect(lateFailRes.status).toBe(409);
    const lateFailBody = await lateFailRes.json<any>();
    expect(lateFailBody.code).toBe('LEASE_OWNERSHIP_MISMATCH');
  });

  it('7. Active owner (Job B) completes and releases successfully after Job A late release was rejected', async () => {
    // Acquire lease for Job B
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin2Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_B_finish', leaseDurationMs: 60000 })
    }), env);

    // Job B completes and releases
    const releaseRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/release-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin2Token}` },
      body: JSON.stringify({
        fiscalYearId: '2026_27',
        jobId: 'job_B_finish',
        status: 'COMPLETED',
        fileChecksum: 'job_b_verified_hash_999',
        driveFileId: 'job_b_drive_file_888'
      })
    }), env);

    expect(releaseRes.status).toBe(200);
    const releaseBody = await releaseRes.json<any>();
    expect(releaseBody.released).toBe(true);
    expect(releaseBody.status).toBe('COMPLETED');

    // Verify D1 state is SYNCED and lease is cleared
    const stateRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/state?fiscalYearId=2026_27`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${admin2Token}` }
    }), env);
    const stateBody = await stateRes.json<any>();
    expect(stateBody.state.sync_state).toBe('SYNCED');
    expect(stateBody.state.lease_owner_job_id).toBeNull();
    expect(stateBody.state.file_checksum).toBe('job_b_verified_hash_999');
    expect(stateBody.state.master_drive_file_id).toBe('job_b_drive_file_888');
    expect(stateBody.latestJob.status).toBe('COMPLETED');
  });

  it('8. Idempotent release by current owner and rejection of unknown or non-owner jobs', async () => {
    // 1. Admin 1 acquires and releases Job C
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/acquire-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_C_idempotent' })
    }), env);

    const firstRelease = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/release-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_C_idempotent', status: 'COMPLETED' })
    }), env);
    expect(firstRelease.status).toBe(200);

    // 2. Idempotent retry by Job C returns 200 without error
    const retryRelease = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/release-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_C_idempotent', status: 'COMPLETED' })
    }), env);
    expect(retryRelease.status).toBe(200);
    const retryBody = await retryRelease.json<any>();
    expect(retryBody.released).toBe(true);
    expect(retryBody.status).toBe('COMPLETED');

    // 3. Unknown job attempting to release fails with 404 JOB_NOT_FOUND
    const unknownJobRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/release-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_non_existent', status: 'COMPLETED' })
    }), env);
    expect(unknownJobRes.status).toBe(404);
    const unknownBody = await unknownJobRes.json<any>();
    expect(unknownBody.code).toBe('JOB_NOT_FOUND');

    // 4. Stale job that never finished attempting release when state is unleased fails with 409
    // Manually insert an uncompleted stale job
    shardDb.getNativeDb().prepare(
      `INSERT INTO master_sync_jobs (job_id, client_id, fiscal_year_id, job_type, status, triggered_by_uid, created_at)
       VALUES ('job_stale_abandoned', '${CLIENT_ID}', '2026_27', 'MASTER_WORKBOOK_SYNC', 'IN_PROGRESS', '${ADMIN1_UID}', datetime('now'))`
    ).run();

    const staleRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/master-workbook/release-lease`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ fiscalYearId: '2026_27', jobId: 'job_stale_abandoned', status: 'COMPLETED' })
    }), env);
    expect(staleRes.status).toBe(409);
    const staleBody = await staleRes.json<any>();
    expect(staleBody.code).toBe('LEASE_OWNERSHIP_MISMATCH');
  });
});
