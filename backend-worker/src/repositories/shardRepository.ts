// Repository for Client-Scoped Data Shard Operations

import { DynamicSchemaVersion, DynamicFieldDefinition, DynamicFieldOption } from '../domain/dynamicSchema';
import { CountingRecord } from '../domain/countingRecord';
import { EvidenceMetadata } from '../domain/evidence';

export class ShardRepository {
  constructor(private shardDb: D1Database) {}

  private async loadFieldsAndOptions(schemaVersionId: string): Promise<DynamicFieldDefinition[]> {
    const fieldRows = await this.shardDb
      .prepare(
        `SELECT * FROM input_field_definitions
         WHERE schema_version_id = ?
         ORDER BY sort_order ASC`
      )
      .bind(schemaVersionId)
      .all<{
        field_id: string;
        schema_version_id: string;
        client_id: string;
        field_key: string;
        display_label: string;
        original_header: string | null;
        source_type: string;
        source_worksheet_index: number | null;
        source_column_index: number | null;
        source_column_letter: string | null;
        input_mode: string;
        data_type: string;
        is_required: number;
        is_visible: number;
        sort_order: number;
        validation_rules_json: string | null;
        auto_source_rule: string | null;
        promoted_from_far: number;
        destination_excel_header: string | null;
        semantic_role: string | null;
        allow_other_option: number;
        is_archived: number;
      }>();

    const optionRows = await this.shardDb
      .prepare(
        `SELECT * FROM input_field_options
         WHERE schema_version_id = ? AND is_active = 1
         ORDER BY sort_order ASC`
      )
      .bind(schemaVersionId)
      .all<{
        option_id: string;
        field_id: string;
        schema_version_id: string;
        client_id: string;
        option_label: string;
        option_value: string;
        sort_order: number;
        is_active: number;
      }>();

    const optionsByField = new Map<string, DynamicFieldOption[]>();
    for (const opt of optionRows.results || []) {
      const list = optionsByField.get(opt.field_id) || [];
      list.push({
        optionId: opt.option_id,
        fieldId: opt.field_id,
        schemaVersionId: opt.schema_version_id,
        clientId: opt.client_id,
        optionLabel: opt.option_label,
        optionValue: opt.option_value,
        sortOrder: opt.sort_order,
        isActive: opt.is_active === 1
      });
      optionsByField.set(opt.field_id, list);
    }

    return (fieldRows.results || []).map(f => ({
      fieldId: f.field_id,
      schemaVersionId: f.schema_version_id,
      clientId: f.client_id,
      fieldKey: f.field_key,
      displayLabel: f.display_label,
      originalHeader: f.original_header,
      sourceType: f.source_type as any,
      sourceWorksheetIndex: f.source_worksheet_index ?? undefined,
      sourceColumnIndex: f.source_column_index ?? undefined,
      sourceColumnLetter: f.source_column_letter ?? undefined,
      inputMode: f.input_mode as any,
      dataType: f.data_type as any,
      isRequired: f.is_required === 1,
      isVisible: f.is_visible === 1,
      sortOrder: f.sort_order,
      validationRulesJson: f.validation_rules_json ?? undefined,
      autoSourceRule: f.auto_source_rule ?? undefined,
      promotedFromFar: f.promoted_from_far === 1,
      destinationExcelHeader: f.destination_excel_header ?? undefined,
      semanticRole: f.semantic_role ?? undefined,
      allowOtherOption: f.allow_other_option === 1,
      isArchived: f.is_archived === 1,
      options: optionsByField.get(f.field_id) || []
    }));
  }

  async getSchemaById(schemaVersionId: string): Promise<DynamicSchemaVersion | null> {
    const versionRow = await this.shardDb
      .prepare('SELECT * FROM input_schema_versions WHERE schema_version_id = ?')
      .bind(schemaVersionId)
      .first<{
        schema_version_id: string;
        client_id: string;
        fiscal_year_id: string;
        version_number: number;
        status: string;
        source_workbook_name: string;
        source_worksheet_name: string;
        source_worksheet_index: number;
        header_row_index: number;
        unique_asset_key_header: string;
        change_summary: string | null;
        created_by_uid: string;
        created_at: string;
        published_by_uid: string | null;
        published_at: string | null;
        previous_version_id: string | null;
      }>();

    if (!versionRow) return null;

    const fields = await this.loadFieldsAndOptions(versionRow.schema_version_id);

    return {
      schemaVersionId: versionRow.schema_version_id,
      clientId: versionRow.client_id,
      fiscalYearId: versionRow.fiscal_year_id,
      versionNumber: versionRow.version_number,
      status: versionRow.status as any,
      sourceWorkbookName: versionRow.source_workbook_name,
      sourceWorksheetName: versionRow.source_worksheet_name,
      sourceWorksheetIndex: versionRow.source_worksheet_index,
      headerRowIndex: versionRow.header_row_index,
      uniqueAssetKeyHeader: versionRow.unique_asset_key_header,
      changeSummary: versionRow.change_summary ?? undefined,
      createdByUid: versionRow.created_by_uid,
      createdAt: versionRow.created_at,
      publishedByUid: versionRow.published_by_uid ?? undefined,
      publishedAt: versionRow.published_at ?? undefined,
      previousVersionId: versionRow.previous_version_id ?? undefined,
      fields
    };
  }

  async getActiveSchema(clientId: string, fiscalYearId: string): Promise<DynamicSchemaVersion | null> {
    const versionRow = await this.shardDb
      .prepare(
        `SELECT * FROM input_schema_versions
         WHERE client_id = ? AND fiscal_year_id = ? AND status = 'PUBLISHED'
         ORDER BY version_number DESC LIMIT 1`
      )
      .bind(clientId, fiscalYearId)
      .first<{ schema_version_id: string }>();

    if (!versionRow) return null;
    return this.getSchemaById(versionRow.schema_version_id);
  }

  async getSchemaForCycle(clientId: string, fiscalYearId: string): Promise<DynamicSchemaVersion | null> {
    const cycleRow = await this.shardDb
      .prepare(
        `SELECT active_schema_version_id, is_closed
         FROM fiscal_years
         WHERE client_id = ? AND fiscal_year_id = ?`
      )
      .bind(clientId, fiscalYearId)
      .first<{ active_schema_version_id: string | null; is_closed: number }>();

    if (cycleRow?.active_schema_version_id) {
      const pinned = await this.getSchemaById(cycleRow.active_schema_version_id);
      if (pinned) {
        return pinned;
      }
    }

    return this.getActiveSchema(clientId, fiscalYearId);
  }

  async saveSchema(schema: DynamicSchemaVersion): Promise<void> {
    await this.shardDb
      .prepare(
        `INSERT INTO input_schema_versions (
            schema_version_id, client_id, fiscal_year_id, version_number,
            status, source_workbook_name, source_worksheet_name,
            source_worksheet_index, header_row_index, unique_asset_key_header,
            change_summary, created_by_uid, published_by_uid, published_at, previous_version_id
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(
        schema.schemaVersionId,
        schema.clientId,
        schema.fiscalYearId,
        schema.versionNumber,
        schema.status,
        schema.sourceWorkbookName,
        schema.sourceWorksheetName,
        schema.sourceWorksheetIndex,
        schema.headerRowIndex,
        schema.uniqueAssetKeyHeader,
        schema.changeSummary || null,
        schema.createdByUid,
        schema.publishedByUid || null,
        schema.publishedAt || null,
        schema.previousVersionId || null
      )
      .run();

    // Insert Field Definitions
    for (const field of schema.fields || []) {
      await this.shardDb
        .prepare(
          `INSERT INTO input_field_definitions (
              field_id, schema_version_id, client_id, field_key, display_label,
              original_header, source_type, source_worksheet_index, source_column_index,
              source_column_letter, input_mode, data_type, is_required, is_visible,
              sort_order, validation_rules_json, auto_source_rule, promoted_from_far,
              destination_excel_header, semantic_role, allow_other_option, is_archived
           ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .bind(
          field.fieldId,
          schema.schemaVersionId,
          schema.clientId,
          field.fieldKey,
          field.displayLabel,
          field.originalHeader || null,
          field.sourceType,
          field.sourceWorksheetIndex ?? null,
          field.sourceColumnIndex ?? null,
          field.sourceColumnLetter || null,
          field.inputMode,
          field.dataType,
          field.isRequired ? 1 : 0,
          field.isVisible ? 1 : 0,
          field.sortOrder,
          field.validationRulesJson || null,
          field.autoSourceRule || null,
          field.promotedFromFar ? 1 : 0,
          field.destinationExcelHeader || null,
          field.semanticRole || null,
          field.allowOtherOption ? 1 : 0,
          field.isArchived ? 1 : 0
        )
        .run();

      // Insert Dropdown Options
      for (const opt of field.options || []) {
        await this.shardDb
          .prepare(
            `INSERT INTO input_field_options (
                option_id, field_id, schema_version_id, client_id,
                option_label, option_value, sort_order, is_active
             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
          )
          .bind(
            opt.optionId,
            field.fieldId,
            schema.schemaVersionId,
            schema.clientId,
            opt.optionLabel,
            opt.optionValue,
            opt.sortOrder,
            opt.isActive ? 1 : 0
          )
          .run();
      }
    }
  }

  async getFarAsset(clientId: string, fiscalYearId: string, assetKey: string): Promise<any | null> {
    return this.shardDb
      .prepare('SELECT * FROM far_assets WHERE client_id = ? AND fiscal_year_id = ? AND asset_key = ?')
      .bind(clientId, fiscalYearId, assetKey)
      .first();
  }

  async insertFarAsset(asset: {
    clientId: string;
    fiscalYearId: string;
    assetKey: string;
    assetId: string; // TEXT preserving leading zeros
    sourceRow: number;
    rawRowJson: string;
    searchText: string;
    importedByUid: string;
    importBatchId: string;
  }): Promise<void> {
    await this.shardDb
      .prepare(
        `INSERT INTO far_assets (
            client_id, fiscal_year_id, asset_key, asset_id, source_row,
            import_batch_id, raw_row_json, search_text, imported_by_uid
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(
        asset.clientId,
        asset.fiscalYearId,
        asset.assetKey,
        asset.assetId,
        asset.sourceRow,
        asset.importBatchId,
        asset.rawRowJson,
        asset.searchText,
        asset.importedByUid
      )
      .run();
  }

  async batchInsertFarAssets(
    clientId: string,
    fiscalYearId: string,
    importBatchId: string,
    importedByUid: string,
    assets: Array<{
      assetKey: string;
      assetId: string;
      sourceRow: number;
      sourceWorksheet?: string;
      sourceWorksheetIndex?: number;
      rawRowJson: string;
      searchText: string;
    }>
  ): Promise<number> {
    const stmts = assets.map(a =>
      this.shardDb
        .prepare(
          `INSERT INTO far_assets (
              client_id, fiscal_year_id, asset_key, asset_id, source_row,
              source_worksheet, source_worksheet_index, import_batch_id,
              raw_row_json, search_text, imported_by_uid
           ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
           ON CONFLICT(client_id, fiscal_year_id, asset_key) DO UPDATE SET
              asset_id = excluded.asset_id,
              source_row = excluded.source_row,
              source_worksheet = excluded.source_worksheet,
              source_worksheet_index = excluded.source_worksheet_index,
              import_batch_id = excluded.import_batch_id,
              raw_row_json = excluded.raw_row_json,
              search_text = excluded.search_text,
              imported_by_uid = excluded.imported_by_uid,
              imported_at = datetime('now'),
              is_active = 1`
        )
        .bind(
          clientId,
          fiscalYearId,
          a.assetKey,
          a.assetId,
          a.sourceRow,
          a.sourceWorksheet || 'Count Sheet',
          a.sourceWorksheetIndex || 0,
          importBatchId,
          a.rawRowJson,
          a.searchText,
          importedByUid
        )
    );

    await this.shardDb.batch(stmts);
    return assets.length;
  }

  async searchFarAssets(params: {
    clientId: string;
    fiscalYearId: string;
    query?: string;
    cursor?: string;
    limit?: number;
  }): Promise<{ assets: any[]; nextCursor?: string }> {
    const limit = Math.min(Math.max(params.limit || 50, 1), 200);
    let sql = `SELECT client_id, fiscal_year_id, asset_key, asset_id, source_row, source_worksheet,
                      source_worksheet_index, import_batch_id, raw_row_json, search_text, imported_at, is_active
               FROM far_assets
               WHERE client_id = ? AND fiscal_year_id = ? AND is_active = 1`;
    const bindParams: any[] = [params.clientId, params.fiscalYearId];

    if (params.query && params.query.trim().length > 0) {
      const q = `%${params.query.trim().toLowerCase()}%`;
      sql += ` AND (search_text LIKE ? OR asset_key LIKE ? OR asset_id LIKE ?)`;
      bindParams.push(q, q, q);
    }

    if (params.cursor) {
      sql += ` AND asset_key > ?`;
      bindParams.push(params.cursor);
    }

    sql += ` ORDER BY asset_key ASC LIMIT ?`;
    bindParams.push(limit + 1);

    const res = await this.shardDb.prepare(sql).bind(...bindParams).all<any>();
    const rows = res.results || [];
    let nextCursor: string | undefined = undefined;

    if (rows.length > limit) {
      rows.pop();
      nextCursor = rows[rows.length - 1].asset_key;
    }

    return {
      assets: rows,
      nextCursor
    };
  }

  async getCountingRecord(clientId: string, assetKey: string): Promise<CountingRecord | null> {
    const row = await this.shardDb
      .prepare('SELECT * FROM counting_records WHERE client_id = ? AND asset_key = ?')
      .bind(clientId, assetKey)
      .first<{
        record_id: string;
        client_id: string;
        fiscal_year_id: string;
        asset_key: string;
        schema_version_id: string;
        auditor_uid: string;
        auditor_name_snapshot: string;
        record_status: string;
        batch_id: string | null;
        count_date: string;
        values_json: string;
        idempotency_key: string | null;
        record_version: number;
        created_at: string;
        updated_at: string;
      }>();

    if (!row) return null;

    return {
      recordId: row.record_id,
      clientId: row.client_id,
      fiscalYearId: row.fiscal_year_id,
      assetKey: row.asset_key,
      schemaVersionId: row.schema_version_id,
      auditorUid: row.auditor_uid,
      auditorNameSnapshot: row.auditor_name_snapshot,
      recordStatus: row.record_status as any,
      batchId: row.batch_id ?? undefined,
      countDate: row.count_date,
      valuesJson: row.values_json,
      idempotencyKey: row.idempotency_key ?? undefined,
      recordVersion: row.record_version,
      createdAt: row.created_at,
      updatedAt: row.updated_at
    };
  }

  async upsertCountingRecord(record: CountingRecord): Promise<void> {
    await this.shardDb
      .prepare(
        `INSERT INTO counting_records (
            record_id, client_id, fiscal_year_id, asset_key, schema_version_id,
            auditor_uid, auditor_name_snapshot, record_status, batch_id,
            count_date, values_json, idempotency_key, record_version, created_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
         ON CONFLICT(record_id) DO UPDATE SET
            schema_version_id = excluded.schema_version_id,
            auditor_uid = excluded.auditor_uid,
            auditor_name_snapshot = excluded.auditor_name_snapshot,
            record_status = excluded.record_status,
            batch_id = excluded.batch_id,
            count_date = excluded.count_date,
            values_json = excluded.values_json,
            record_version = counting_records.record_version + 1,
            updated_at = datetime('now')`
      )
      .bind(
        record.recordId,
        record.clientId,
        record.fiscalYearId,
        record.assetKey,
        record.schemaVersionId,
        record.auditorUid,
        record.auditorNameSnapshot,
        record.recordStatus,
        record.batchId || null,
        record.countDate,
        record.valuesJson,
        record.idempotencyKey || null,
        record.recordVersion
      )
      .run();
  }

  async insertFoundNotInFar(record: FoundNotInFarEntity): Promise<void> {
    await this.shardDb
      .prepare(
        `INSERT INTO found_not_in_far (
          temp_id, client_id, fiscal_year_id, description, raw_values_json,
          auditor_uid, auditor_name, count_date, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?, datetime('now')))
        ON CONFLICT(temp_id) DO UPDATE SET
          description = excluded.description,
          raw_values_json = excluded.raw_values_json,
          count_date = excluded.count_date`
      )
      .bind(
        record.temp_id,
        record.client_id,
        record.fiscal_year_id,
        record.description,
        record.raw_values_json,
        record.auditor_uid,
        record.auditor_name,
        record.count_date,
        record.created_at || null
      )
      .run();
  }

  async listFoundNotInFar(clientId: string, fiscalYearId?: string): Promise<FoundNotInFarEntity[]> {
    let query = `SELECT * FROM found_not_in_far WHERE client_id = ?`;
    const params: any[] = [clientId];
    if (fiscalYearId) {
      query += ` AND fiscal_year_id = ?`;
      params.push(fiscalYearId);
    }
    query += ` ORDER BY created_at DESC`;

    const result = await this.shardDb.prepare(query).bind(...params).all<FoundNotInFarEntity>();
    return result.results || [];
  }

  async getFoundNotInFar(clientId: string, tempId: string): Promise<FoundNotInFarEntity | null> {
    const result = await this.shardDb
      .prepare(`SELECT * FROM found_not_in_far WHERE client_id = ? AND temp_id = ?`)
      .bind(clientId, tempId)
      .first<FoundNotInFarEntity>();
    return result || null;
  }

  async checkFiscalYearExists(clientId: string, cycleId: string): Promise<boolean> {
    const row = await this.shardDb
      .prepare('SELECT fiscal_year_id FROM fiscal_years WHERE client_id = ? AND fiscal_year_id = ?')
      .bind(clientId, cycleId)
      .first();
    return row !== null;
  }

  async getPhotoById(clientId: string, photoId: string): Promise<EvidenceMetadata | null> {
    const row = await this.shardDb
      .prepare('SELECT * FROM asset_photos WHERE client_id = ? AND photo_id = ?')
      .bind(clientId, photoId)
      .first<any>();
    if (!row) return null;
    return this.mapPhotoRow(row);
  }

  async getPhotoByGlobalId(photoId: string): Promise<EvidenceMetadata | null> {
    const row = await this.shardDb
      .prepare('SELECT * FROM asset_photos WHERE photo_id = ?')
      .bind(photoId)
      .first<any>();
    if (!row) return null;
    return this.mapPhotoRow(row);
  }

  async listPhotosForAsset(clientId: string, cycleId: string, assetKey: string): Promise<EvidenceMetadata[]> {
    const res = await this.shardDb
      .prepare('SELECT * FROM asset_photos WHERE client_id = ? AND fiscal_year_id = ? AND asset_key = ? ORDER BY captured_at ASC')
      .bind(clientId, cycleId, assetKey)
      .all<any>();
    return (res.results || []).map(r => this.mapPhotoRow(r));
  }

  async listPhotosForCycle(clientId: string, cycleId: string, limit: number = 100): Promise<EvidenceMetadata[]> {
    const res = await this.shardDb
      .prepare('SELECT * FROM asset_photos WHERE client_id = ? AND fiscal_year_id = ? ORDER BY captured_at DESC LIMIT ?')
      .bind(clientId, cycleId, limit)
      .all<any>();
    return (res.results || []).map(r => this.mapPhotoRow(r));
  }

  async upsertPhotoMetadata(photo: EvidenceMetadata): Promise<void> {
    const photoStmt = this.shardDb
      .prepare(
        `INSERT INTO asset_photos (
            photo_id, client_id, fiscal_year_id, asset_key, asset_id, audit_record_id,
            local_filename, drive_file_id, drive_web_view_link, captured_by_uid,
            uploader_uid, uploader_name_snapshot, photo_category, caption,
            mime_type, file_extension, file_size, sha256,
            drive_root_folder_id, drive_date_folder_id, captured_at, uploaded_at, status
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(photo_id) DO NOTHING`
      )
      .bind(
        photo.photoId,
        photo.clientId,
        photo.cycleId,
        photo.assetKey,
        photo.assetId,
        photo.auditRecordId || null,
        photo.localFilename || null,
        photo.driveFileId,
        photo.driveWebViewLink || null,
        photo.uploaderUid,
        photo.uploaderUid,
        photo.uploaderNameSnapshot,
        photo.photoCategory,
        photo.caption || null,
        photo.mimeType,
        photo.fileExtension,
        photo.fileSize,
        photo.sha256 || null,
        photo.driveRootFolderId,
        photo.driveDateFolderId || null,
        photo.capturedAt,
        photo.uploadedAt,
        photo.status
      );

    const summaryStmt = this.shardDb
      .prepare(
        `UPDATE counting_records SET
            photo_count = (SELECT COUNT(*) FROM asset_photos WHERE client_id = ? AND fiscal_year_id = ? AND asset_key = ? AND status = 'UPLOADED'),
            has_photo_evidence = CASE WHEN (SELECT COUNT(*) FROM asset_photos WHERE client_id = ? AND fiscal_year_id = ? AND asset_key = ? AND status = 'UPLOADED') > 0 THEN 1 ELSE 0 END,
            last_photo_uploaded_at = (SELECT MAX(uploaded_at) FROM asset_photos WHERE client_id = ? AND fiscal_year_id = ? AND asset_key = ? AND status = 'UPLOADED')
         WHERE client_id = ? AND fiscal_year_id = ? AND asset_key = ?`
      )
      .bind(
        photo.clientId,
        photo.cycleId,
        photo.assetKey,
        photo.clientId,
        photo.cycleId,
        photo.assetKey,
        photo.clientId,
        photo.cycleId,
        photo.assetKey,
        photo.clientId,
        photo.cycleId,
        photo.assetKey
      );

    await this.shardDb.batch([photoStmt, summaryStmt]);
  }

  async listFiscalYears(clientId: string): Promise<Array<{
    clientId: string;
    client_id: string;
    fiscalYearId: string;
    fiscal_year_id: string;
    label: string;
    isClosed: boolean;
    is_closed: number;
    activeSchemaVersionId: string | null;
  }>> {
    const rows = await this.shardDb
      .prepare('SELECT client_id, fiscal_year_id, label, is_closed, active_schema_version_id FROM fiscal_years WHERE client_id = ? ORDER BY fiscal_year_id DESC')
      .bind(clientId)
      .all<any>();
    return (rows.results || []).map((r: any) => ({
      clientId: r.client_id,
      client_id: r.client_id,
      fiscalYearId: r.fiscal_year_id,
      fiscal_year_id: r.fiscal_year_id,
      label: r.label,
      isClosed: r.is_closed === 1,
      is_closed: r.is_closed,
      activeSchemaVersionId: r.active_schema_version_id || null
    }));
  }

  async createFiscalYear(clientId: string, fiscalYearId: string, label: string): Promise<{
    clientId: string;
    client_id: string;
    fiscalYearId: string;
    fiscal_year_id: string;
    label: string;
    isClosed: boolean;
    is_closed: number;
  }> {
    await this.shardDb
      .prepare(
        `INSERT OR IGNORE INTO fiscal_years (client_id, fiscal_year_id, label, is_closed)
         VALUES (?, ?, ?, 0)`
      )
      .bind(clientId, fiscalYearId, label)
      .run();

    return {
      clientId,
      client_id: clientId,
      fiscalYearId,
      fiscal_year_id: fiscalYearId,
      label,
      isClosed: false,
      is_closed: 0
    };
  }

  private mapPhotoRow(row: any): EvidenceMetadata {
    return {
      photoId: row.photo_id,
      clientId: row.client_id,
      cycleId: row.fiscal_year_id,
      assetKey: row.asset_key,
      assetId: row.asset_id || row.asset_key,
      auditRecordId: row.audit_record_id ?? undefined,
      uploaderUid: row.uploader_uid || row.captured_by_uid,
      uploaderNameSnapshot: row.uploader_name_snapshot || 'Auditor',
      photoCategory: row.photo_category || 'GENERAL',
      caption: row.caption ?? undefined,
      mimeType: row.mime_type || 'image/jpeg',
      fileExtension: row.file_extension || 'jpg',
      fileSize: row.file_size || 0,
      sha256: row.sha256 ?? undefined,
      driveFileId: row.drive_file_id,
      driveWebViewLink: row.drive_web_view_link ?? undefined,
      driveRootFolderId: row.drive_root_folder_id || '',
      driveDateFolderId: row.drive_date_folder_id ?? undefined,
      localFilename: row.local_filename ?? undefined,
      capturedAt: row.captured_at,
      uploadedAt: row.uploaded_at || row.captured_at,
      status: row.status as any
    };
  }

  // --- Access Requests ---

  async getPendingAccessRequest(clientId: string, requesterUid: string): Promise<AccessRequestEntity | null> {
    return this.shardDb
      .prepare('SELECT * FROM access_requests WHERE client_id = ? AND requester_uid = ? AND status = ?')
      .bind(clientId, requesterUid, 'Pending')
      .first<AccessRequestEntity>();
  }

  async createAccessRequest(req: AccessRequestEntity): Promise<void> {
    await this.shardDb
      .prepare(
        `INSERT INTO access_requests (
            request_id, client_id, fiscal_year_id, requester_uid, requester_email, reason, status, requested_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(
        req.request_id,
        req.client_id,
        req.fiscal_year_id,
        req.requester_uid,
        req.requester_email,
        req.reason || null,
        req.status,
        req.requested_at
      )
      .run();
  }

  async listAccessRequestsForClient(clientId: string): Promise<AccessRequestEntity[]> {
    const res = await this.shardDb
      .prepare('SELECT * FROM access_requests WHERE client_id = ? ORDER BY requested_at DESC')
      .bind(clientId)
      .all<AccessRequestEntity>();
    return res.results || [];
  }

  async listAccessRequestsForUser(requesterUid: string): Promise<AccessRequestEntity[]> {
    const res = await this.shardDb
      .prepare('SELECT * FROM access_requests WHERE requester_uid = ? ORDER BY requested_at DESC')
      .bind(requesterUid)
      .all<AccessRequestEntity>();
    return res.results || [];
  }

  async decideAccessRequest(requestId: string, status: 'Approved' | 'Rejected', decidedByUid: string): Promise<AccessRequestEntity | null> {
    await this.shardDb
      .prepare(
        `UPDATE access_requests
         SET status = ?, decided_at = datetime('now'), decided_by_uid = ?
         WHERE request_id = ?`
      )
      .bind(status, decidedByUid, requestId)
      .run();

    return this.shardDb
      .prepare('SELECT * FROM access_requests WHERE request_id = ?')
      .bind(requestId)
      .first<AccessRequestEntity>();
  }

  // =========================================================================
  // DAILY BATCHES, SUBMISSIONS & REVIEW QUEUE
  // =========================================================================

  async createOrSubmitDailyBatch(params: {
    clientId: string;
    fiscalYearId: string;
    batchId: string;
    batchDate: string;
    auditorUid: string;
    auditorName: string;
    items: Array<{
      recordId?: string;
      assetKey: string;
      schemaVersionId: string;
      countDate?: string;
      valuesJson?: string;
      idempotencyKey?: string;
    }>;
  }): Promise<{ batchId: string; submissionId: string; totalItems: number }> {
    const submissionId = `sub_${params.batchId}`;
    const batchDate = params.batchDate || new Date().toISOString().slice(0, 10);
    const totalItems = params.items.length;

    const batchStmt = this.shardDb
      .prepare(
        `INSERT INTO daily_batches (
           batch_id, client_id, fiscal_year_id, auditor_uid, auditor_name,
           batch_date, status, total_items, created_at, updated_at
         ) VALUES (?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, datetime('now'), datetime('now'))
         ON CONFLICT(batch_id) DO UPDATE SET
           status = 'SUBMITTED',
           total_items = excluded.total_items,
           updated_at = datetime('now')`
      )
      .bind(
        params.batchId,
        params.clientId,
        params.fiscalYearId,
        params.auditorUid,
        params.auditorName,
        batchDate,
        totalItems
      );

    const submissionStmt = this.shardDb
      .prepare(
        `INSERT INTO submissions (
           submission_id, client_id, fiscal_year_id, batch_id,
           submitted_by_uid, submitted_by_name, status, item_count, submitted_at
         ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING_REVIEW', ?, datetime('now'))
         ON CONFLICT(submission_id) DO UPDATE SET
           status = 'PENDING_REVIEW',
           item_count = excluded.item_count,
           submitted_at = datetime('now')`
      )
      .bind(
        submissionId,
        params.clientId,
        params.fiscalYearId,
        params.batchId,
        params.auditorUid,
        params.auditorName,
        totalItems
      );

    const recordStmts = params.items.map((item, idx) => {
      const recordId = item.recordId || `rec_${params.batchId}_${item.assetKey || idx}`;
      const countDate = item.countDate || batchDate;
      const valuesJson = item.valuesJson || '{}';

      return this.shardDb
        .prepare(
          `INSERT INTO counting_records (
             record_id, client_id, fiscal_year_id, asset_key, schema_version_id,
             auditor_uid, auditor_name_snapshot, record_status, batch_id,
             count_date, values_json, idempotency_key, record_version, created_at, updated_at
           ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING_REVIEW', ?, ?, ?, ?, 1, datetime('now'), datetime('now'))
           ON CONFLICT(record_id) DO UPDATE SET
             schema_version_id = excluded.schema_version_id,
             auditor_uid = excluded.auditor_uid,
             auditor_name_snapshot = excluded.auditor_name_snapshot,
             record_status = 'PENDING_REVIEW',
             batch_id = excluded.batch_id,
             count_date = excluded.count_date,
             values_json = excluded.values_json,
             record_version = counting_records.record_version + 1,
             updated_at = datetime('now')`
        )
        .bind(
          recordId,
          params.clientId,
          params.fiscalYearId,
          item.assetKey,
          item.schemaVersionId,
          params.auditorUid,
          params.auditorName,
          params.batchId,
          countDate,
          valuesJson,
          item.idempotencyKey || null
        );
    });

    await this.shardDb.batch([batchStmt, submissionStmt, ...recordStmts]);
    return { batchId: params.batchId, submissionId, totalItems };
  }

  async listDailyBatches(
    clientId: string,
    auditorUid?: string,
    status?: string
  ): Promise<Array<any>> {
    let sql = `
      SELECT b.batch_id, b.client_id, b.fiscal_year_id, b.auditor_uid, b.auditor_name,
             b.batch_date, b.status as batch_status, b.total_items, b.created_at, b.updated_at,
             s.submission_id, s.status as submission_status, s.submitted_at, s.reviewed_by_uid, s.reviewed_at
      FROM daily_batches b
      LEFT JOIN submissions s ON b.batch_id = s.batch_id
      WHERE b.client_id = ?
    `;
    const bindings: any[] = [clientId];

    if (auditorUid) {
      sql += ' AND b.auditor_uid = ?';
      bindings.push(auditorUid);
    }

    if (status) {
      sql += ' AND (b.status = ? OR s.status = ?)';
      bindings.push(status, status);
    }

    sql += ' ORDER BY b.created_at DESC';

    const res = await this.shardDb.prepare(sql).bind(...bindings).all<any>();
    return res.results || [];
  }

  async getDailyBatch(
    clientId: string,
    batchId: string
  ): Promise<{ batch: any; submission: any; records: any[] } | null> {
    const batch = await this.shardDb
      .prepare('SELECT * FROM daily_batches WHERE client_id = ? AND batch_id = ?')
      .bind(clientId, batchId)
      .first<any>();

    if (!batch) return null;

    const submission = await this.shardDb
      .prepare('SELECT * FROM submissions WHERE client_id = ? AND batch_id = ?')
      .bind(clientId, batchId)
      .first<any>();

    const recordsRes = await this.shardDb
      .prepare('SELECT * FROM counting_records WHERE client_id = ? AND batch_id = ? ORDER BY asset_key ASC')
      .bind(clientId, batchId)
      .all<any>();

    return {
      batch,
      submission,
      records: recordsRes.results || []
    };
  }

  async reviewDailyBatch(
    clientId: string,
    batchId: string,
    reviewerUid: string,
    decision: 'APPROVE' | 'REJECT',
    remarks?: string
  ): Promise<{ success: boolean; status: string }> {
    const batch = await this.shardDb
      .prepare('SELECT * FROM daily_batches WHERE client_id = ? AND batch_id = ?')
      .bind(clientId, batchId)
      .first<any>();

    if (!batch) {
      const err: any = new Error(`Daily batch '${batchId}' not found for client '${clientId}'`);
      err.statusCode = 404;
      throw err;
    }

    // Strict Separation of Duties: Submitter cannot review or approve their own batch
    if (batch.auditor_uid === reviewerUid) {
      const err: any = new Error(
        'Separation of Duties violation: The auditor who submitted this daily sheet cannot review or approve it.'
      );
      err.statusCode = 403;
      err.code = 'SEPARATION_OF_DUTIES_VIOLATION';
      throw err;
    }

    const newBatchStatus = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED';
    const newSubmissionStatus = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED';
    const newRecordStatus = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED';

    const updateBatchStmt = this.shardDb
      .prepare(
        `UPDATE daily_batches
         SET status = ?, updated_at = datetime('now')
         WHERE client_id = ? AND batch_id = ?`
      )
      .bind(newBatchStatus, clientId, batchId);

    const updateSubmissionStmt = this.shardDb
      .prepare(
        `UPDATE submissions
         SET status = ?, reviewed_by_uid = ?, reviewed_at = datetime('now')
         WHERE client_id = ? AND batch_id = ?`
      )
      .bind(newSubmissionStatus, reviewerUid, clientId, batchId);

    const updateRecordsStmt = this.shardDb
      .prepare(
        `UPDATE counting_records
         SET record_status = ?, updated_at = datetime('now')
         WHERE client_id = ? AND batch_id = ?`
      )
      .bind(newRecordStatus, clientId, batchId);

    const logStmt = this.shardDb
      .prepare(
        `INSERT INTO activity_logs (
           log_id, client_id, fiscal_year_id, user_uid, action,
           entity_type, entity_id, details_json, created_at
         ) VALUES (?, ?, ?, ?, ?, 'DAILY_BATCH', ?, ?, datetime('now'))`
      )
      .bind(
        `log_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
        clientId,
        batch.fiscal_year_id || '2026_27',
        reviewerUid,
        decision === 'APPROVE' ? 'DAILY_SHEET_APPROVED' : 'DAILY_SHEET_RETURNED',
        batchId,
        JSON.stringify({ decision, remarks: remarks || null, previousStatus: batch.status })
      );

    await this.shardDb.batch([updateBatchStmt, updateSubmissionStmt, updateRecordsStmt, logStmt]);

    return { success: true, status: newBatchStatus };
  }

  // =========================================================================
  // MASTER WORKBOOK STATE & CONCURRENCY LEASE
  // =========================================================================

  async getMasterWorkbookState(
    clientId: string,
    fiscalYearId: string
  ): Promise<{
    state: any;
    latestJob: any;
  }> {
    const stateId = `${clientId}_${fiscalYearId}`;
    let state = await this.shardDb
      .prepare('SELECT * FROM master_workbook_state WHERE state_id = ?')
      .bind(stateId)
      .first<any>();

    if (!state) {
      state = {
        state_id: stateId,
        client_id: clientId,
        fiscal_year_id: fiscalYearId,
        sync_state: 'NOT_SYNCED',
        last_synced_at: null,
        file_checksum: null,
        master_drive_file_id: null,
        master_drive_file_name: null,
        head_revision_id: null,
        lease_owner_uid: null,
        lease_owner_job_id: null,
        lease_expires_at_ms: 0,
        applied_submissions_json: '{}'
      };
    }

    const latestJob = await this.shardDb
      .prepare(
        'SELECT * FROM master_sync_jobs WHERE client_id = ? AND fiscal_year_id = ? ORDER BY created_at DESC LIMIT 1'
      )
      .bind(clientId, fiscalYearId)
      .first<any>();

    return { state, latestJob: latestJob || null };
  }

  async acquireMasterSyncLease(params: {
    clientId: string;
    fiscalYearId: string;
    userUid: string;
    jobId: string;
    batchId?: string;
    leaseDurationMs?: number;
  }): Promise<{ acquired: boolean; jobId: string; leaseExpiresAtMs: number }> {
    const stateId = `${params.clientId}_${params.fiscalYearId}`;
    const leaseDuration = params.leaseDurationMs || 60000;
    const nowMs = Date.now();
    const leaseExpiresAtMs = nowMs + leaseDuration;

    // Check existing state & lease
    const existing = await this.shardDb
      .prepare('SELECT * FROM master_workbook_state WHERE state_id = ?')
      .bind(stateId)
      .first<any>();

    if (
      existing &&
      existing.lease_owner_job_id &&
      existing.lease_owner_job_id !== params.jobId &&
      existing.lease_expires_at_ms > nowMs
    ) {
      const err: any = new Error(
        `Concurrency Lock Conflict: Another sync job (${existing.lease_owner_job_id}) currently holds the master workbook lease until ${new Date(existing.lease_expires_at_ms).toISOString()}`
      );
      err.statusCode = 409;
      err.code = 'LEASE_LOCK_CONFLICT';
      throw err;
    }

    const upsertStateStmt = this.shardDb
      .prepare(
        `INSERT INTO master_workbook_state (
           state_id, client_id, fiscal_year_id, sync_state,
           lease_owner_uid, lease_owner_job_id, lease_expires_at_ms, updated_at
         ) VALUES (?, ?, ?, 'SYNCING', ?, ?, ?, datetime('now'))
         ON CONFLICT(state_id) DO UPDATE SET
           sync_state = 'SYNCING',
           lease_owner_uid = excluded.lease_owner_uid,
           lease_owner_job_id = excluded.lease_owner_job_id,
           lease_expires_at_ms = excluded.lease_expires_at_ms,
           updated_at = datetime('now')`
      )
      .bind(
        stateId,
        params.clientId,
        params.fiscalYearId,
        params.userUid,
        params.jobId,
        leaseExpiresAtMs
      );

    const insertJobStmt = this.shardDb
      .prepare(
        `INSERT INTO master_sync_jobs (
           job_id, client_id, fiscal_year_id, job_type, status,
           triggered_by_uid, batch_id, created_at
         ) VALUES (?, ?, ?, 'MASTER_WORKBOOK_SYNC', 'IN_PROGRESS', ?, ?, datetime('now'))`
      )
      .bind(
        params.jobId,
        params.clientId,
        params.fiscalYearId,
        params.userUid,
        params.batchId || null
      );

    await this.shardDb.batch([upsertStateStmt, insertJobStmt]);

    return { acquired: true, jobId: params.jobId, leaseExpiresAtMs };
  }

  async releaseMasterSyncLease(params: {
    clientId: string;
    fiscalYearId: string;
    jobId: string;
    status: 'COMPLETED' | 'FAILED';
    errorMessage?: string;
    fileChecksum?: string;
    driveFileId?: string;
    submissionId?: string;
  }): Promise<{ released: boolean; status: string }> {
    const stateId = `${params.clientId}_${params.fiscalYearId}`;
    const newSyncState = params.status === 'COMPLETED' ? 'SYNCED' : 'FAILED';

    const existing = await this.shardDb
      .prepare(
        'SELECT sync_state, lease_owner_uid, lease_owner_job_id, lease_expires_at_ms, applied_submissions_json FROM master_workbook_state WHERE state_id = ?'
      )
      .bind(stateId)
      .first<any>();

    if (!existing) {
      const err: any = new Error(`Master workbook state for '${stateId}' not found.`);
      err.statusCode = 404;
      err.code = 'STATE_NOT_FOUND';
      throw err;
    }

    const existingJob = await this.shardDb
      .prepare('SELECT job_id, status, error_message FROM master_sync_jobs WHERE job_id = ?')
      .bind(params.jobId)
      .first<any>();

    // 1. Concurrency Fencing: If another job currently holds the active lease, reject late release
    if (existing.lease_owner_job_id && existing.lease_owner_job_id !== params.jobId) {
      const err: any = new Error(
        `Lease Ownership Mismatch: Job '${params.jobId}' cannot release master sync lease currently held by job '${existing.lease_owner_job_id}'.`
      );
      err.statusCode = 409;
      err.code = 'LEASE_OWNERSHIP_MISMATCH';
      throw err;
    }

    // 2. If no job currently holds the lease in state
    if (!existing.lease_owner_job_id) {
      if (!existingJob) {
        const err: any = new Error(`Master sync job '${params.jobId}' not found.`);
        err.statusCode = 404;
        err.code = 'JOB_NOT_FOUND';
        throw err;
      }

      // Idempotent retry: this job previously released the lease
      if (existingJob.status === 'COMPLETED' || existingJob.status === 'FAILED') {
        return { released: true, status: existingJob.status };
      }

      // Stale or unassigned job whose lease was expired or cleared
      const err: any = new Error(
        `Lease Ownership Mismatch: Job '${params.jobId}' does not hold the active master sync lease.`
      );
      err.statusCode = 409;
      err.code = 'LEASE_OWNERSHIP_MISMATCH';
      throw err;
    }

    // 3. Idempotent call by the active owner if already marked finished
    if (existingJob && (existingJob.status === 'COMPLETED' || existingJob.status === 'FAILED')) {
      return { released: true, status: existingJob.status };
    }

    let appliedMap: Record<string, boolean> = {};
    if (existing.applied_submissions_json) {
      try {
        appliedMap = JSON.parse(existing.applied_submissions_json);
      } catch {
        appliedMap = {};
      }
    }
    if (params.submissionId && params.status === 'COMPLETED') {
      appliedMap[params.submissionId] = true;
    }

    // 4. Atomic batch update with SQL-level ownership fencing
    const updateStateStmt = this.shardDb
      .prepare(
        `UPDATE master_workbook_state
         SET sync_state = ?,
             lease_owner_uid = NULL,
             lease_owner_job_id = NULL,
             lease_expires_at_ms = 0,
             last_synced_at = CASE WHEN ? = 'COMPLETED' THEN datetime('now') ELSE last_synced_at END,
             file_checksum = COALESCE(?, file_checksum),
             master_drive_file_id = COALESCE(?, master_drive_file_id),
             applied_submissions_json = ?,
             updated_at = datetime('now')
         WHERE state_id = ? AND lease_owner_job_id = ?`
      )
      .bind(
        newSyncState,
        params.status,
        params.fileChecksum || null,
        params.driveFileId || null,
        JSON.stringify(appliedMap),
        stateId,
        params.jobId
      );

    const updateJobStmt = this.shardDb
      .prepare(
        `UPDATE master_sync_jobs
         SET status = ?,
             error_message = ?,
             completed_at = datetime('now')
         WHERE job_id = ?`
      )
      .bind(params.status, params.errorMessage || null, params.jobId);

    const batchResults = await this.shardDb.batch([updateStateStmt, updateJobStmt]);

    if (batchResults && batchResults[0]?.meta?.changes === 0) {
      const err: any = new Error(
        `Lease Ownership Mismatch: Lease for state '${stateId}' is no longer held by job '${params.jobId}'.`
      );
      err.statusCode = 409;
      err.code = 'LEASE_OWNERSHIP_MISMATCH';
      throw err;
    }

    return { released: true, status: params.status };
  }

  async deleteClientShardData(clientId: string): Promise<void> {
    const tables = [
      'counting_records',
      'daily_batches',
      'submissions',
      'asset_photos',
      'exceptions',
      'found_not_in_far',
      'far_assets',
      'fiscal_years',
      'master_workbook_state',
      'master_sync_jobs',
      'export_jobs',
      'master_data',
      'access_requests',
      'column_mapping',
      'input_field_options',
      'input_field_definitions',
      'input_schema_versions',
      'activity_logs'
    ];

    const stmts = tables.map(tbl =>
      this.shardDb.prepare(`DELETE FROM ${tbl} WHERE client_id = ?`).bind(clientId)
    );

    await this.shardDb.batch(stmts);
  }
}

export interface AccessRequestEntity {
  request_id: string;
  client_id: string;
  fiscal_year_id: string;
  requester_uid: string;
  requester_email: string;
  reason?: string;
  status: 'Pending' | 'Approved' | 'Rejected';
  requested_at: string;
  decided_at?: string;
  decided_by_uid?: string;
}

export interface FoundNotInFarEntity {
  temp_id: string;
  client_id: string;
  fiscal_year_id: string;
  description: string;
  raw_values_json: string;
  auditor_uid: string;
  auditor_name: string;
  count_date: string;
  created_at?: string;
}
