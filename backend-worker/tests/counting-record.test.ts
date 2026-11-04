import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import { createLocalTestD1, MockD1Database } from './test-helper';
import { ShardRepository } from '../src/repositories/shardRepository';
import { SchemaService } from '../src/services/schemaService';
import { CountingService } from '../src/services/countingService';
import { DynamicSchemaVersion } from '../src/domain/dynamicSchema';
import { AuthenticatedUser, ClientMembershipContext } from '../src/domain/permissions';

describe('Dynamic Counting Records and Validation Service', () => {
  let shardDb: MockD1Database;
  let shardRepo: ShardRepository;
  let schemaService: SchemaService;
  let countingService: CountingService;

  const shardMigration = path.resolve(__dirname, '../migrations/shard/0001_initial_shard.sql');

  const testUser: AuthenticatedUser = {
    uid: 'uid_auditor_01',
    email: 'auditor@test.com',
    displayName: 'Lead Auditor Jane',
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
    shardRepo = new ShardRepository(shardDb as any);
    schemaService = new SchemaService(shardRepo);
    countingService = new CountingService(shardRepo);

    // 1. Seed Fiscal Year
    shardDb.getNativeDb().prepare(
      `INSERT INTO fiscal_years (client_id, fiscal_year_id, label)
       VALUES ('client_walton', '2026_27', '2026-27')`
    ).run();

    // 2. Publish a Dynamic Schema Version with 6 specific fields:
    // - fld_desc: FIXED_IMPORTED (from FAR)
    // - fld_cond: DROPDOWN (Good, Damaged, Fair)
    // - fld_qty: MANUAL (Integer, Required)
    // - fld_tags: MANUAL (MULTI_SELECT, JSON Array)
    // - fld_auto_date: AUTO (System date)
    // - fld_archived: MANUAL (Archived)
    const testSchema: DynamicSchemaVersion = {
      schemaVersionId: 'sch_wlt_v1',
      clientId: 'client_walton',
      fiscalYearId: '2026_27',
      versionNumber: 1,
      status: 'PUBLISHED',
      sourceWorkbookName: 'Walton_FAR.xlsx',
      sourceWorksheetName: 'Count Sheet',
      sourceWorksheetIndex: 0,
      headerRowIndex: 0,
      uniqueAssetKeyHeader: 'Asset Code',
      createdByUid: 'uid_admin',
      createdAt: new Date().toISOString(),
      fields: [
        {
          fieldId: 'fld_desc',
          schemaVersionId: 'sch_wlt_v1',
          clientId: 'client_walton',
          fieldKey: 'description',
          displayLabel: 'FAR Description',
          originalHeader: 'Asset Description',
          sourceType: 'EXCEL_POPULATED',
          inputMode: 'FIXED_IMPORTED',
          dataType: 'TEXT',
          isRequired: false,
          isVisible: true,
          sortOrder: 1,
          promotedFromFar: false,
          allowOtherOption: false,
          isArchived: false
        },
        {
          fieldId: 'fld_cond',
          schemaVersionId: 'sch_wlt_v1',
          clientId: 'client_walton',
          fieldKey: 'condition',
          displayLabel: 'Physical Condition',
          originalHeader: null,
          sourceType: 'CUSTOM',
          inputMode: 'DROPDOWN',
          dataType: 'SINGLE_SELECT',
          isRequired: true,
          isVisible: true,
          sortOrder: 2,
          promotedFromFar: false,
          allowOtherOption: true,
          isArchived: false,
          options: [
            {
              optionId: 'opt_good',
              fieldId: 'fld_cond',
              schemaVersionId: 'sch_wlt_v1',
              clientId: 'client_walton',
              optionLabel: 'Good Condition',
              optionValue: 'Good',
              sortOrder: 1,
              isActive: true
            },
            {
              optionId: 'opt_damaged',
              fieldId: 'fld_cond',
              schemaVersionId: 'sch_wlt_v1',
              clientId: 'client_walton',
              optionLabel: 'Damaged Asset',
              optionValue: 'Damaged',
              sortOrder: 2,
              isActive: true
            }
          ]
        },
        {
          fieldId: 'fld_qty',
          schemaVersionId: 'sch_wlt_v1',
          clientId: 'client_walton',
          fieldKey: 'verified_qty',
          displayLabel: 'Physical Count Quantity',
          originalHeader: null,
          sourceType: 'CUSTOM',
          inputMode: 'MANUAL',
          dataType: 'INTEGER',
          isRequired: true,
          isVisible: true,
          sortOrder: 3,
          promotedFromFar: false,
          allowOtherOption: false,
          isArchived: false
        },
        {
          fieldId: 'fld_tags',
          schemaVersionId: 'sch_wlt_v1',
          clientId: 'client_walton',
          fieldKey: 'audit_flags',
          displayLabel: 'Audit Check Flags',
          originalHeader: null,
          sourceType: 'CUSTOM',
          inputMode: 'MANUAL',
          dataType: 'MULTI_SELECT',
          isRequired: false,
          isVisible: true,
          sortOrder: 4,
          promotedFromFar: false,
          allowOtherOption: false,
          isArchived: false,
          options: [
            {
              optionId: 'opt_tag_ok',
              fieldId: 'fld_tags',
              schemaVersionId: 'sch_wlt_v1',
              clientId: 'client_walton',
              optionLabel: 'Barcode Scanned',
              optionValue: 'BARCODE_OK',
              sortOrder: 1,
              isActive: true
            },
            {
              optionId: 'opt_photo_ok',
              fieldId: 'fld_tags',
              schemaVersionId: 'sch_wlt_v1',
              clientId: 'client_walton',
              optionLabel: 'Photo Captured',
              optionValue: 'PHOTO_OK',
              sortOrder: 2,
              isActive: true
            }
          ]
        },
        {
          fieldId: 'fld_auto_date',
          schemaVersionId: 'sch_wlt_v1',
          clientId: 'client_walton',
          fieldKey: 'system_count_date',
          displayLabel: 'Count Date',
          originalHeader: null,
          sourceType: 'SYSTEM',
          inputMode: 'AUTO',
          dataType: 'DATE',
          isRequired: false,
          isVisible: true,
          sortOrder: 5,
          promotedFromFar: false,
          allowOtherOption: false,
          isArchived: false
        },
        {
          fieldId: 'fld_archived',
          schemaVersionId: 'sch_wlt_v1',
          clientId: 'client_walton',
          fieldKey: 'old_legacy_field',
          displayLabel: 'Deprecated Field',
          originalHeader: null,
          sourceType: 'CUSTOM',
          inputMode: 'MANUAL',
          dataType: 'TEXT',
          isRequired: false,
          isVisible: false,
          sortOrder: 6,
          promotedFromFar: false,
          allowOtherOption: false,
          isArchived: true
        }
      ]
    };

    await schemaService.publishSchema(testSchema);

    // 3. Seed FAR Asset
    await shardRepo.insertFarAsset({
      clientId: 'client_walton',
      fiscalYearId: '2026_27',
      assetKey: 'wlt_2026_27_000101_R2',
      assetId: '000101',
      sourceRow: 2,
      rawRowJson: JSON.stringify({ 'Asset Code': '000101', 'Asset Description': 'Precision CNC Lathe' }),
      searchText: '000101 Precision CNC Lathe',
      importedByUid: 'uid_admin',
      importBatchId: 'batch_01'
    });
  });

  it('successfully submits a valid dynamic counting record and saves values_json', async () => {
    const result = await countingService.submitCount(
      'client_walton',
      '2026_27',
      {
        assetKey: 'wlt_2026_27_000101_R2',
        schemaVersionId: 'sch_wlt_v1',
        countDate: '2026-11-01',
        idempotencyKey: 'idemp_key_001',
        values: {
          fld_cond: 'Good',
          fld_qty: 1,
          fld_tags: ['BARCODE_OK', 'PHOTO_OK']
        }
      },
      testUser,
      testMembership
    );

    expect(result.status).toBe('SUBMITTED');
    expect(result.isIdempotentReplay).toBe(false);

    // Verify stored row in database
    const stored = await shardRepo.getCountingRecord('client_walton', 'wlt_2026_27_000101_R2');
    expect(stored).not.toBeNull();
    expect(stored?.schemaVersionId).toBe('sch_wlt_v1');
    expect(stored?.auditorNameSnapshot).toBe('Lead Auditor Jane');

    const parsedValues = JSON.parse(stored!.valuesJson);
    expect(parsedValues.fld_cond).toBe('Good');
    expect(parsedValues.fld_qty).toBe(1);
    expect(parsedValues.fld_tags).toEqual(['BARCODE_OK', 'PHOTO_OK']);
  });

  it('rejects counting records with unknown / unmapped field IDs', async () => {
    await expect(
      countingService.submitCount(
        'client_walton',
        '2026_27',
        {
          assetKey: 'wlt_2026_27_000101_R2',
          schemaVersionId: 'sch_wlt_v1',
          countDate: '2026-11-01',
          values: {
            fld_cond: 'Good',
            fld_qty: 1,
            unmapped_random_field: 'Hacker Input'
          }
        },
        testUser,
        testMembership
      )
    ).rejects.toThrow(/Field ID 'unmapped_random_field' is not defined in active schema version/i);
  });

  it('rejects input attempts to overwrite FIXED_IMPORTED or AUTO fields', async () => {
    await expect(
      countingService.submitCount(
        'client_walton',
        '2026_27',
        {
          assetKey: 'wlt_2026_27_000101_R2',
          schemaVersionId: 'sch_wlt_v1',
          countDate: '2026-11-01',
          values: {
            fld_desc: 'Forged Baseline Description',
            fld_cond: 'Good',
            fld_qty: 1
          }
        },
        testUser,
        testMembership
      )
    ).rejects.toThrow(/is FIXED_IMPORTED and cannot be written directly by client/i);
  });

  it('rejects inputs on archived fields', async () => {
    await expect(
      countingService.submitCount(
        'client_walton',
        '2026_27',
        {
          assetKey: 'wlt_2026_27_000101_R2',
          schemaVersionId: 'sch_wlt_v1',
          countDate: '2026-11-01',
          values: {
            fld_cond: 'Good',
            fld_qty: 1,
            fld_archived: 'Old data'
          }
        },
        testUser,
        testMembership
      )
    ).rejects.toThrow(/is archived and cannot receive inputs/i);
  });

  it('validates single select options and rejects invalid dropdown entries', async () => {
    await expect(
      countingService.submitCount(
        'client_walton',
        '2026_27',
        {
          assetKey: 'wlt_2026_27_000101_R2',
          schemaVersionId: 'sch_wlt_v1',
          countDate: '2026-11-01',
          values: {
            fld_cond: 'Completely Destroyed', // Not in dropdown options
            fld_qty: 1
          }
        },
        testUser,
        testMembership
      )
    ).rejects.toThrow(/is not a valid option for dropdown field/i);
  });

  it('enforces idempotency replay: identical key returns 200 without creating duplicate records', async () => {
    const payload = {
      assetKey: 'wlt_2026_27_000101_R2',
      schemaVersionId: 'sch_wlt_v1',
      countDate: '2026-11-01',
      idempotencyKey: 'unique_device_event_9988',
      values: {
        fld_cond: 'Good',
        fld_qty: 1
      }
    };

    // First call: creates record
    const res1 = await countingService.submitCount('client_walton', '2026_27', payload, testUser, testMembership);
    expect(res1.isIdempotentReplay).toBe(false);

    // Second call with same idempotencyKey: returns existing record safely
    const res2 = await countingService.submitCount('client_walton', '2026_27', payload, testUser, testMembership);
    expect(res2.isIdempotentReplay).toBe(true);
    expect(res2.recordId).toBe(res1.recordId);
  });

  it('prevents multi-auditor overwrite collisions on the same asset', async () => {
    // 1. Auditor 1 counts the asset
    await countingService.submitCount(
      'client_walton',
      '2026_27',
      {
        assetKey: 'wlt_2026_27_000101_R2',
        schemaVersionId: 'sch_wlt_v1',
        countDate: '2026-11-01',
        values: { fld_cond: 'Good', fld_qty: 1 }
      },
      testUser,
      testMembership
    );

    // 2. Auditor 2 attempts to overwrite the same asset in the same cycle
    const auditor2: AuthenticatedUser = {
      uid: 'uid_auditor_02',
      email: 'auditor2@test.com',
      displayName: 'Auditor Bob',
      accountStatus: 'Active',
      globalRole: 'Auditor'
    };
    const membership2: ClientMembershipContext = {
      ...testMembership,
      userUid: 'uid_auditor_02'
    };

    await expect(
      countingService.submitCount(
        'client_walton',
        '2026_27',
        {
          assetKey: 'wlt_2026_27_000101_R2',
          schemaVersionId: 'sch_wlt_v1',
          countDate: '2026-11-01',
          values: { fld_cond: 'Damaged', fld_qty: 1 }
        },
        auditor2,
        membership2
      )
    ).rejects.toThrow(/Conflict: Asset 'wlt_2026_27_000101_R2' has already been verified by auditor 'Lead Auditor Jane'/i);
  });

  it('allows an open cycle pinned to an ARCHIVED schema to remain operational', async () => {
    // 1. Archive the schema v1
    shardDb.getNativeDb().prepare(
      "UPDATE input_schema_versions SET status = 'ARCHIVED' WHERE schema_version_id = 'sch_wlt_v1'"
    ).run();

    // 2. Pin fiscal year 2026_27 to sch_wlt_v1
    shardDb.getNativeDb().prepare(
      "UPDATE fiscal_years SET active_schema_version_id = 'sch_wlt_v1' WHERE client_id = 'client_walton' AND fiscal_year_id = '2026_27'"
    ).run();

    // 3. Counting in this cycle must succeed because it is pinned to this cycle
    const res = await countingService.submitCount(
      'client_walton',
      '2026_27',
      {
        assetKey: 'wlt_2026_27_000101_R2',
        schemaVersionId: 'sch_wlt_v1',
        countDate: '2026-11-01',
        values: { fld_cond: 'Good', fld_qty: 1 }
      },
      testUser,
      testMembership
    );
    expect(res.status).toBe('SUBMITTED');
  });
});
