// Service for Evidence Photo Registration and Multi-Client Isolation

import { ShardRepository } from '../repositories/shardRepository';
import { ControlRepository } from '../repositories/controlRepository';
import { EvidenceMetadata, RegisterEvidencePayload, validateEvidencePayload } from '../domain/evidence';
import { AuthenticatedUser, ClientMembershipContext } from '../domain/permissions';

export class EvidenceConflictError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'EvidenceConflictError';
  }
}

export class EvidenceValidationError extends Error {
  public errors: string[];
  constructor(errors: string[]) {
    super(errors.join(', '));
    this.name = 'EvidenceValidationError';
    this.errors = errors;
  }
}

export class EvidenceService {
  constructor(
    private shardRepo: ShardRepository,
    private controlRepo?: ControlRepository
  ) {}

  async registerEvidence(
    clientId: string,
    cycleId: string,
    payload: RegisterEvidencePayload,
    user: AuthenticatedUser,
    membership: ClientMembershipContext
  ): Promise<{ message: string; photo: EvidenceMetadata; isIdempotentReplay: boolean }> {
    // 1. Structural & Field Validation
    const validation = validateEvidencePayload(payload);
    if (!validation.isValid) {
      throw new EvidenceValidationError(validation.errors);
    }

    // 2. Client & Cycle Scoping Validation
    if (payload.cycleId && payload.cycleId !== cycleId) {
      throw new EvidenceValidationError([`Cycle ID in payload ('${payload.cycleId}') does not match route cycle ('${cycleId}')`]);
    }

    const cycleExists = await this.shardRepo.checkFiscalYearExists(clientId, cycleId);
    if (!cycleExists) {
      throw new EvidenceValidationError([`Audit cycle '${cycleId}' does not belong to client '${clientId}'`]);
    }

    // 3. Asset Existence & Association Validation
    const farAsset = await this.shardRepo.getFarAsset(clientId, cycleId, payload.assetKey);
    if (!farAsset) {
      throw new EvidenceValidationError([`Asset with key '${payload.assetKey}' does not exist in cycle '${cycleId}' for client '${clientId}'`]);
    }

    // 4. Drive Root Folder Alignment (if Client has configured drive_root_folder_id in Control DB)
    if (this.controlRepo) {
      const client = await this.controlRepo.getClient(clientId);
      if (client && client.drive_root_folder_id && client.drive_root_folder_id.trim().length > 0) {
        if (payload.driveRootFolderId !== client.drive_root_folder_id) {
          throw new EvidenceValidationError([
            `Target Drive root folder ('${payload.driveRootFolderId}') does not match client's designated Drive root folder ('${client.drive_root_folder_id}')`
          ]);
        }
      }
    }

    // 5. Idempotency & Conflicting photoId Reuse Detection (Shard Check)
    const preExisting = await this.shardRepo.getPhotoByGlobalId(payload.photoId);
    if (preExisting) {
      const providedSha = (payload.sha256 || payload.sha256Checksum)?.trim();
      if (preExisting.clientId !== clientId) {
        throw new EvidenceConflictError(
          `Conflicting photoId reuse: '${payload.photoId}' was previously registered for a different client ('${preExisting.clientId}').`
        );
      }
      if (preExisting.cycleId !== cycleId) {
        throw new EvidenceConflictError(
          `Conflicting photoId reuse: '${payload.photoId}' was previously registered for a different cycle ('${preExisting.cycleId}').`
        );
      }
      if (preExisting.assetKey !== payload.assetKey) {
        throw new EvidenceConflictError(
          `Conflicting photoId reuse: '${payload.photoId}' was previously registered for a different asset ('${preExisting.assetKey}').`
        );
      }
      if (providedSha && preExisting.sha256 && providedSha !== preExisting.sha256) {
        throw new EvidenceConflictError(
          `Conflicting photoId reuse: '${payload.photoId}' checksum does not match previously registered evidence.`
        );
      }
      if (preExisting.driveFileId && payload.driveFileId && preExisting.driveFileId !== payload.driveFileId.trim()) {
        throw new EvidenceConflictError(
          `Conflicting photoId reuse: '${payload.photoId}' was previously registered with a different Drive file ID ('${preExisting.driveFileId}').`
        );
      }

      // Idempotent replay: return existing record
      return {
        message: 'Evidence metadata already registered',
        photo: preExisting,
        isIdempotentReplay: true
      };
    }

    // 6. Build Immutable Attributed Evidence Metadata Record
    const nowIso = new Date().toISOString();
    const photo: EvidenceMetadata = {
      photoId: payload.photoId.trim(),
      clientId,
      cycleId,
      assetKey: payload.assetKey.trim(),
      assetId: payload.assetId.trim(), // Strictly preserves leading zeroes
      auditRecordId: payload.auditRecordId?.trim(),
      uploaderUid: user.uid,
      uploaderNameSnapshot: user.displayName || user.email || 'Auditor',
      photoCategory: (payload.photoCategory || 'GENERAL').trim().toUpperCase(),
      caption: payload.caption?.trim(),
      mimeType: (payload.mimeType || 'image/jpeg').trim().toLowerCase(),
      fileExtension: (payload.fileExtension || 'jpg').trim().toLowerCase().replace('.', ''),
      fileSize: payload.fileSize || 0,
      sha256: (payload.sha256 || payload.sha256Checksum)?.trim(),
      driveFileId: payload.driveFileId.trim(),
      driveWebViewLink: payload.driveWebViewLink?.trim(),
      driveRootFolderId: payload.driveRootFolderId.trim(),
      driveDateFolderId: payload.driveDateFolderId?.trim(),
      localFilename: payload.localFilename?.trim(),
      capturedAt: payload.capturedAt?.trim() || nowIso,
      uploadedAt: payload.uploadedAt?.trim() || nowIso,
      status: 'UPLOADED'
    };

    // 7. Persist to Shard D1 with ON CONFLICT(photo_id) DO NOTHING
    await this.shardRepo.upsertPhotoMetadata(photo);

    // 8. Retrieve authoritative row to verify concurrent race safety
    const stored = await this.shardRepo.getPhotoByGlobalId(photo.photoId);
    if (!stored) {
      throw new Error(`Failed to retrieve persisted photo evidence record for '${photo.photoId}'`);
    }

    // 9. Compare immutable identity fields
    if (stored.clientId !== photo.clientId) {
      throw new EvidenceConflictError(
        `Conflicting photoId reuse: '${photo.photoId}' was previously registered for a different client ('${stored.clientId}').`
      );
    }
    if (stored.cycleId !== photo.cycleId) {
      throw new EvidenceConflictError(
        `Conflicting photoId reuse: '${photo.photoId}' was previously registered for a different cycle ('${stored.cycleId}').`
      );
    }
    if (stored.assetKey !== photo.assetKey) {
      throw new EvidenceConflictError(
        `Conflicting photoId reuse: '${photo.photoId}' was previously registered for a different asset ('${stored.assetKey}').`
      );
    }
    if (stored.sha256 && photo.sha256 && stored.sha256 !== photo.sha256) {
      throw new EvidenceConflictError(
        `Conflicting photoId reuse: '${photo.photoId}' checksum does not match previously registered evidence.`
      );
    }
    if (stored.driveFileId !== photo.driveFileId) {
      throw new EvidenceConflictError(
        `Conflicting photoId reuse: '${photo.photoId}' was previously registered with a different Drive file ID ('${stored.driveFileId}').`
      );
    }

    const isReplay = stored.uploadedAt !== photo.uploadedAt;
    return {
      message: isReplay ? 'Evidence metadata already registered' : 'Evidence metadata registered successfully',
      photo: stored,
      isIdempotentReplay: isReplay
    };
  }

  async getEvidence(clientId: string, photoId: string): Promise<EvidenceMetadata | null> {
    return this.shardRepo.getPhotoById(clientId, photoId);
  }

  async listEvidenceForAsset(clientId: string, cycleId: string, assetKey: string): Promise<EvidenceMetadata[]> {
    return this.shardRepo.listPhotosForAsset(clientId, cycleId, assetKey);
  }

  async listEvidenceForCycle(clientId: string, cycleId: string, limit: number = 100): Promise<EvidenceMetadata[]> {
    return this.shardRepo.listPhotosForCycle(clientId, cycleId, limit);
  }
}
