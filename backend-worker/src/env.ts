// Environment Interface for Cloudflare Worker and D1 Database Bindings

export interface Env {
  // D1 Database Bindings
  CONTROL_DB: D1Database;
  SHARD_01: D1Database;
  SHARD_02?: D1Database;
  SHARD_03?: D1Database;
  SHARD_04?: D1Database;
  SHARD_05?: D1Database;
  SHARD_06?: D1Database;
  SHARD_07?: D1Database;
  SHARD_08?: D1Database;
  SHARD_09?: D1Database;

  // Environment Variables & Configuration
  ENVIRONMENT: string;
  FIREBASE_PROJECT_ID: string;
  SHARD_ALLOWLIST: string;
  INITIAL_OWNER_UID?: string;

  // Optional mock flag for isolated unit tests
  JWT_VERIFICATION_MOCK?: string;
  JWT_MOCK_PUBLIC_KEY?: string;
}

export const VALID_SHARD_BINDINGS = [
  'SHARD_01',
  'SHARD_02',
  'SHARD_03',
  'SHARD_04',
  'SHARD_05',
  'SHARD_06',
  'SHARD_07',
  'SHARD_08',
  'SHARD_09'
] as const;

export type ShardBindingName = (typeof VALID_SHARD_BINDINGS)[number];
