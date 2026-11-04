// Domain types and validation for Photo Evidence Metadata

export interface EvidenceMetadata {
  photoId: string;
  clientId: string;
  cycleId: string;
  assetKey: string;
  assetId: string;
  auditRecordId?: string;
  uploaderUid: string;
  uploaderNameSnapshot: string;
  photoCategory: string;
  caption?: string;
  mimeType: string;
  fileExtension: string;
  fileSize: number;
  sha256?: string;
  driveFileId: string;
  driveWebViewLink?: string;
  driveRootFolderId: string;
  driveDateFolderId?: string;
  localFilename?: string;
  capturedAt: string;
  uploadedAt: string;
  status: 'PENDING_UPLOAD' | 'UPLOADED' | 'FAILED';
}

export interface RegisterEvidencePayload {
  photoId: string;
  cycleId: string;
  assetKey: string;
  assetId: string;
  auditRecordId?: string;
  photoCategory?: string;
  caption?: string;
  mimeType?: string;
  fileExtension?: string;
  fileSize?: number;
  sha256?: string;
  sha256Checksum?: string;
  driveFileId: string;
  driveWebViewLink?: string;
  driveRootFolderId: string;
  driveDateFolderId?: string;
  localFilename?: string;
  capturedAt?: string;
  uploadedAt?: string;
}

export const ALLOWED_IMAGE_MIME_TYPES = [
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/heic',
  'image/heif'
];

export const ALLOWED_PHOTO_CATEGORIES = [
  'GENERAL',
  'ASSET_TAG',
  'NAMEPLATE',
  'CONDITION_DEFECT',
  'OVERALL_LOCATION',
  'SERIAL_NUMBER'
];

export function validateEvidencePayload(payload: any): { isValid: boolean; errors: string[] } {
  const errors: string[] = [];

  if (!payload || typeof payload !== 'object') {
    return { isValid: false, errors: ['Payload must be a non-null JSON object'] };
  }

  // Required: photoId (UUID/non-empty string)
  if (!payload.photoId || typeof payload.photoId !== 'string' || payload.photoId.trim().length === 0) {
    errors.push('photoId is required and must be a non-empty string');
  } else if (payload.photoId.length > 128) {
    errors.push('photoId must not exceed 128 characters');
  }

  // Required: assetKey
  if (!payload.assetKey || typeof payload.assetKey !== 'string' || payload.assetKey.trim().length === 0) {
    errors.push('assetKey is required and must be a non-empty string');
  }

  // Required: assetId (strictly preserve leading zeroes)
  if (!payload.assetId || typeof payload.assetId !== 'string' || payload.assetId.trim().length === 0) {
    errors.push('assetId is required and must be a non-empty string preserving leading zeroes');
  } else if (payload.assetId.length > 128) {
    errors.push('assetId must not exceed 128 characters');
  }

  // Required: driveFileId
  if (!payload.driveFileId || typeof payload.driveFileId !== 'string' || payload.driveFileId.trim().length === 0) {
    errors.push('driveFileId is required and must be a non-empty string');
  } else if (payload.driveFileId.length > 256) {
    errors.push('driveFileId must not exceed 256 characters');
  }

  // Required: driveRootFolderId
  if (!payload.driveRootFolderId || typeof payload.driveRootFolderId !== 'string' || payload.driveRootFolderId.trim().length === 0) {
    errors.push('driveRootFolderId is required and must be a non-empty string');
  }

  // Required: sha256 or sha256Checksum
  const sha = payload.sha256 || payload.sha256Checksum;
  if (!sha || typeof sha !== 'string' || sha.trim().length === 0) {
    errors.push('sha256 (or sha256Checksum) is required and must be a 64-character hex string');
  } else if (!/^[a-fA-F0-9]{64}$/.test(sha.trim())) {
    errors.push('sha256 must be a 64-character hexadecimal string');
  }

  // Optional MIME type validation
  if (payload.mimeType && typeof payload.mimeType === 'string') {
    const cleanMime = payload.mimeType.trim().toLowerCase();
    if (!ALLOWED_IMAGE_MIME_TYPES.includes(cleanMime)) {
      errors.push(`Invalid image mimeType '${payload.mimeType}'. Allowed: ${ALLOWED_IMAGE_MIME_TYPES.join(', ')}`);
    }
  }

  // Optional category validation
  if (payload.photoCategory && typeof payload.photoCategory === 'string') {
    const cleanCat = payload.photoCategory.trim().toUpperCase();
    if (!ALLOWED_PHOTO_CATEGORIES.includes(cleanCat)) {
      errors.push(`Invalid photoCategory '${payload.photoCategory}'. Allowed: ${ALLOWED_PHOTO_CATEGORIES.join(', ')}`);
    }
  }

  // Optional file size check (must be non-negative)
  if (payload.fileSize !== undefined && (typeof payload.fileSize !== 'number' || payload.fileSize < 0)) {
    errors.push('fileSize must be a non-negative number');
  }

  // Max caption length
  if (payload.caption && typeof payload.caption === 'string' && payload.caption.length > 1000) {
    errors.push('caption must not exceed 1000 characters');
  }

  return {
    isValid: errors.length === 0,
    errors
  };
}
