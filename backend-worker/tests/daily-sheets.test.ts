import { describe, it, expect, beforeEach } from 'vitest';
import { createAllMigrationsControlD1, createAllMigrationsShardD1, MockD1Database, createMockJWT } from './test-helper';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('Phase 4: Daily Sheets, Review Queue & Separation of Duties on D1', () => {
  let env: Env;
  let controlDb: MockD1Database;
  let shardDb: MockD1Database;

  const CLIENT_ID = 'client_daily_test';
  const OWNER_UID = 'uid_owner_master';
  const ADMIN1_UID = 'uid_admin_one';
  const ADMIN2_UID = 'uid_admin_two';
  const AUDITOR_UID = 'uid_auditor_field';

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
         ('${OWNER_UID}', 'owner@assetflow.com', 'System Owner', 'System Owner', 'owner', 'Owner', 'Active'),
         ('${ADMIN1_UID}', 'admin1@assetflow.com', 'Admin One', 'Admin One', 'admin1', 'Admin', 'Active'),
         ('${ADMIN2_UID}', 'admin2@assetflow.com', 'Admin Two', 'Admin Two', 'admin2', 'Admin', 'Active'),
         ('${AUDITOR_UID}', 'auditor@assetflow.com', 'Field Auditor', 'Field Auditor', 'auditor', 'Auditor', 'Active')`
    ).run();

    // Seed client
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, setup_status, active_fiscal_year_id, unique_asset_key_header, created_by_uid)
       VALUES ('${CLIENT_ID}', 'Daily Sheet Test Corp', 'DAILY-CORP', 'Audit', 'Active', 'COMPLETED', '2026_27', 'Asset ID', '${OWNER_UID}')`
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

    // Seed schema version
    shardDb.getNativeDb().prepare(
      `INSERT INTO input_schema_versions (schema_version_id, client_id, fiscal_year_id, version_number, status, source_workbook_name, created_by_uid)
       VALUES ('v1', '${CLIENT_ID}', '2026_27', 1, 'PUBLISHED', 'test.xlsx', '${OWNER_UID}')`
    ).run();

    // Seed FAR assets referenced in tests
    const farStmt = shardDb.getNativeDb().prepare(
      `INSERT INTO far_assets (client_id, fiscal_year_id, asset_key, asset_id, source_row, import_batch_id, raw_row_json, search_text, imported_by_uid)
       VALUES (?, ?, ?, ?, ?, 'imp_001', '{}', ?, ?)`
    );
    ['AST_001', 'AST_002', 'AST_ADM_1', 'AST_AUD_1', 'AST_ADM_2', 'AST_APPR_1', 'AST_REJ_1'].forEach((key, idx) => {
      farStmt.run(CLIENT_ID, '2026_27', key, key, idx + 1, key, OWNER_UID);
    });

    env = {
      CONTROL_DB: controlDb as any,
      SHARD_01: shardDb as any,
      ENVIRONMENT: 'test',
      FIREBASE_PROJECT_ID: 'spherical-booth-366013',
      SHARD_ALLOWLIST: 'SHARD_01',
      JWT_VERIFICATION_MOCK: 'true'
    };

    ownerToken = createMockJWT({ sub: OWNER_UID, email: 'owner@assetflow.com' });
    admin1Token = createMockJWT({ sub: ADMIN1_UID, email: 'admin1@assetflow.com' });
    admin2Token = createMockJWT({ sub: ADMIN2_UID, email: 'admin2@assetflow.com' });
    auditorToken = createMockJWT({ sub: AUDITOR_UID, email: 'auditor@assetflow.com' });
  });

  it('1. Auditor submits daily sheet batch: creates batch, submission, and counting records', async () => {
    const res = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${auditorToken}`
      },
      body: JSON.stringify({
        batchId: 'batch_20261102_001',
        fiscalYearId: '2026_27',
        batchDate: '2026-11-02',
        items: [
          {
            recordId: 'rec_001',
            assetKey: 'AST_001',
            schemaVersionId: 'v1',
            valuesJson: JSON.stringify({ tag_status: 'FOUND', condition: 'GOOD' })
          },
          {
            recordId: 'rec_002',
            assetKey: 'AST_002',
            schemaVersionId: 'v1',
            valuesJson: JSON.stringify({ tag_status: 'DAMAGED', condition: 'POOR' })
          }
        ]
      })
    }), env);

    expect(res.status).toBe(201);
    const body = await res.json<any>();
    expect(body.batchId).toBe('batch_20261102_001');
    expect(body.totalItems).toBe(2);

    // Verify detail
    const detailRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets/batch_20261102_001`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${auditorToken}` }
    }), env);
    expect(detailRes.status).toBe(200);
    const detail = await detailRes.json<any>();
    expect(detail.batch.status).toBe('SUBMITTED');
    expect(detail.submission.status).toBe('PENDING_REVIEW');
    expect(detail.records.length).toBe(2);
    expect(detail.records[0].record_status).toBe('PENDING_REVIEW');
  });

  it('2. Auditor can only view their own daily sheets in list; Admin sees all', async () => {
    // Admin 1 submits another batch as an auditor in the field
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({
        batchId: 'batch_admin1_001',
        fiscalYearId: '2026_27',
        batchDate: '2026-11-02',
        items: [{ recordId: 'rec_adm_1', assetKey: 'AST_ADM_1', schemaVersionId: 'v1' }]
      })
    }), env);

    // Auditor submits a batch
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${auditorToken}` },
      body: JSON.stringify({
        batchId: 'batch_auditor_001',
        fiscalYearId: '2026_27',
        batchDate: '2026-11-02',
        items: [{ recordId: 'rec_aud_1', assetKey: 'AST_AUD_1', schemaVersionId: 'v1' }]
      })
    }), env);

    // Auditor lists sheets -> should see only 1
    const audListRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${auditorToken}` }
    }), env);
    const audList = await audListRes.json<any>();
    expect(audList.dailySheets.length).toBe(1);
    expect(audList.dailySheets[0].batch_id).toBe('batch_auditor_001');

    // Admin 2 lists sheets -> should see both
    const admListRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${admin2Token}` }
    }), env);
    const admList = await admListRes.json<any>();
    expect(admList.dailySheets.length).toBe(2);
  });

  it('3. Strict Separation of Duties: Submitter cannot approve their own daily sheet', async () => {
    // Admin 1 submits batch_admin1_002
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({
        batchId: 'batch_admin1_002',
        fiscalYearId: '2026_27',
        batchDate: '2026-11-02',
        items: [{ recordId: 'rec_adm_2', assetKey: 'AST_ADM_2', schemaVersionId: 'v1' }]
      })
    }), env);

    // Admin 1 tries to approve their own batch -> 403 Forbidden (Separation of duties)
    const selfApproveRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets/batch_admin1_002/approve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ remarks: 'Self approval attempt' })
    }), env);

    expect(selfApproveRes.status).toBe(403);
    const errBody = await selfApproveRes.json<any>();
    expect(errBody.error).toContain('Separation of Duties');

    // Admin 1 tries to return/reject their own batch -> 403 Forbidden
    const selfReviewRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets/batch_admin1_002/review`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ remarks: 'Self return attempt' })
    }), env);

    expect(selfReviewRes.status).toBe(403);
  });

  it('4. Independent Admin can approve daily sheet: updates batch, submission and counting records to APPROVED', async () => {
    // Auditor submits batch_for_approval
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${auditorToken}` },
      body: JSON.stringify({
        batchId: 'batch_for_approval',
        fiscalYearId: '2026_27',
        batchDate: '2026-11-02',
        items: [{ recordId: 'rec_appr_1', assetKey: 'AST_APPR_1', schemaVersionId: 'v1' }]
      })
    }), env);

    // Admin 1 approves the sheet
    const approveRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets/batch_for_approval/approve`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin1Token}` },
      body: JSON.stringify({ remarks: 'Verified all physical assets on site' })
    }), env);

    expect(approveRes.status).toBe(200);
    const body = await approveRes.json<any>();
    expect(body.status).toBe('APPROVED');

    // Verify detail
    const detailRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets/batch_for_approval`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${admin1Token}` }
    }), env);
    const detail = await detailRes.json<any>();
    expect(detail.batch.status).toBe('APPROVED');
    expect(detail.submission.status).toBe('APPROVED');
    expect(detail.submission.reviewed_by_uid).toBe(ADMIN1_UID);
    expect(detail.records[0].record_status).toBe('APPROVED');
  });

  it('5. Independent Admin can return daily sheet with remarks: transitions to REJECTED', async () => {
    // Auditor submits batch_for_reject
    await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${auditorToken}` },
      body: JSON.stringify({
        batchId: 'batch_for_reject',
        fiscalYearId: '2026_27',
        batchDate: '2026-11-02',
        items: [{ recordId: 'rec_rej_1', assetKey: 'AST_REJ_1', schemaVersionId: 'v1' }]
      })
    }), env);

    // Admin 2 returns with remarks
    const reviewRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets/batch_for_reject/review`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${admin2Token}` },
      body: JSON.stringify({ remarks: 'Missing photo evidence for tagged item AST_REJ_1. Please re-capture.' })
    }), env);

    expect(reviewRes.status).toBe(200);
    const body = await reviewRes.json<any>();
    expect(body.status).toBe('REJECTED');

    // Verify detail
    const detailRes = await handleRequest(new Request(`http://localhost/api/v1/clients/${CLIENT_ID}/daily-sheets/batch_for_reject`, {
      method: 'GET',
      headers: { 'Authorization': `Bearer ${admin2Token}` }
    }), env);
    const detail = await detailRes.json<any>();
    expect(detail.batch.status).toBe('REJECTED');
    expect(detail.submission.status).toBe('REJECTED');
    expect(detail.submission.reviewed_by_uid).toBe(ADMIN2_UID);
    expect(detail.records[0].record_status).toBe('REJECTED');
  });
});
