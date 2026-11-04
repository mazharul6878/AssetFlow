import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import { createLocalTestD1, MockD1Database } from './test-helper';
import { ShardRepository } from '../src/repositories/shardRepository';
import { SchemaService } from '../src/services/schemaService';
import { DynamicSchemaVersion, DynamicFieldDefinition } from '../src/domain/dynamicSchema';

describe('Dynamic Input Schema Management and Header Analysis', () => {
  let shardDb: MockD1Database;
  let shardRepo: ShardRepository;
  let schemaService: SchemaService;
  const migrationPath = path.resolve(__dirname, '../migrations/shard/0001_initial_shard.sql');

  beforeEach(() => {
    shardDb = createLocalTestD1(migrationPath);
    shardRepo = new ShardRepository(shardDb as any);
    schemaService = new SchemaService(shardRepo);
  });

  it('confirms that a Client with NO uploaded Master Input Sheet has NO dynamic schema and NO default form', async () => {
    // Client has not uploaded any Master Input Sheet
    const activeSchema = await schemaService.getActiveSchema('client_uninitialized', '2026_27');
    expect(activeSchema).toBeNull();
  });

  it('correctly classifies Excel headers into Populated, Completely Empty, and Sparse columns', () => {
    const headers = ['Asset ID', 'Description', 'Serial Number', 'Condition Status', 'Auditor Notes'];
    const rows = [
      ['WLT-01', 'Forklift A', 'SN-991', '', ''],
      ['WLT-02', 'Forklift B', '', '', ''],
      ['WLT-03', 'Server Rack', 'SN-993', '', ''],
      ['WLT-04', 'Desktop PC', '', '', '']
    ];

    const analysis = schemaService.analyzeWorkbookColumns(headers, rows);

    // 1. Fully Populated: 'Asset ID' and 'Description' (4/4 populated)
    expect(analysis[0].headerText).toBe('Asset ID');
    expect(analysis[0].classification).toBe('FIXED_IMPORTED');
    expect(analysis[0].nonBlankCount).toBe(4);
    expect(analysis[0].isSparse).toBe(false);

    expect(analysis[1].headerText).toBe('Description');
    expect(analysis[1].classification).toBe('FIXED_IMPORTED');
    expect(analysis[1].nonBlankCount).toBe(4);
    expect(analysis[1].isSparse).toBe(false);

    // 2. Sparse: 'Serial Number' (2/4 populated) - MUST NOT be classified as empty!
    expect(analysis[2].headerText).toBe('Serial Number');
    expect(analysis[2].classification).toBe('FIXED_IMPORTED');
    expect(analysis[2].nonBlankCount).toBe(2);
    expect(analysis[2].isSparse).toBe(true);

    // 3. Completely Empty: 'Condition Status' and 'Auditor Notes' (0/4 populated)
    expect(analysis[3].headerText).toBe('Condition Status');
    expect(analysis[3].classification).toBe('EMPTY_HEADER_PENDING_CONFIGURATION');
    expect(analysis[3].nonBlankCount).toBe(0);

    expect(analysis[4].headerText).toBe('Auditor Notes');
    expect(analysis[4].classification).toBe('EMPTY_HEADER_PENDING_CONFIGURATION');
    expect(analysis[4].nonBlankCount).toBe(0);
  });

  function generateMockSchema(clientId: string, fieldCount: number): DynamicSchemaVersion {
    const fields: DynamicFieldDefinition[] = [];
    for (let i = 1; i <= fieldCount; i++) {
      fields.push({
        fieldId: `fld_${clientId}_${i.toString().padStart(2, '0')}`,
        schemaVersionId: `sch_${clientId}_v1`,
        clientId,
        fieldKey: `key_field_${i}`,
        displayLabel: `Dynamic Field ${i}`,
        originalHeader: `Header ${i}`,
        sourceType: i <= 5 ? 'EXCEL_POPULATED' : 'CUSTOM',
        inputMode: i <= 5 ? 'FIXED_IMPORTED' : i % 2 === 0 ? 'DROPDOWN' : 'MANUAL',
        dataType: i === 1 ? 'INTEGER' : i === 2 ? 'DECIMAL' : 'TEXT',
        isRequired: i > 5,
        isVisible: true,
        sortOrder: i,
        promotedFromFar: false,
        allowOtherOption: false,
        isArchived: false,
        options: i % 2 === 0 ? [
          {
            optionId: `opt_${clientId}_${i}_1`,
            fieldId: `fld_${clientId}_${i.toString().padStart(2, '0')}`,
            schemaVersionId: `sch_${clientId}_v1`,
            clientId,
            optionLabel: 'Option Alpha',
            optionValue: 'Alpha',
            sortOrder: 1,
            isActive: true
          }
        ] : []
      });
    }

    return {
      schemaVersionId: `sch_${clientId}_v1`,
      clientId,
      fiscalYearId: '2026_27',
      versionNumber: 1,
      status: 'PUBLISHED',
      sourceWorkbookName: `${clientId}_Register.xlsx`,
      sourceWorksheetName: 'Count Sheet',
      sourceWorksheetIndex: 0,
      headerRowIndex: 0,
      uniqueAssetKeyHeader: 'Header 1',
      createdByUid: 'uid_admin',
      createdAt: new Date().toISOString(),
      fields
    };
  }

  it('supports Client A with 8 configured dynamic fields', async () => {
    const schema8 = generateMockSchema('client_a_8', 8);
    await schemaService.publishSchema(schema8);

    const retrieved = await schemaService.getActiveSchema('client_a_8', '2026_27');
    expect(retrieved).not.toBeNull();
    expect(retrieved?.fields?.length).toBe(8);
  });

  it('supports Client B with 10 configured dynamic fields', async () => {
    const schema10 = generateMockSchema('client_b_10', 10);
    await schemaService.publishSchema(schema10);

    const retrieved = await schemaService.getActiveSchema('client_b_10', '2026_27');
    expect(retrieved).not.toBeNull();
    expect(retrieved?.fields?.length).toBe(10);
  });

  it('supports Client C with 15 configured dynamic fields (typical enterprise audit)', async () => {
    const schema15 = generateMockSchema('client_c_15', 15);
    await schemaService.publishSchema(schema15);

    const retrieved = await schemaService.getActiveSchema('client_c_15', '2026_27');
    expect(retrieved).not.toBeNull();
    expect(retrieved?.fields?.length).toBe(15);
  });

  it('supports Client D with 24 configured dynamic fields (complex industrial facility)', async () => {
    const schema24 = generateMockSchema('client_d_24', 24);
    await schemaService.publishSchema(schema24);

    const retrieved = await schemaService.getActiveSchema('client_d_24', '2026_27');
    expect(retrieved).not.toBeNull();
    expect(retrieved?.fields?.length).toBe(24);
  });
});
