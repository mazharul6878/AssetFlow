import { describe, it, expect, beforeEach } from 'vitest';
import * as path from 'node:path';
import { createLocalTestD1, MockD1Database, createMockJWT } from './test-helper';
import { authenticateFirebaseRequest, AuthenticationError } from '../src/middleware/firebaseAuth';
import { Env } from '../src/env';

describe('Firebase JWT and Control DB Authentication Middleware', () => {
  let controlDb: MockD1Database;
  let env: Env;
  const controlMigration = path.resolve(__dirname, '../migrations/control/0001_initial_control.sql');

  beforeEach(() => {
    controlDb = createLocalTestD1(controlMigration);

    // Seed Active and Suspended users in CONTROL_DB
    controlDb.getNativeDb().prepare(
      `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
       VALUES 
         ('uid_active_user', 'active@test.com', 'Active User', 'Active User', 'active1', 'Auditor', 'Active'),
         ('uid_suspended_user', 'suspended@test.com', 'Suspended User', 'Suspended User', 'susp1', 'Auditor', 'Suspended'),
         ('uid_deleted_user', 'deleted@test.com', 'Deleted User', 'Deleted User', 'del1', 'Auditor', 'Deleted')`
    ).run();

    env = {
      CONTROL_DB: controlDb as any,
      SHARD_01: {} as any,
      ENVIRONMENT: 'local',
      FIREBASE_PROJECT_ID: 'spherical-booth-366013',
      SHARD_ALLOWLIST: 'SHARD_01',
      JWT_VERIFICATION_MOCK: 'true'
    };
  });

  it('rejects requests with missing Authorization header', async () => {
    const request = new Request('https://api.assetflow.local/api/v1/clients');
    await expect(authenticateFirebaseRequest(request, env)).rejects.toThrow(
      /Missing or malformed Authorization header/i
    );
  });

  it('rejects requests with malformed Authorization header format', async () => {
    const request = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: 'Basic dXNlcjpwYXNz' }
    });
    await expect(authenticateFirebaseRequest(request, env)).rejects.toThrow(
      /Missing or malformed Authorization header/i
    );
  });

  it('rejects expired JWT tokens', async () => {
    const expiredToken = createMockJWT({
      sub: 'uid_active_user',
      exp: Math.floor(Date.now() / 1000) - 300 // expired 5 minutes ago
    });

    const request = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${expiredToken}` }
    });

    await expect(authenticateFirebaseRequest(request, env)).rejects.toThrow(
      /Token expired/i
    );
  });

  it('rejects valid JWT when user profile does not exist in Control DB', async () => {
    const validToken = createMockJWT({
      sub: 'uid_unknown_unregistered',
      email: 'ghost@test.com'
    });

    const request = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${validToken}` }
    });

    await expect(authenticateFirebaseRequest(request, env)).rejects.toThrow(
      /User profile not found in Control Database/i
    );
  });

  it('rejects valid JWT when user status in Control DB is Suspended', async () => {
    const validToken = createMockJWT({
      sub: 'uid_suspended_user',
      email: 'suspended@test.com'
    });

    const request = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${validToken}` }
    });

    await expect(authenticateFirebaseRequest(request, env)).rejects.toThrow(
      /User account status is 'Suspended'. Access has been revoked or suspended./i
    );
  });

  it('successfully authenticates active user and returns profile identity', async () => {
    const validToken = createMockJWT({
      sub: 'uid_active_user',
      email: 'active@test.com',
      name: 'Active User'
    });

    const request = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${validToken}` }
    });

    const user = await authenticateFirebaseRequest(request, env);
    expect(user.uid).toBe('uid_active_user');
    expect(user.email).toBe('active@test.com');
    expect(user.accountStatus).toBe('Active');
    expect(user.globalRole).toBe('Auditor');
  });

  // Explicit Negative Security Tests for Firebase ID Token Verification
  it('rejects tokens signed with unsupported algorithms (e.g. HS256 instead of RS256)', async () => {
    const badAlgToken = createMockJWT({ sub: 'uid_active_user' }, { alg: 'HS256' });
    const req = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${badAlgToken}` }
    });
    await expect(authenticateFirebaseRequest(req, env)).rejects.toThrow(/Unsupported algorithm 'HS256'/i);
  });

  it('rejects tokens missing the key ID (kid) in header', async () => {
    const missingKidToken = createMockJWT({ sub: 'uid_active_user' }, { kid: '' });
    const req = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${missingKidToken}` }
    });
    await expect(authenticateFirebaseRequest(req, env)).rejects.toThrow(/Token header missing valid key ID/i);
  });

  it('rejects tokens with mismatched audience (wrong Firebase project)', async () => {
    const wrongAudToken = createMockJWT({
      sub: 'uid_active_user',
      aud: 'malicious-or-wrong-project-id'
    });
    const req = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${wrongAudToken}` }
    });
    await expect(authenticateFirebaseRequest(req, env)).rejects.toThrow(/Invalid audience/i);
  });

  it('rejects tokens with mismatched issuer', async () => {
    const wrongIssToken = createMockJWT({
      sub: 'uid_active_user',
      iss: 'https://attacker-identity.example.com'
    });
    const req = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${wrongIssToken}` }
    });
    await expect(authenticateFirebaseRequest(req, env)).rejects.toThrow(/Invalid issuer/i);
  });

  it('rejects tokens with empty or blank subject (UID)', async () => {
    const emptySubToken = createMockJWT({ sub: '   ' });
    const req = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${emptySubToken}` }
    });
    await expect(authenticateFirebaseRequest(req, env)).rejects.toThrow(/invalid or missing subject/i);
  });

  it('rejects tokens with subject exceeding maximum 128 characters', async () => {
    const tooLongSub = 'a'.repeat(129);
    const longSubToken = createMockJWT({ sub: tooLongSub });
    const req = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${longSubToken}` }
    });
    await expect(authenticateFirebaseRequest(req, env)).rejects.toThrow(/invalid or missing subject/i);
  });

  it('rejects Firebase Custom Tokens presented as ID tokens', async () => {
    const customToken = createMockJWT({
      sub: 'uid_active_user',
      aud: 'https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit'
    });
    const req = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${customToken}` }
    });
    await expect(authenticateFirebaseRequest(req, env)).rejects.toThrow(/Custom tokens cannot be used as Firebase ID tokens/i);
  });

  it('rejects tokens with future auth_time', async () => {
    const futureAuthToken = createMockJWT({
      sub: 'uid_active_user',
      auth_time: Math.floor(Date.now() / 1000) + 3600
    });
    const req = new Request('https://api.assetflow.local/api/v1/clients', {
      headers: { Authorization: `Bearer ${futureAuthToken}` }
    });
    await expect(authenticateFirebaseRequest(req, env)).rejects.toThrow(/Token auth_time is in the future/i);
  });
});
