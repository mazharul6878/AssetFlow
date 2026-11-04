// Service for System Owner Initial Bootstrap and Role Resolution via Control DB

import { Env } from '../env';
import { VerifiedTokenUser } from '../middleware/firebaseAuth';

export class BootstrapError extends Error {
  constructor(
    public code: string,
    message: string,
    public statusCode: number = 400
  ) {
    super(message);
    this.name = 'BootstrapError';
    Object.setPrototypeOf(this, BootstrapError.prototype);
  }
}

export interface BootstrapResponse {
  status: 'OK';
  role: 'Owner';
  uid: string;
  email: string;
  message: string;
}

export interface UserSessionResponse {
  status: 'OK' | 'UNPROVISIONED';
  user?: {
    uid: string;
    email: string;
    displayName: string;
    fullName: string;
    nickname: string;
    globalRole: string;
    accountStatus: string;
  };
  memberships?: Array<{
    clientId: string;
    role: string;
    accountStatus: string;
    hasReviewAuthority: boolean;
    canPublish: boolean;
    canSyncDrive: boolean;
  }>;
  message?: string;
}

export class BootstrapService {
  constructor(private controlDb: D1Database) {}

  async bootstrapInitialOwner(
    tokenUser: VerifiedTokenUser,
    env: Env,
    profileInput?: { fullName?: string; nickname?: string }
  ): Promise<BootstrapResponse> {
    // 1. Verify INITIAL_OWNER_UID configuration
    const configuredOwnerUid = env.INITIAL_OWNER_UID?.trim();
    if (!configuredOwnerUid) {
      throw new BootstrapError(
        'BOOTSTRAP_CONFIGURATION_MISSING',
        'System Owner bootstrap configuration (INITIAL_OWNER_UID) is not set on the server.',
        500
      );
    }

    // 2. Check if bootstrap was already executed
    try {
      const existingBootstrap = await this.controlDb
        .prepare('SELECT owner_uid, owner_email, bootstrapped_at FROM system_bootstrap WHERE singleton_id = 1')
        .first<{ owner_uid: string; owner_email: string; bootstrapped_at: string }>();

      if (existingBootstrap) {
        if (existingBootstrap.owner_uid === tokenUser.uid) {
          // Idempotent recovery for the owner
          return {
            status: 'OK',
            role: 'Owner',
            uid: tokenUser.uid,
            email: tokenUser.email || existingBootstrap.owner_email,
            message: 'System Owner identity already bootstrapped'
          };
        } else {
          throw new BootstrapError(
            'BOOTSTRAP_ALREADY_COMPLETE',
            'System bootstrap has already been completed by the designated System Owner.',
            409
          );
        }
      }
    } catch (e) {
      if (e instanceof BootstrapError) throw e;
      // If table doesn't exist yet, throw database error
      throw new BootstrapError(
        'BOOTSTRAP_DATABASE_ERROR',
        `Failed to check bootstrap status in Control Database: ${e instanceof Error ? e.message : String(e)}`,
        500
      );
    }

    // 3. Authorization check against configured INITIAL_OWNER_UID
    if (tokenUser.uid !== configuredOwnerUid) {
      throw new BootstrapError(
        'INITIAL_OWNER_NOT_AUTHORIZED',
        'AUTH-02: Role Mismatch: Account is not authorized to bootstrap as System Owner.',
        403
      );
    }

    // 4. Atomic One-Time Provisioning via D1 batch()
    const nowIso = new Date().toISOString();
    const cleanEmail = (tokenUser.email || 'owner@assetflow.local').trim().toLowerCase();
    const fullName = profileInput?.fullName || tokenUser.tokenDisplayName || cleanEmail.split('@')[0] || 'System Owner';
    const nickname = profileInput?.nickname || 'owner';

    try {
      const statements = [
        // Insert into global users table as Owner
        this.controlDb.prepare(
          `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, 'Owner', 'Active', ?, ?)
           ON CONFLICT(uid) DO UPDATE SET
             global_role = 'Owner',
             account_status = 'Active',
             updated_at = excluded.updated_at`
        ).bind(tokenUser.uid, cleanEmail, fullName, fullName, nickname, nowIso, nowIso),

        // Insert into singleton system_bootstrap table
        this.controlDb.prepare(
          `INSERT INTO system_bootstrap (singleton_id, owner_uid, owner_email, bootstrapped_at)
           VALUES (1, ?, ?, ?)`
        ).bind(tokenUser.uid, cleanEmail, nowIso),

        // Update system_config
        this.controlDb.prepare(
          `INSERT OR REPLACE INTO system_config (config_key, config_value, description, updated_by_uid, updated_at)
           VALUES ('system_bootstrap_completed', 'true', 'System initial bootstrap executed', ?, ?)`
        ).bind(tokenUser.uid, nowIso)
      ];

      await this.controlDb.batch(statements);

      return {
        status: 'OK',
        role: 'Owner',
        uid: tokenUser.uid,
        email: cleanEmail,
        message: 'System Owner bootstrapped successfully'
      };
    } catch (e) {
      throw new BootstrapError(
        'BOOTSTRAP_DATABASE_ERROR',
        `Failed to provision System Owner atomically in Control Database: ${e instanceof Error ? e.message : String(e)}`,
        500
      );
    }
  }

  async getUserSession(tokenUser: VerifiedTokenUser): Promise<UserSessionResponse> {
    try {
      const userRow = await this.controlDb
        .prepare('SELECT uid, email, display_name, full_name, nickname, global_role, account_status FROM users WHERE uid = ?')
        .bind(tokenUser.uid)
        .first<any>();

      if (!userRow) {
        return {
          status: 'UNPROVISIONED',
          message: 'User account is not provisioned in AssetFlow Control Database.'
        };
      }

      const memberships = await this.controlDb
        .prepare('SELECT client_id, role, account_status, has_review_authority, can_publish, can_sync_drive FROM client_memberships WHERE user_uid = ? AND account_status = \'Active\'')
        .bind(tokenUser.uid)
        .all<any>();

      return {
        status: 'OK',
        user: {
          uid: userRow.uid,
          email: userRow.email,
          displayName: userRow.display_name,
          fullName: userRow.full_name,
          nickname: userRow.nickname,
          globalRole: userRow.global_role,
          accountStatus: userRow.account_status
        },
        memberships: (memberships.results || []).map((m: any) => ({
          clientId: m.client_id,
          role: m.role,
          accountStatus: m.account_status,
          hasReviewAuthority: m.has_review_authority === 1,
          canPublish: m.can_publish === 1,
          canSyncDrive: m.can_sync_drive === 1
        }))
      };
    } catch (e) {
      throw new BootstrapError(
        'BOOTSTRAP_DATABASE_ERROR',
        `Database error resolving user session: ${e instanceof Error ? e.message : String(e)}`,
        500
      );
    }
  }
}
