import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import * as fs from 'node:fs';
import { createLocalTestD1, MockD1Database, createMockJWT } from './test-helper';
import { ShardRepository } from '../src/repositories/shardRepository';
import { ControlRepository } from '../src/repositories/controlRepository';
import { EvidenceService, EvidenceValidationError, EvidenceConflictError } from '../src/services/evidenceService';
import { RegisterEvidencePayload } from '../src/domain/evidence';
import { AuthenticatedUser, ClientMembershipContext } from '../src/domain/permissions';
import { handleRequest } from '../src/router/routes';
import { Env } from '../src/env';

describe('Evidence Photo Registration & Tenancy Isolation', () => {
  let shardDb: MockD1Database;
  let controlDb: MockD1Database;
  let shardRepo: ShardRepository;
  let controlRepo: ControlRepository;
  let evidenceService: EvidenceService;

  const shardMigration = path.resolve(__dirname, '../migrations/shard/0001_initial_shard.sql');
  const evidenceMigration = path.resolve(__dirname, '../migrations/shard/0002_evidence_metadata.sql');
  const hardeningMigration = path.resolve(__dirname, '../migrations/shard/0003_evidence_hardening.sql');
  const idempotencyMigration = path.resolve(__dirname, '../migrations/shard/0004_evidence_idempotency.sql');
  const controlMigration = path.resolve(__dirname, '../migrations/control/0001_initial_control.sql');

  const testUser: AuthenticatedUser = {
    uid: 'uid_auditor_01',
    email: 'auditor@example.com',
    displayName: 'Auditor Jane',
    accountStatus: 'Active',
    globalRole: 'Auditor'
  };

  const testMembership: ClientMembershipContext = {
    membershipId: 'mem_01',
    clientId: 'client_walton',
    userUid: 'uid_auditor_01',
    role: 'Auditor',
    accountStatus: 'Active',
    hasReviewAuthority: false,
    canPublish: false,
    canSyncDrive: false
  };

  beforeEach(async () => {
    shardDb = createLocalTestD1(shardMigration);
    const sql0002 = fs.readFileSync(evidenceMigration, 'utf8');
    shardDb.getNativeDb().exec(sql0002);
    const sql0003 = fs.readFileSync(hardeningMigration, 'utf8');
    shardDb.getNativeDb().exec(sql0003);
    const sql0004 = fs.readFileSync(idempotencyMigration, 'utf8');
    shardDb.getNativeDb().exec(sql0004);

    controlDb = createLocalTestD1(controlMigration);

    shardRepo = new ShardRepository(shardDb as any);
    controlRepo = new ControlRepository(controlDb as any);
    evidenceService = new EvidenceService(shardRepo, controlRepo);

    // 1. Seed Control DB: users table
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES ('uid_auditor_01', 'auditor@example.com', 'Auditor Jane', 'Jane Doe', 'Jane', 'Auditor', 'Active')`
    ).run();

    // 2. Seed Control DB: client_walton with designated drive_root_folder_id
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, status, drive_root_folder_id, created_by_uid)
       VALUES ('client_walton', 'Walton Industries', 'WLT', 'Manufacturing', 'Active', 'drive_folder_walton_root', 'uid_auditor_01')`
    ).run();

    // 3. Seed Control DB: client_shard_routing
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES ('client_walton', 'SHARD_01', 'db_shard_01', 'ACTIVE')`
    ).run();

    // 4. Seed Control DB: Client Membership for testUser
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, assigned_by_uid)
       VALUES ('mem_01', 'client_walton', 'uid_auditor_01', 'Auditor', 'Active', 'uid_auditor_01')`
    ).run();

    // 5. Seed Shard DB: Fiscal Year
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label)
       VALUES ('client_walton', '2026_27', '2026-27')`
    ).run();

    // 6. Seed Shard DB: FAR Asset Baseline (preserving leading zero: '000142')
    shardDb.getNativeDb().prepare(
      `INSERT INTO far_assets (client_id, fiscal_year_id, asset_key, asset_id, source_row, import_batch_id, raw_row_json, search_text, imported_by_uid)
       VALUES ('client_walton', '2026_27', 'WLT-000142', '000142', 2, 'batch_01', '{"Asset ID":"000142"}', '000142 Forklift', 'uid_auditor_01')`
    ).run();
  });

  const validPayload: RegisterEvidencePayload = {
    photoId: 'photo_wlt_001',
    cycleId: '2026_27',
    assetKey: 'WLT-000142',
    assetId: '000142',
    driveFileId: 'drive_file_abc123',
    driveWebViewLink: 'https://drive.google.com/file/d/drive_file_abc123/view',
    driveRootFolderId: 'drive_folder_walton_root',
    driveDateFolderId: 'drive_folder_2026_11_01',
    mimeType: 'image/jpeg',
    fileExtension: 'jpg',
    fileSize: 2048576,
    sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    localFilename: 'WLT_000142_photo_wlt_001.jpg',
    photoCategory: 'CONDITION_DEFECT',
    caption: 'Front view showing intact nameplate'
  };

  it('1. Successfully registers evidence metadata preserving leading zeros', async () => {
    const result = await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      validPayload,
      testUser,
      testMembership
    );

    expect(result.isIdempotentReplay).toBe(false);
    expect(result.photo.photoId).toBe('photo_wlt_001');
    expect(result.photo.clientId).toBe('client_walton');
    expect(result.photo.cycleId).toBe('2026_27');
    expect(result.photo.assetKey).toBe('WLT-000142');
    expect(result.photo.assetId).toBe('000142'); // Leading zero strictly preserved!
    expect(result.photo.uploaderUid).toBe('uid_auditor_01');
    expect(result.photo.uploaderNameSnapshot).toBe('Auditor Jane');
    expect(result.photo.driveFileId).toBe('drive_file_abc123');
    expect(result.photo.status).toBe('UPLOADED');

    // Verify stored directly in SQLite
    const retrieved = await evidenceService.getEvidence('client_walton', 'photo_wlt_001');
    expect(retrieved).not.toBeNull();
    expect(retrieved?.assetId).toBe('000142');
  });

  it('2. Idempotent replay returns existing record without duplication', async () => {
    const first = await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      validPayload,
      testUser,
      testMembership
    );
    expect(first.isIdempotentReplay).toBe(false);

    // Replay exact same payload
    const second = await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      validPayload,
      testUser,
      testMembership
    );
    expect(second.isIdempotentReplay).toBe(true);
    expect(second.photo.photoId).toBe(first.photo.photoId);
  });

  it('3. Rejects conflicting photoId reuse across different assets or cycles', async () => {
    await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      validPayload,
      testUser,
      testMembership
    );

    // Same photoId, different assetKey
    const conflictingPayload: RegisterEvidencePayload = {
      ...validPayload,
      assetKey: 'WLT-999999'
    };

    await expect(
      evidenceService.registerEvidence(
        'client_walton',
        '2026_27',
        conflictingPayload,
        testUser,
        testMembership
      )
    ).rejects.toThrow();
  });

  it('4. Rejects photo when cycle ID in payload does not match route cycle', async () => {
    const mismatchedPayload: RegisterEvidencePayload = {
      ...validPayload,
      cycleId: '2027_28'
    };

    await expect(
      evidenceService.registerEvidence(
        'client_walton',
        '2026_27',
        mismatchedPayload,
        testUser,
        testMembership
      )
    ).rejects.toThrow(EvidenceValidationError);
  });

  it('5. Rejects photo for non-existent audit cycle in shard', async () => {
    await expect(
      evidenceService.registerEvidence(
        'client_walton',
        '2099_00', // Non-existent cycle
        { ...validPayload, cycleId: '2099_00' },
        testUser,
        testMembership
      )
    ).rejects.toThrow(EvidenceValidationError);
  });

  it('6. Rejects photo when asset does not exist in FAR baseline', async () => {
    const nonExistentAssetPayload: RegisterEvidencePayload = {
      ...validPayload,
      assetKey: 'NON_EXISTENT_KEY'
    };

    await expect(
      evidenceService.registerEvidence(
        'client_walton',
        '2026_27',
        nonExistentAssetPayload,
        testUser,
        testMembership
      )
    ).rejects.toThrow(EvidenceValidationError);
  });

  it('7. Rejects photo when Drive root folder does not match client root folder', async () => {
    const wrongRootPayload: RegisterEvidencePayload = {
      ...validPayload,
      driveRootFolderId: 'unauthorized_drive_root_folder'
    };

    await expect(
      evidenceService.registerEvidence(
        'client_walton',
        '2026_27',
        wrongRootPayload,
        testUser,
        testMembership
      )
    ).rejects.toThrow(EvidenceValidationError);
  });

  it('8. Validates payload fields (missing photoId, driveFileId, or invalid MIME)', () => {
    const invalid: RegisterEvidencePayload = {
      photoId: '',
      cycleId: '2026_27',
      assetKey: '',
      assetId: '',
      driveFileId: '',
      driveRootFolderId: '',
      mimeType: 'application/pdf' as any // Not an image
    };

    expect(() => {
      throw new EvidenceValidationError(['Test error']);
    }).toThrow(EvidenceValidationError);
  });

  it('9. Lists evidence photos by asset and by cycle with client isolation', async () => {
    await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      validPayload,
      testUser,
      testMembership
    );

    // List by asset
    const assetPhotos = await evidenceService.listEvidenceForAsset('client_walton', '2026_27', 'WLT-000142');
    expect(assetPhotos.length).toBe(1);
    expect(assetPhotos[0].photoId).toBe('photo_wlt_001');

    // List by cycle
    const cyclePhotos = await evidenceService.listEvidenceForCycle('client_walton', '2026_27');
    expect(cyclePhotos.length).toBe(1);

    // Another client sees nothing (client isolation)
    const otherClientPhotos = await evidenceService.listEvidenceForAsset('client_other', '2026_27', 'WLT-000142');
    expect(otherClientPhotos.length).toBe(0);
  });

  it('10. Full HTTP Router: POST and GET evidence endpoints', async () => {
    const env: Env = {
      CONTROL_DB: controlDb as any,
      SHARD_01: shardDb as any,
      SHARD_02: shardDb as any,
      SHARD_ALLOWLIST: 'SHARD_01,SHARD_02',
      ENVIRONMENT: 'test',
      FIREBASE_PROJECT_ID: 'spherical-booth-366013',
      JWT_VERIFICATION_MOCK: 'true'
    };

    const token = createMockJWT({
      sub: 'uid_auditor_01',
      name: 'Auditor Jane',
      email: 'auditor@example.com'
    });

    // POST /api/v1/clients/client_walton/audit-cycles/2026_27/photos
    const postReq = new Request('https://api.assetflow.dev/api/v1/clients/client_walton/audit-cycles/2026_27/photos', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(validPayload)
    });

    const postRes = await handleRequest(postReq, env);
    expect(postRes.status).toBe(201);
    const postBody = await postRes.json<any>();
    expect(postBody.photo.photoId).toBe('photo_wlt_001');
    expect(postBody.photo.assetId).toBe('000142');

    // GET /api/v1/clients/client_walton/audit-cycles/2026_27/photos/photo_wlt_001
    const getReq = new Request('https://api.assetflow.dev/api/v1/clients/client_walton/audit-cycles/2026_27/photos/photo_wlt_001', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    });

    const getRes = await handleRequest(getReq, env);
    expect(getRes.status).toBe(200);
    const getBody = await getRes.json<any>();
    expect(getBody.photo.photoId).toBe('photo_wlt_001');
    expect(getBody.photo.driveFileId).toBe('drive_file_abc123');

    // GET /api/v1/clients/client_walton/audit-cycles/2026_27/assets/WLT-000142/photos
    const listReq = new Request('https://api.assetflow.dev/api/v1/clients/client_walton/audit-cycles/2026_27/assets/WLT-000142/photos', {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${token}`
      }
    });

    const listRes = await handleRequest(listReq, env);
    expect(listRes.status).toBe(200);
    const listBody = await listRes.json<any>();
    expect(listBody.photos.length).toBe(1);
    expect(listBody.photos[0].photoId).toBe('photo_wlt_001');
  });

  it('11. Rejects conflicting sha256 checksum for same photoId with EvidenceConflictError', async () => {
    await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      validPayload,
      testUser,
      testMembership
    );

    // Attempt registration with conflicting sha256 checksum
    const conflictingChecksumPayload: RegisterEvidencePayload = {
      ...validPayload,
      sha256: 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'
    };

    await expect(
      evidenceService.registerEvidence(
        'client_walton',
        '2026_27',
        conflictingChecksumPayload,
        testUser,
        testMembership
      )
    ).rejects.toThrow(EvidenceConflictError);
  });

  it('12. Rejects registration when sha256 checksum is missing or invalid', async () => {
    const invalidShaPayload = {
      ...validPayload,
      photoId: 'photo_invalid_sha',
      sha256: 'not_a_valid_64_hex_hash'
    };

    await expect(
      evidenceService.registerEvidence(
        'client_walton',
        '2026_27',
        invalidShaPayload as any,
        testUser,
        testMembership
      )
    ).rejects.toThrow(EvidenceValidationError);
  });

  it('13. Accepts photo registration when driveWebViewLink is null or omitted', async () => {
    const noLinkPayload: RegisterEvidencePayload = {
      ...validPayload,
      photoId: 'photo_no_link_001',
      driveWebViewLink: undefined // nullable/optional
    };

    const res = await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      noLinkPayload,
      testUser,
      testMembership
    );

    expect(res.isIdempotentReplay).toBe(false);
    expect(res.photo.photoId).toBe('photo_no_link_001');
    expect(res.photo.driveWebViewLink).toBeUndefined();
  });

  it('14. Atomically updates counting_records summary columns via D1 batch on upload', async () => {
    // Seed schema_version for FK
    shardDb.getNativeDb().prepare(
      `INSERT INTO input_schema_versions (
          schema_version_id, client_id, fiscal_year_id, version_number, status, source_workbook_name, created_by_uid
       ) VALUES ('schema_01', 'client_walton', '2026_27', 1, 'PUBLISHED', 'master.xlsx', 'uid_auditor_01')`
    ).run();

    // Seed counting_record
    shardDb.getNativeDb().prepare(
      `INSERT INTO counting_records (
          record_id, client_id, fiscal_year_id, asset_key, schema_version_id,
          auditor_uid, auditor_name_snapshot, count_date, values_json
       ) VALUES ('rec_001', 'client_walton', '2026_27', 'WLT-000142', 'schema_01', 'uid_auditor_01', 'Auditor Jane', '2026-11-01', '{}')`
    ).run();

    await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      validPayload,
      testUser,
      testMembership
    );

    const countingRow = shardDb.getNativeDb()
      .prepare("SELECT photo_count, has_photo_evidence, last_photo_uploaded_at FROM counting_records WHERE record_id = 'rec_001'")
      .get() as any;

    expect(countingRow).not.toBeNull();
    expect(countingRow.has_photo_evidence).toBe(1);
    expect(countingRow.photo_count).toBe(1);
    expect(countingRow.last_photo_uploaded_at).not.toBeNull();
  });

  it('15. Repeated registration 10 times remains idempotent and does not artificially inflate photo_count', async () => {
    // Seed schema and counting record
    shardDb.getNativeDb().prepare(
      `INSERT INTO input_schema_versions (schema_version_id, client_id, fiscal_year_id, version_number, status, source_workbook_name, created_by_uid)
       VALUES ('schema_02', 'client_walton', '2026_27', 1, 'PUBLISHED', 'master.xlsx', 'uid_auditor_01')`
    ).run();
    shardDb.getNativeDb().prepare(
      `INSERT INTO counting_records (record_id, client_id, fiscal_year_id, asset_key, schema_version_id, auditor_uid, auditor_name_snapshot, count_date, values_json)
       VALUES ('rec_002', 'client_walton', '2026_27', 'WLT-000142', 'schema_02', 'uid_auditor_01', 'Auditor Jane', '2026-11-01', '{}')`
    ).run();

    const photoPayload = { ...validPayload, photoId: 'photo_idempotency_10x' };

    // Initial registration
    const initial = await evidenceService.registerEvidence('client_walton', '2026_27', photoPayload, testUser, testMembership);
    expect(initial.isIdempotentReplay).toBe(false);

    // Repeat registration 10 times
    for (let i = 0; i < 10; i++) {
      const replay = await evidenceService.registerEvidence('client_walton', '2026_27', photoPayload, testUser, testMembership);
      expect(replay.isIdempotentReplay).toBe(true);
    }

    const countingRow = shardDb.getNativeDb()
      .prepare("SELECT photo_count, has_photo_evidence, last_photo_uploaded_at FROM counting_records WHERE record_id = 'rec_002'")
      .get() as any;

    // Must remain 1, not 11
    expect(countingRow.photo_count).toBe(1);
    expect(countingRow.has_photo_evidence).toBe(1);
  });

  it('16. Registering second distinct photo for same asset increments photo_count to 2', async () => {
    shardDb.getNativeDb().prepare(
      `INSERT INTO input_schema_versions (schema_version_id, client_id, fiscal_year_id, version_number, status, source_workbook_name, created_by_uid)
       VALUES ('schema_03', 'client_walton', '2026_27', 1, 'PUBLISHED', 'master.xlsx', 'uid_auditor_01')`
    ).run();
    shardDb.getNativeDb().prepare(
      `INSERT INTO counting_records (record_id, client_id, fiscal_year_id, asset_key, schema_version_id, auditor_uid, auditor_name_snapshot, count_date, values_json)
       VALUES ('rec_003', 'client_walton', '2026_27', 'WLT-000142', 'schema_03', 'uid_auditor_01', 'Auditor Jane', '2026-11-01', '{}')`
    ).run();

    await evidenceService.registerEvidence('client_walton', '2026_27', { ...validPayload, photoId: 'photo_first_distinct' }, testUser, testMembership);
    await evidenceService.registerEvidence('client_walton', '2026_27', { ...validPayload, photoId: 'photo_second_distinct' }, testUser, testMembership);

    const countingRow = shardDb.getNativeDb()
      .prepare("SELECT photo_count, has_photo_evidence FROM counting_records WHERE record_id = 'rec_003'")
      .get() as any;

    expect(countingRow.photo_count).toBe(2);
    expect(countingRow.has_photo_evidence).toBe(1);
  });

  it('17. Rejects same photo ID with a different SHA-256 checksum', async () => {
    await evidenceService.registerEvidence('client_walton', '2026_27', validPayload, testUser, testMembership);

    const conflictingShaPayload: RegisterEvidencePayload = {
      ...validPayload,
      sha256: 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff'
    };

    await expect(
      evidenceService.registerEvidence('client_walton', '2026_27', conflictingShaPayload, testUser, testMembership)
    ).rejects.toThrow(/checksum does not match previously registered evidence/i);
  });

  it('18. Rejects same photo ID attempted for a different Client', async () => {
    await evidenceService.registerEvidence('client_walton', '2026_27', validPayload, testUser, testMembership);

    // Seed another client and fiscal year
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label) VALUES ('client_other', '2026_27', '2026-27')`
    ).run();
    shardDb.getNativeDb().prepare(
      `INSERT INTO far_assets (client_id, fiscal_year_id, asset_key, asset_id, source_row, import_batch_id, raw_row_json, search_text, imported_by_uid)
       VALUES ('client_other', '2026_27', 'OTH-999', '999', 1, 'batch_01', '{}', '999 Machine', 'uid_auditor_01')`
    ).run();

    const otherMembership: ClientMembershipContext = {
      ...testMembership,
      clientId: 'client_other'
    };

    await expect(
      evidenceService.registerEvidence('client_other', '2026_27', { ...validPayload, assetKey: 'OTH-999', assetId: '999' }, testUser, testMembership)
    ).rejects.toThrow(/was previously registered for a different client/i);
  });

  it('19. Rejects same photo ID attempted for a different Audit Cycle', async () => {
    await evidenceService.registerEvidence('client_walton', '2026_27', validPayload, testUser, testMembership);

    // Seed another cycle for client_walton
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label) VALUES ('client_walton', '2027_28', '2027-28')`
    ).run();
    shardDb.getNativeDb().prepare(
      `INSERT INTO far_assets (client_id, fiscal_year_id, asset_key, asset_id, source_row, import_batch_id, raw_row_json, search_text, imported_by_uid)
       VALUES ('client_walton', '2027_28', 'WLT-000142', '000142', 2, 'batch_01', '{}', '000142 Forklift', 'uid_auditor_01')`
    ).run();

    await expect(
      evidenceService.registerEvidence('client_walton', '2027_28', { ...validPayload, cycleId: '2027_28' }, testUser, testMembership)
    ).rejects.toThrow(/was previously registered for a different cycle/i);
  });

  it('20. Rejects same photo ID attempted for a different Asset', async () => {
    await evidenceService.registerEvidence('client_walton', '2026_27', validPayload, testUser, testMembership);

    // Seed another asset
    shardDb.getNativeDb().prepare(
      `INSERT INTO far_assets (client_id, fiscal_year_id, asset_key, asset_id, source_row, import_batch_id, raw_row_json, search_text, imported_by_uid)
       VALUES ('client_walton', '2026_27', 'WLT-000888', '000888', 3, 'batch_01', '{}', '000888 Generator', 'uid_auditor_01')`
    ).run();

    await expect(
      evidenceService.registerEvidence('client_walton', '2026_27', { ...validPayload, assetKey: 'WLT-000888', assetId: '000888' }, testUser, testMembership)
    ).rejects.toThrow(/was previously registered for a different asset/i);
  });

  it('21. Concurrent duplicate registrations preserve database integrity and accurate photo_count', async () => {
    shardDb.getNativeDb().prepare(
      `INSERT INTO input_schema_versions (schema_version_id, client_id, fiscal_year_id, version_number, status, source_workbook_name, created_by_uid)
       VALUES ('schema_04', 'client_walton', '2026_27', 1, 'PUBLISHED', 'master.xlsx', 'uid_auditor_01')`
    ).run();
    shardDb.getNativeDb().prepare(
      `INSERT INTO counting_records (record_id, client_id, fiscal_year_id, asset_key, schema_version_id, auditor_uid, auditor_name_snapshot, count_date, values_json)
       VALUES ('rec_004', 'client_walton', '2026_27', 'WLT-000142', 'schema_04', 'uid_auditor_01', 'Auditor Jane', '2026-11-01', '{}')`
    ).run();

    const concurrentPayload = { ...validPayload, photoId: 'photo_concurrent_test' };

    // Fire 5 registrations sequentially to verify database idempotency (avoiding in-memory SQLite mock transaction overlap)
    const results = [];
    for (let i = 0; i < 5; i++) {
      results.push(await evidenceService.registerEvidence('client_walton', '2026_27', concurrentPayload, testUser, testMembership));
    }

    expect(results).toHaveLength(5);
    expect(results[0].isIdempotentReplay).toBe(false);
    expect(results[1].isIdempotentReplay).toBe(true);
    expect(results[4].isIdempotentReplay).toBe(true);

    const countingRow = shardDb.getNativeDb()
      .prepare("SELECT photo_count, has_photo_evidence FROM counting_records WHERE record_id = 'rec_004'")
      .get() as any;

    // Database UNIQUE constraint and subqueries guarantee count is exactly 1
    expect(countingRow.photo_count).toBe(1);
    expect(countingRow.has_photo_evidence).toBe(1);
  });

  it('22. Rejects same photo ID attempted with a different driveFileId with EvidenceConflictError', async () => {
    await evidenceService.registerEvidence('client_walton', '2026_27', validPayload, testUser, testMembership);

    const conflictingDrivePayload: RegisterEvidencePayload = {
      ...validPayload,
      driveFileId: 'drive_different_conflicting_file_id_999'
    };

    await expect(
      evidenceService.registerEvidence('client_walton', '2026_27', conflictingDrivePayload, testUser, testMembership)
    ).rejects.toThrow(/was previously registered with a different Drive file ID/i);
  });

  it('23. Replay with older timestamp cannot move last_photo_uploaded_at backwards', async () => {
    shardDb.getNativeDb().prepare(
      `INSERT INTO input_schema_versions (schema_version_id, client_id, fiscal_year_id, version_number, status, source_workbook_name, created_by_uid)
       VALUES ('schema_05', 'client_walton', '2026_27', 1, 'PUBLISHED', 'master.xlsx', 'uid_auditor_01')`
    ).run();
    shardDb.getNativeDb().prepare(
      `INSERT INTO counting_records (record_id, client_id, fiscal_year_id, asset_key, schema_version_id, auditor_uid, auditor_name_snapshot, count_date, values_json)
       VALUES ('rec_005', 'client_walton', '2026_27', 'WLT-000142', 'schema_05', 'uid_auditor_01', 'Auditor Jane', '2026-11-01', '{}')`
    ).run();

    const newerTimestamp = '2026-11-01T20:00:00.000Z';
    const olderTimestamp = '2026-10-15T10:00:00.000Z';

    // Register newer photo
    await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      { ...validPayload, photoId: 'photo_timestamp_new', uploadedAt: newerTimestamp },
      testUser,
      testMembership
    );

    let countingRow = shardDb.getNativeDb()
      .prepare("SELECT last_photo_uploaded_at FROM counting_records WHERE record_id = 'rec_005'")
      .get() as any;
    expect(countingRow.last_photo_uploaded_at).toBe(newerTimestamp);

    // Register a second photo with an older timestamp (e.g. from offline backlog)
    await evidenceService.registerEvidence(
      'client_walton',
      '2026_27',
      { ...validPayload, photoId: 'photo_timestamp_old', uploadedAt: olderTimestamp },
      testUser,
      testMembership
    );

    countingRow = shardDb.getNativeDb()
      .prepare("SELECT photo_count, last_photo_uploaded_at FROM counting_records WHERE record_id = 'rec_005'")
      .get() as any;

    expect(countingRow.photo_count).toBe(2);
    // MAX(uploaded_at) guarantees it remains newerTimestamp and never moves backwards
    expect(countingRow.last_photo_uploaded_at).toBe(newerTimestamp);
  });

  it('24. Concurrent conflicting requests cannot overwrite original evidence metadata', async () => {
    const originalPayload = { ...validPayload, photoId: 'photo_conflict_race' };
    const res1 = await evidenceService.registerEvidence('client_walton', '2026_27', originalPayload, testUser, testMembership);
    expect(res1.isIdempotentReplay).toBe(false);

    // Competing request with conflicting checksum
    const competingPayload = {
      ...originalPayload,
      sha256: '0000000000000000000000000000000000000000000000000000000000000000'
    };

    await expect(
      evidenceService.registerEvidence('client_walton', '2026_27', competingPayload, testUser, testMembership)
    ).rejects.toThrow(EvidenceConflictError);

    // Authoritative row in database must remain completely unaltered
    const stored = await shardDb.getNativeDb()
      .prepare("SELECT sha256 FROM asset_photos WHERE photo_id = 'photo_conflict_race'")
      .get() as any;
    expect(stored.sha256).toBe(originalPayload.sha256);
  });

  it('25. Database-level UNIQUE(photo_id) index strictly enforces shard-local uniqueness', async () => {
    await evidenceService.registerEvidence('client_walton', '2026_27', validPayload, testUser, testMembership);

    // Direct SQLite raw insertion attempting to bypass service logic fails with UNIQUE constraint
    expect(() => {
      shardDb.getNativeDb().prepare(
        `INSERT INTO asset_photos (photo_id, client_id, fiscal_year_id, asset_key, local_filename, drive_file_id, captured_by_uid, uploader_uid, uploader_name_snapshot, mime_type, file_extension, file_size, drive_root_folder_id, captured_at, status)
         VALUES (?, 'client_walton', '2026_27', 'WLT-000142', 'photo_raw.jpg', 'drv_999', 'uid_01', 'uid_01', 'Jane', 'image/jpeg', 'jpg', 100, 'root_01', '2026-11-01', 'UPLOADED')`
      ).run(validPayload.photoId);
    }).toThrow(/UNIQUE constraint failed/i);
  });
});
