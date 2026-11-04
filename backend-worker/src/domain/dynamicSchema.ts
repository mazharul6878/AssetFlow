// Domain Models and Validation for Client-Specific Dynamic Input Schemas

export type InputMode =
  | 'FIXED_IMPORTED'
  | 'MANUAL'
  | 'AUTO'
  | 'DROPDOWN'
  | 'HIDDEN'
  | 'INACTIVE';

export type DataType =
  | 'TEXT'
  | 'MULTILINE_TEXT'
  | 'INTEGER'
  | 'DECIMAL'
  | 'DATE'
  | 'TIME'
  | 'BOOLEAN'
  | 'SINGLE_SELECT'
  | 'MULTI_SELECT'
  | 'PHOTO_EVIDENCE';

export type SourceType =
  | 'EXCEL_POPULATED'
  | 'EXCEL_EMPTY'
  | 'CUSTOM'
  | 'SYSTEM';

export type SchemaStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';

export interface DynamicFieldOption {
  optionId: string;
  fieldId: string;
  schemaVersionId: string;
  clientId: string;
  optionLabel: string;
  optionValue: string;
  sortOrder: number;
  isActive: boolean;
}

export interface DynamicFieldDefinition {
  fieldId: string;
  schemaVersionId: string;
  clientId: string;
  fieldKey: string;
  displayLabel: string;
  originalHeader: string | null;
  sourceType: SourceType;
  sourceWorksheetIndex?: number;
  sourceColumnIndex?: number;
  sourceColumnLetter?: string;
  inputMode: InputMode;
  dataType: DataType;
  isRequired: boolean;
  isVisible: boolean;
  sortOrder: number;
  validationRulesJson?: string;
  autoSourceRule?: string;
  promotedFromFar: boolean;
  destinationExcelHeader?: string;
  semanticRole?: string;
  allowOtherOption: boolean;
  isArchived: boolean;
  options?: DynamicFieldOption[];
}

export interface DynamicSchemaVersion {
  schemaVersionId: string;
  clientId: string;
  fiscalYearId: string;
  versionNumber: number;
  status: SchemaStatus;
  sourceWorkbookName: string;
  sourceWorkbookDriveFileId?: string;
  sourceWorksheetName: string;
  sourceWorksheetIndex: number;
  headerRowIndex: number;
  uniqueAssetKeyHeader: string;
  changeSummary?: string;
  createdByUid: string;
  createdAt: string;
  publishedByUid?: string;
  publishedAt?: string;
  previousVersionId?: string;
  fields?: DynamicFieldDefinition[];
}

export interface HeaderClassificationResult {
  headerText: string;
  columnIndex: number;
  columnLetter: string;
  nonBlankCount: number;
  totalRows: number;
  sampleValues: string[];
  inferredDataType: DataType;
  classification: 'FIXED_IMPORTED' | 'EMPTY_HEADER_PENDING_CONFIGURATION';
  isSparse: boolean;
}

export function classifyHeader(
  headerText: string,
  columnIndex: number,
  columnLetter: string,
  values: (string | null | undefined)[]
): HeaderClassificationResult {
  const totalRows = values.length;
  const nonBlankValues = values.filter((v): v is string => v !== null && v !== undefined && v.trim() !== '');
  const nonBlankCount = nonBlankValues.length;

  let inferredDataType: DataType = 'TEXT';
  if (nonBlankCount > 0) {
    const isAllInt = nonBlankValues.every(v => /^-?\d+$/.test(v.trim()));
    const isAllDec = nonBlankValues.every(v => /^-?\d+(\.\d+)?$/.test(v.trim()));
    const isAllDate = nonBlankValues.every(v => /^\d{4}-\d{2}-\d{2}/.test(v.trim()));

    if (isAllInt) inferredDataType = 'INTEGER';
    else if (isAllDec) inferredDataType = 'DECIMAL';
    else if (isAllDate) inferredDataType = 'DATE';
  }

  const isCompletelyEmpty = nonBlankCount === 0 && totalRows > 0;
  const isSparse = nonBlankCount > 0 && nonBlankCount < totalRows;

  return {
    headerText,
    columnIndex,
    columnLetter,
    nonBlankCount,
    totalRows,
    sampleValues: nonBlankValues.slice(0, 5),
    inferredDataType,
    classification: isCompletelyEmpty ? 'EMPTY_HEADER_PENDING_CONFIGURATION' : 'FIXED_IMPORTED',
    isSparse
  };
}
