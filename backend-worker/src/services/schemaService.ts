// Service for Client-Specific Dynamic Input Schema Management and Header Analysis

import { ShardRepository } from '../repositories/shardRepository';
import {
  DynamicSchemaVersion,
  DynamicFieldDefinition,
  HeaderClassificationResult,
  classifyHeader
} from '../domain/dynamicSchema';

export class SchemaService {
  constructor(private shardRepo: ShardRepository) {}

  async getActiveSchema(clientId: string, fiscalYearId: string): Promise<DynamicSchemaVersion | null> {
    return this.shardRepo.getActiveSchema(clientId, fiscalYearId);
  }

  async getSchemaForCycle(clientId: string, fiscalYearId: string): Promise<DynamicSchemaVersion | null> {
    return this.shardRepo.getSchemaForCycle(clientId, fiscalYearId);
  }

  async publishSchema(schema: DynamicSchemaVersion): Promise<void> {
    schema.status = 'PUBLISHED';
    schema.publishedAt = new Date().toISOString();
    await this.shardRepo.saveSchema(schema);
  }

  analyzeWorkbookColumns(
    headers: string[],
    rows: (string | null | undefined)[][]
  ): HeaderClassificationResult[] {
    const results: HeaderClassificationResult[] = [];

    for (let colIdx = 0; colIdx < headers.length; colIdx++) {
      const headerText = headers[colIdx] || `Column_${colIdx + 1}`;
      const columnLetter = this.columnIndexToLetter(colIdx);
      const colValues = rows.map(r => r[colIdx]);

      results.push(classifyHeader(headerText, colIdx, columnLetter, colValues));
    }

    return results;
  }

  private columnIndexToLetter(index: number): string {
    let temp = index;
    let letter = '';
    while (temp >= 0) {
      letter = String.fromCharCode((temp % 26) + 65) + letter;
      temp = Math.floor(temp / 26) - 1;
    }
    return letter;
  }
}
