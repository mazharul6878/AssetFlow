import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import { createLocalTestD1, MockD1Database } from './test-helper';

describe('Control Database Schema and Integrity (CONTROL_DB)', () => {
  let controlDb: MockD1Database;
  const migrationPath = path.resolve(__dirname, '../migrations/control/0001_initial_control.sql');

  beforeEach(() => {
    controlDb = createLocalTestD1(migrationPath);
  });

  it('successfully creates all control tables and seeds initial metadata', async () => {
    // Check tables exist
    const tables = controlDb.getNativeDb().prepare(
      "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    ).all() as { name: string }[];

    const tableNames = tables.map(t => t.name);
    expect(tableNames).toContain('users');
    expect(tableNames).toContain('clients');
    expect(tableNames).toContain('client_memberships');
    expect(tableNames).toContain('client_shard_routing');
    expect(tableNames).toContain('shard_metadata');
    expect(tableNames).toContain('system_config');
    expect(tableNames).toContain('deleted_identities');
    expect(tableNames).toContain('deletion_audit_events');
    expect(tableNames).toContain('project_settings');

    // Check system_config seed
    const ownerEmail = await controlDb
      .prepare("SELECT config_value FROM system_config WHERE config_key = 'designated_system_owner_email'")
      .first<{ config_value: string }>();
    expect(ownerEmail?.config_value).toBe('admin@assetflow.local');

    // Check shard_metadata seed
    const shardMeta = await controlDb
      .prepare("SELECT * FROM shard_metadata WHERE shard_binding = 'SHARD_01'")
      .first<{ shard_binding: string; status: string }>();
    expect(shardMeta?.shard_binding).toBe('SHARD_01');
    expect(shardMeta?.status).toBe('ACTIVE');
  });

  it('enforces foreign key constraints on client creation', async () => {
    // Inserting a client with non-existent user UID should fail due to foreign key
    expect(() => {
      controlDb.getNativeDb().prepare(
        `INSERT INTO clients (client_id, client_name, client_code, industry, created_by_uid)
         VALUES ('cl_test', 'Test Corp', 'TC01', 'Manufacturing', 'non_existent_uid')`
      ).run();
    }).toThrow(/FOREIGN KEY/i);
  });

  it('enforces UNIQUE(client_id, user_uid) in client_memberships', async () => {
    // 1. Create User
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role)
       VALUES ('uid_user1', 'user1@test.com', 'User One', 'User One', 'user1', 'Auditor')`
    ).run();

    // 2. Create Client
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, created_by_uid)
       VALUES ('cl_01', 'Client 01', 'C01', 'Retail', 'uid_user1')`
    ).run();

    // 3. Insert Initial Membership
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, assigned_by_uid)
       VALUES ('mem_01', 'cl_01', 'uid_user1', 'Auditor', 'uid_user1')`
    ).run();

    // 4. Duplicate Membership Insert Must Throw UNIQUE constraint failure
    expect(() => {
      controlDb.getNativeDb().prepare(
        `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, assigned_by_uid)
         VALUES ('mem_02', 'cl_01', 'uid_user1', 'Admin', 'uid_user1')`
      ).run();
    }).toThrow(/UNIQUE constraint failed/i);
  });

  it('verifies client shard routing lookup and status constraints', async () => {
    // Create user and client
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role)
       VALUES ('uid_admin', 'admin@test.com', 'Admin', 'Admin User', 'admin', 'Admin')`
    ).run();

    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, created_by_uid)
       VALUES ('cl_walton', 'Walton Corp', 'WLT', 'Electronics', 'uid_admin')`
    ).run();

    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES ('cl_walton', 'SHARD_01', 'shard-db-uuid-01', 'ACTIVE')`
    ).run();

    const route = await controlDb
      .prepare('SELECT * FROM client_shard_routing WHERE client_id = ?')
      .bind('cl_walton')
      .first<{ shard_binding: string; routing_status: string }>();

    expect(route?.shard_binding).toBe('SHARD_01');
    expect(route?.routing_status).toBe('ACTIVE');

    // Check invalid routing_status constraint
    expect(() => {
      controlDb.getNativeDb().prepare(
        `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
         VALUES ('cl_other', 'SHARD_01', 'uuid', 'INVALID_STATUS')`
      ).run();
    }).toThrow(/CHECK constraint failed/i);
  });
});
