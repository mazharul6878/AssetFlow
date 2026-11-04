// Firebase JWT Verification Middleware via 'jose' with Real-Time Control DB Status Validation

import { jwtVerify, createRemoteJWKSet, JWTVerifyGetKey } from 'jose';
import { Env } from '../env';
import { AuthenticatedUser } from '../domain/permissions';

let cachedJWKS: JWTVerifyGetKey | null = null;

function getFirebaseJWKS(): JWTVerifyGetKey {
  if (!cachedJWKS) {
    cachedJWKS = createRemoteJWKSet(
      new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com')
    );
  }
  return cachedJWKS;
}

export class AuthenticationError extends Error {
  constructor(message: string, public statusCode: number = 401) {
    super(message);
    this.name = 'AuthenticationError';
  }
}

export interface VerifiedTokenUser {
  uid: string;
  email: string;
  tokenDisplayName: string;
}

export async function verifyFirebaseTokenOnly(
  request: Request,
  env: Env
): Promise<VerifiedTokenUser> {
  const authHeader = request.headers.get('Authorization');
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    throw new AuthenticationError('Missing or malformed Authorization header. Expected Bearer token.', 401);
  }

  const token = authHeader.substring(7).trim();
  if (!token) {
    throw new AuthenticationError('Empty Bearer token provided.', 401);
  }

  let uid: string;
  let email = '';
  let tokenDisplayName = '';

  // 1. Verify Cryptographic Signature, Headers & Claims
  if (env.JWT_VERIFICATION_MOCK === 'true') {
    // Isolated Mock Validation for Unit Tests
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        throw new Error('Invalid token structure');
      }
      const header = JSON.parse(atob(parts[0]));
      const payload = JSON.parse(atob(parts[1]));

      // 1.1 Header Algorithm & Key ID
      if (header.alg && header.alg !== 'RS256') {
        throw new Error(`Unsupported algorithm '${header.alg}'. Expected RS256.`);
      }
      if (header.kid !== undefined && (typeof header.kid !== 'string' || header.kid.trim().length === 0)) {
        throw new Error('Token header missing valid key ID (kid)');
      }

      // 1.2 Non-empty valid subject
      if (!payload.sub || typeof payload.sub !== 'string' || payload.sub.trim().length === 0 || payload.sub.length > 128) {
        throw new Error('Token payload has invalid or missing subject (sub)');
      }

      // 1.3 Expiration & Timestamps
      const nowSec = Math.floor(Date.now() / 1000);
      if (payload.exp && payload.exp < nowSec) {
        throw new Error('Token expired');
      }
      if (payload.iat && payload.iat > nowSec + 300) {
        throw new Error('Token iat is in the future');
      }
      if (payload.auth_time && payload.auth_time > nowSec + 300) {
        throw new Error('Token auth_time is in the future');
      }

      // 1.4 Reject Custom Tokens
      if (payload.aud === 'https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit') {
        throw new Error('Custom tokens cannot be used as Firebase ID tokens.');
      }

      // 1.5 Audience & Issuer
      if (env.FIREBASE_PROJECT_ID && payload.aud && payload.aud !== env.FIREBASE_PROJECT_ID) {
        throw new Error(`Invalid audience '${payload.aud}'. Expected '${env.FIREBASE_PROJECT_ID}'`);
      }
      if (env.FIREBASE_PROJECT_ID && payload.iss && payload.iss !== `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`) {
        throw new Error(`Invalid issuer '${payload.iss}'. Expected 'https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}'`);
      }

      uid = payload.sub;
      email = payload.email || '';
      tokenDisplayName = payload.name || '';
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      throw new AuthenticationError(`Mock token verification failed: ${msg}`, 401);
    }
  } else {
    // Production & Real Edge Verification via Google Public JWKS
    try {
      const jwks = getFirebaseJWKS();
      const expectedIssuer = `https://securetoken.google.com/${env.FIREBASE_PROJECT_ID}`;
      const expectedAudience = env.FIREBASE_PROJECT_ID;

      const { payload, protectedHeader } = await jwtVerify(token, jwks, {
        issuer: expectedIssuer,
        audience: expectedAudience,
        algorithms: ['RS256']
      });

      if (!protectedHeader.kid) {
        throw new AuthenticationError('Token header missing key ID (kid)', 401);
      }
      if (protectedHeader.alg !== 'RS256') {
        throw new AuthenticationError(`Unsupported algorithm '${protectedHeader.alg}'. Expected RS256.`, 401);
      }
      if (!payload.sub || typeof payload.sub !== 'string' || payload.sub.trim().length === 0 || payload.sub.length > 128) {
        throw new AuthenticationError('Token payload has invalid or missing subject (sub)', 401);
      }
      if (typeof payload.auth_time === 'number') {
        const nowSec = Math.floor(Date.now() / 1000);
        if (payload.auth_time > nowSec + 300) {
          throw new AuthenticationError('Token auth_time is in the future', 401);
        }
      }

      uid = payload.sub;
      email = (payload.email as string) || '';
      tokenDisplayName = (payload.name as string) || '';
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      throw new AuthenticationError(`Invalid or expired Firebase authentication token: ${msg}`, 401);
    }
  }

  return { uid, email, tokenDisplayName };
}

export async function authenticateFirebaseRequest(
  request: Request,
  env: Env
): Promise<AuthenticatedUser> {
  const { uid, email, tokenDisplayName } = await verifyFirebaseTokenOnly(request, env);

  // 2. Real-Time Account Revocation & Status Check in CONTROL_DB
  const userRow = await env.CONTROL_DB.prepare(
    'SELECT uid, email, display_name, full_name, nickname, global_role, account_status FROM users WHERE uid = ?'
  )
    .bind(uid)
    .first<{
      uid: string;
      email: string;
      display_name: string;
      full_name: string;
      nickname: string;
      global_role: string;
      account_status: string;
    }>();

  if (!userRow) {
    throw new AuthenticationError(`User profile not found in Control Database for UID: ${uid}`, 403);
  }

  if (userRow.account_status !== 'Active') {
    throw new AuthenticationError(
      `User account status is '${userRow.account_status}'. Access has been revoked or suspended.`,
      403
    );
  }

  return {
    uid: userRow.uid,
    email: userRow.email || email,
    displayName: userRow.full_name || userRow.display_name || tokenDisplayName || userRow.nickname || 'Auditor',
    accountStatus: userRow.account_status as 'Active' | 'Suspended' | 'Deleted',
    globalRole: (userRow.global_role as 'Owner' | 'Admin' | 'Auditor' | 'Reviewer') || 'Auditor'
  };
}
