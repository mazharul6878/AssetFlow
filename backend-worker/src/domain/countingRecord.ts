// Domain Models and Validation for Dynamic Counting Records

import { DynamicFieldDefinition } from './dynamicSchema';

export type RecordStatus = 'DRAFT' | 'SUBMITTED' | 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';

export interface CountingRecord {
  recordId: string;
  clientId: string;
  fiscalYearId: string;
  assetKey: string;
  schemaVersionId: string;
  auditorUid: string;
  auditorNameSnapshot: string;
  recordStatus: RecordStatus;
  batchId?: string;
  countDate: string;
  valuesJson: string; // JSON string containing dynamic field values keyed by immutable field_id
  idempotencyKey?: string;
  recordVersion: number;
  createdAt: string;
  updatedAt: string;
}

export interface CountingSubmissionPayload {
  assetKey: string;
  schemaVersionId: string;
  countDate: string;
  batchId?: string;
  idempotencyKey?: string;
  values: Record<string, unknown>; // Keyed by fieldId (e.g. "fld_cond_01": "Good")
}

export interface ValidationIssue {
  fieldId: string;
  message: string;
}

export function validateCountingPayload(
  payload: CountingSubmissionPayload,
  fields: DynamicFieldDefinition[]
): { isValid: boolean; errors: ValidationIssue[] } {
  const errors: ValidationIssue[] = [];
  const fieldMap = new Map(fields.map(f => [f.fieldId, f]));

  // 1. Verify unknown field IDs
  for (const fieldId of Object.keys(payload.values)) {
    const definition = fieldMap.get(fieldId);
    if (!definition) {
      errors.push({
        fieldId,
        message: `Field ID '${fieldId}' is not defined in active schema version '${payload.schemaVersionId}'`
      });
      continue;
    }

    if (definition.isArchived) {
      errors.push({
        fieldId,
        message: `Field '${definition.displayLabel}' (${fieldId}) is archived and cannot receive inputs`
      });
      continue;
    }

    if (definition.inputMode === 'FIXED_IMPORTED' || definition.inputMode === 'AUTO') {
      errors.push({
        fieldId,
        message: `Field '${definition.displayLabel}' (${fieldId}) is ${definition.inputMode} and cannot be written directly by client`
      });
      continue;
    }

    const val = payload.values[fieldId];
    if (val === null || val === undefined || val === '') {
      continue;
    }

    // Type validation
    switch (definition.dataType) {
      case 'INTEGER': {
        const num = Number(val);
        if (!Number.isInteger(num)) {
          errors.push({ fieldId, message: `Value for '${definition.displayLabel}' must be an integer` });
        }
        break;
      }
      case 'DECIMAL': {
        const num = Number(val);
        if (Number.isNaN(num)) {
          errors.push({ fieldId, message: `Value for '${definition.displayLabel}' must be a decimal number` });
        }
        break;
      }
      case 'DATE': {
        if (typeof val !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(val)) {
          errors.push({ fieldId, message: `Value for '${definition.displayLabel}' must be in YYYY-MM-DD format` });
        }
        break;
      }
      case 'SINGLE_SELECT': {
        const strVal = String(val);
        const validOptions = (definition.options || []).filter(o => o.isActive).map(o => o.optionValue);
        const matchesOption = validOptions.includes(strVal);
        const isAllowedOther = definition.allowOtherOption && strVal.startsWith('Other:');

        if (!matchesOption && !isAllowedOther) {
          errors.push({
            fieldId,
            message: `Value '${strVal}' is not a valid option for dropdown field '${definition.displayLabel}'`
          });
        }
        break;
      }
      case 'MULTI_SELECT': {
        if (!Array.isArray(val)) {
          errors.push({ fieldId, message: `Value for '${definition.displayLabel}' must be a JSON array` });
        } else {
          const validOptions = (definition.options || []).filter(o => o.isActive).map(o => o.optionValue);
          for (const item of val) {
            const strItem = String(item);
            const matches = validOptions.includes(strItem) || (definition.allowOtherOption && strItem.startsWith('Other:'));
            if (!matches) {
              errors.push({
                fieldId,
                message: `Option '${strItem}' is invalid for multi-select field '${definition.displayLabel}'`
              });
            }
          }
        }
        break;
      }
    }
  }

  // 2. Validate Required Fields
  for (const definition of fields) {
    if (
      definition.isRequired &&
      definition.isVisible &&
      !definition.isArchived &&
      (definition.inputMode === 'MANUAL' || definition.inputMode === 'DROPDOWN')
    ) {
      const val = payload.values[definition.fieldId];
      if (val === undefined || val === null || val === '' || (Array.isArray(val) && val.length === 0)) {
        errors.push({
          fieldId: definition.fieldId,
          message: `Required field '${definition.displayLabel}' (${definition.fieldId}) is missing or empty`
        });
      }
    }
  }

  return {
    isValid: errors.length === 0,
    errors
  };
}
