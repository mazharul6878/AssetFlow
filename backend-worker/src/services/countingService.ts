// Service for Physical Counting Submission, Validation, and Idempotency Handling

import { ShardRepository } from '../repositories/shardRepository';
import { CountingSubmissionPayload, validateCountingPayload, CountingRecord } from '../domain/countingRecord';
import { AuthenticatedUser, ClientMembershipContext } from '../domain/permissions';

export class CountingService {
  constructor(private shardRepo: ShardRepository) {}

  async submitCount(
    clientId: string,
    fiscalYearId: string,
    payload: CountingSubmissionPayload,
    user: AuthenticatedUser,
    membership: ClientMembershipContext
  ): Promise<{ recordId: string; status: string; isIdempotentReplay: boolean }> {
    // 1. Fetch Schema pinned to Audit Cycle
    const activeSchema = await this.shardRepo.getSchemaForCycle(clientId, fiscalYearId);
    if (!activeSchema) {
      throw new Error(`Client '${clientId}' has no published dynamic input schema for fiscal year '${fiscalYearId}'`);
    }

    if (activeSchema.schemaVersionId !== payload.schemaVersionId) {
      throw new Error(
        `Payload schema version '${payload.schemaVersionId}' does not match active cycle schema '${activeSchema.schemaVersionId}'`
      );
    }

    // 2. Validate Payload against Dynamic Field Definitions
    const validation = validateCountingPayload(payload, activeSchema.fields || []);
    if (!validation.isValid) {
      const errorMsg = validation.errors.map(e => `${e.fieldId}: ${e.message}`).join('; ');
      throw new Error(`Dynamic counting validation failed: ${errorMsg}`);
    }

    // 3. Check Idempotency Key & Multi-Auditor Concurrency Protection
    const existing = await this.shardRepo.getCountingRecord(clientId, payload.assetKey);
    if (existing) {
      // Safe Idempotent Replay by same or original request
      if (payload.idempotencyKey && existing.idempotencyKey === payload.idempotencyKey) {
        return {
          recordId: existing.recordId,
          status: existing.recordStatus,
          isIdempotentReplay: true
        };
      }

      // Multi-Auditor Collision Prevention: Do not silently overwrite another auditor's work
      if (existing.auditorUid !== user.uid) {
        throw new Error(
          `Conflict: Asset '${payload.assetKey}' has already been verified by auditor '${existing.auditorNameSnapshot}'. Concurrent overwrites across auditors are prohibited.`
        );
      }
    }

    const recordId = existing ? existing.recordId : `rec_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`;

    // 4. Construct Immutable Counting Record
    const record: CountingRecord = {
      recordId,
      clientId,
      fiscalYearId,
      assetKey: payload.assetKey,
      schemaVersionId: payload.schemaVersionId,
      auditorUid: user.uid,
      auditorNameSnapshot: user.displayName,
      recordStatus: 'SUBMITTED',
      batchId: payload.batchId,
      countDate: payload.countDate,
      valuesJson: JSON.stringify(payload.values),
      idempotencyKey: payload.idempotencyKey,
      recordVersion: existing ? existing.recordVersion + 1 : 1,
      createdAt: existing ? existing.createdAt : new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    // 5. Persist to Target Shard Database
    await this.shardRepo.upsertCountingRecord(record);

    return {
      recordId,
      status: 'SUBMITTED',
      isIdempotentReplay: false
    };
  }
}
