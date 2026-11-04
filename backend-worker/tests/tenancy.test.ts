import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import { createLocalTestD1, MockD1Database } from './test-helper';
import { resolveClientShard, ShardRoutingError } from '../src/services/shardRouter';
import { authorizeClientAccess, AuthorizationError } from '../src/middleware/authorization';
import { AuthenticatedUser } from '../src/domain/permissions';

describe('Multi-Tenancy Isolation and Shard Routing', () => {
  let controlDb: MockD1Database;
  let shard01Db: MockD1Database;

  const controlMigration = path.resolve(__dirname, '../migrations/control/0001_initial_control.sql');
  const shardMigration = path.resolve(__dirname, '../migrations/shard/0001_initial_shard.sql');

  beforeEach(() => {
    controlDb = createLocalTestD1(controlMigration);
    shard01Db = createLocalTestD1(shardMigration);

    // Seed test users
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES 
         ('uid_auditor_a', 'auditor.a@test.com', 'Auditor A', 'Auditor A', 'aud_a', 'Auditor', 'Active'),
         ('uid_auditor_b', 'auditor.b@test.com', 'Auditor B', 'Auditor B', 'aud_b', 'Auditor', 'Active'),
         ('uid_suspended', 'suspended@test.com', 'Suspended', 'Suspended', 'susp', 'Auditor', 'Suspended')`
    ).run();

    // Seed test clients
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, created_by_uid, status)
       VALUES 
         ('client_alpha', 'Alpha Corp', 'ALP', 'Retail', 'uid_auditor_a', 'Active'),
         ('client_beta', 'Beta Logistics', 'BET', 'Logistics', 'uid_auditor_a', 'Active'),
         ('client_deleting', 'Deleting Inc', 'DEL', 'Finance', 'uid_auditor_a', 'Deleting')`
    ).run();

    // Seed client memberships: Auditor A in Alpha; Auditor B in Beta
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, assigned_by_uid)
       VALUES 
         ('mem_01', 'client_alpha', 'uid_auditor_a', 'Auditor', 'Active', 'uid_auditor_a'),
         ('mem_02', 'client_beta', 'uid_auditor_b', 'Auditor', 'Active', 'uid_auditor_a')`
    ).run();

    // Seed shard routing
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES 
         ('client_alpha', 'SHARD_01', 'shard-db-01', 'ACTIVE'),
         ('client_beta', 'SHARD_01', 'shard-db-01', 'ACTIVE'),
         ('client_deleting', 'SHARD_01', 'shard-db-01', 'DELETING')`
    ).run();
  });

  it('successfully resolves shard binding from trusted Control DB records', async () => {
    const env: any = {
      CONTROL_DB: controlDb,
      SHARD_01: shard01Db,
      SHARD_ALLOWLIST: 'SHARD_01,SHARD_02'
    };

    const { route, shardDb } = await resolveClientShard('client_alpha', env);
    expect(route.shardBinding).toBe('SHARD_01');
    expect(route.routingStatus).toBe('ACTIVE');
    expect(shardDb).toBe(shard01Db);
  });

  it('fails closed when client has no shard mapping in Control DB', async () => {
    const env: any = {
      CONTROL_DB: controlDb,
      SHARD_01: shard01Db,
      SHARD_ALLOWLIST: 'SHARD_01,SHARD_02'
    };

    await expect(resolveClientShard('client_unmapped', env)).rejects.toThrow(
      /No shard route mapped in Control Database/i
    );
  });

  it('fails closed when client is in DELETING status', async () => {
    const env: any = {
      CONTROL_DB: controlDb,
      SHARD_01: shard01Db,
      SHARD_ALLOWLIST: 'SHARD_01,SHARD_02'
    };

    await expect(resolveClientShard('client_deleting', env)).rejects.toThrow(
      /currently in deletion lifecycle/i
    );
  });

  it('fails closed when shard binding is outside the explicit allowlist', async () => {
    // Insert client first to satisfy foreign key
    controlDb.getNativeDb().prepare(
      `INSERT INTO clients (client_id, client_name, client_code, industry, created_by_uid, status)
       VALUES ('client_rogue', 'Rogue Corp', 'ROG', 'Security', 'uid_auditor_a', 'Active')`
    ).run();

    // Map client to SHARD_99 in Control DB (tampered/unauthorized)
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
       VALUES ('client_rogue', 'SHARD_99', 'rogue-db', 'ACTIVE')`
    ).run();

    const env: any = {
      CONTROL_DB: controlDb,
      SHARD_01: shard01Db,
      SHARD_ALLOWLIST: 'SHARD_01,SHARD_02'
    };

    await expect(resolveClientShard('client_rogue', env)).rejects.toThrow(
      /not in the authorized allowlist/i
    );
  });

  it('rejects access when an Auditor attempts to access another Client', async () => {
    const env: any = { CONTROL_DB: controlDb };
    const auditorA: AuthenticatedUser = {
      uid: 'uid_auditor_a',
      email: 'auditor.a@test.com',
      displayName: 'Auditor A',
      accountStatus: 'Active',
      globalRole: 'Auditor'
    };

    // Auditor A can access client_alpha
    const membership = await authorizeClientAccess(auditorA, 'client_alpha', env);
    expect(membership.role).toBe('Auditor');

    // Auditor A attempting to access client_beta MUST be rejected with HTTP 403
    await expect(authorizeClientAccess(auditorA, 'client_beta', env)).rejects.toThrow(
      /not a registered member of Client 'client_beta'/i
    );
  });

  it('rejects access when user membership in Client is Suspended or Revoked', async () => {
    // Add revoked membership
    controlDb.getNativeDb().prepare(
      `INSERT INTO client_memberships (membership_id, client_id, user_uid, role, account_status, assigned_by_uid)
       VALUES ('mem_revoked', 'client_alpha', 'uid_auditor_b', 'Auditor', 'Suspended', 'uid_auditor_a')`
    ).run();

    const env: any = { CONTROL_DB: controlDb };
    const auditorB: AuthenticatedUser = {
      uid: 'uid_auditor_b',
      email: 'auditor.b@test.com',
      displayName: 'Auditor B',
      accountStatus: 'Active',
      globalRole: 'Auditor'
    };

    await expect(authorizeClientAccess(auditorB, 'client_alpha', env)).rejects.toThrow(
      /Membership for user 'uid_auditor_b' in Client 'client_alpha' is Suspended/i
    );
  });
});
