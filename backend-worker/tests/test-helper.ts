// Local D1Database Emulator backed by Node.js built-in 'node:sqlite'

import { createRequire } from 'node:module';
import * as fs from 'node:fs';
import * as path from 'node:path';

const require = createRequire(import.meta.url);
const { DatabaseSync } = require('node:sqlite');

export class MockD1PreparedStatement {
  private boundValues: unknown[] = [];

  constructor(private db: any, private sql: string) {}

  bind(...values: unknown[]): MockD1PreparedStatement {
    this.boundValues = values;
    return this;
  }

  async first<T = unknown>(colName?: string): Promise<T | null> {
    const stmt = this.db.prepare(this.sql);
    const row = stmt.get(...(this.boundValues as any[])) as any;
    if (!row) return null;
    if (colName) return (row[colName] as T) ?? null;
    return row as T;
  }

  async all<T = unknown>(): Promise<{ results: T[]; success: boolean; meta: any }> {
    const stmt = this.db.prepare(this.sql);
    const rows = stmt.all(...(this.boundValues as any[])) as T[];
    return {
      results: rows,
      success: true,
      meta: { changes: 0, duration: 1, rows_read: rows.length, rows_written: 0, last_row_id: 0 }
    };
  }

  async run<T = unknown>(): Promise<{ results: T[]; success: boolean; meta: any }> {
    const stmt = this.db.prepare(this.sql);
    const info = stmt.run(...(this.boundValues as any[]));
    return {
      results: [],
      success: true,
      meta: {
        changes: info.changes,
        duration: 1,
        rows_read: 0,
        rows_written: info.changes,
        last_row_id: Number(info.lastInsertRowid)
      }
    };
  }
}

export class MockD1Database {
  private db: any;

  constructor(filePath: string = ':memory:') {
    this.db = new DatabaseSync(filePath);
    this.db.exec('PRAGMA foreign_keys = ON;');
  }

  getNativeDb(): any {
    return this.db;
  }

  prepare(query: string): MockD1PreparedStatement {
    return new MockD1PreparedStatement(this.db, query);
  }

  async exec(query: string): Promise<{ count: number; duration: number }> {
    this.db.exec(query);
    return { count: 1, duration: 1 };
  }

  async batch<T = unknown>(statements: MockD1PreparedStatement[]): Promise<any[]> {
    const results = [];
    this.db.exec('BEGIN TRANSACTION;');
    try {
      for (const stmt of statements) {
        results.push(await stmt.run());
      }
      this.db.exec('COMMIT;');
      return results;
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }
  }

  async dump(): Promise<ArrayBuffer> {
    return new ArrayBuffer(0);
  }
}

export function createLocalTestD1(sqlFilePath?: string, dbPath: string = ':memory:'): MockD1Database {
  const d1 = new MockD1Database(dbPath);
  if (sqlFilePath && fs.existsSync(sqlFilePath)) {
    const sql = fs.readFileSync(sqlFilePath, 'utf8');
    d1.getNativeDb().exec(sql);
  }
  return d1;
}

export function createAllMigrationsControlD1(): MockD1Database {
  const d1 = new MockD1Database();
  const dir = path.resolve(__dirname, '../migrations/control');
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.sql')).sort();
  for (const f of files) {
    const sql = fs.readFileSync(path.join(dir, f), 'utf8');
    d1.getNativeDb().exec(sql);
  }
  return d1;
}

export function createAllMigrationsShardD1(): MockD1Database {
  const d1 = new MockD1Database();
  const dir = path.resolve(__dirname, '../migrations/shard');
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.sql')).sort();
  for (const f of files) {
    const sql = fs.readFileSync(path.join(dir, f), 'utf8');
    d1.getNativeDb().exec(sql);
  }
  return d1;
}

export function createMockJWT(
  payload: {
    sub?: string;
    email?: string;
    name?: string;
    exp?: number;
    iat?: number;
    auth_time?: number;
    aud?: string;
    iss?: string;
    [key: string]: unknown;
  },
  headerOverrides?: {
    alg?: string;
    kid?: string;
    typ?: string;
    [key: string]: unknown;
  }
): string {
  const headerObj = { alg: 'RS256', typ: 'JWT', kid: 'mock-key-1', ...headerOverrides };
  const header = btoa(JSON.stringify(headerObj));
  const now = Math.floor(Date.now() / 1000);
  const exp = payload.exp !== undefined ? payload.exp : now + 3600;
  const iat = payload.iat !== undefined ? payload.iat : now;
  const auth_time = payload.auth_time !== undefined ? payload.auth_time : now;
  const bodyObj = {
    iss: 'https://securetoken.google.com/spherical-booth-366013',
    aud: 'spherical-booth-366013',
    ...payload,
    exp,
    iat,
    auth_time
  };
  const body = btoa(JSON.stringify(bodyObj));
  const sig = btoa('mock-sig');
  return `${header}.${body}.${sig}`;
}
