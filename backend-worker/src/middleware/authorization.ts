// Tenant and Role-Based Authorization Middleware

import { Env } from '../env';
import { AuthenticatedUser, ClientMembershipContext, ClientRole, AccountStatus } from '../domain/permissions';

export class AuthorizationError extends Error {
  constructor(message: string, public statusCode: number = 403) {
    super(message);
    this.name = 'AuthorizationError';
  }
}

export async function authorizeClientAccess(
  user: AuthenticatedUser,
  clientId: string,
  env: Env
): Promise<ClientMembershipContext> {
  // 1. Verify Client existence and status in CONTROL_DB
  const clientRow = await env.CONTROL_DB.prepare(
    'SELECT client_id, status FROM clients WHERE client_id = ?'
  )
    .bind(clientId)
    .first<{ client_id: string; status: string }>();

  if (!clientRow) {
    throw new AuthorizationError(`Client with ID '${clientId}' does not exist`, 404);
  }

  if (clientRow.status !== 'Active') {
    throw new AuthorizationError(
      `Client '${clientId}' is in status '${clientRow.status}' and cannot process operational requests`,
      403
    );
  }

  // 2. Query Authoritative client_memberships table
  // Note: Track 4 mandates that global System Owner identity is separate from Client membership.
  // Access to client-scoped resources strictly requires an authorized client_memberships record.
  const memberRow = await env.CONTROL_DB.prepare(
    `SELECT membership_id, client_id, user_uid, role, account_status, 
            has_review_authority, can_publish, can_sync_drive
     FROM client_memberships
     WHERE client_id = ? AND user_uid = ?`
  )
    .bind(clientId, user.uid)
    .first<{
      membership_id: string;
      client_id: string;
      user_uid: string;
      role: string;
      account_status: string;
      has_review_authority: number;
      can_publish: number;
      can_sync_drive: number;
    }>();

  if (!memberRow) {
    throw new AuthorizationError(`User '${user.uid}' is not a registered member of Client '${clientId}'`, 403);
  }

  if (memberRow.account_status !== 'Active') {
    throw new AuthorizationError(
      `Membership for user '${user.uid}' in Client '${clientId}' is ${memberRow.account_status}`,
      403
    );
  }

  return {
    membershipId: memberRow.membership_id,
    clientId: memberRow.client_id,
    userUid: memberRow.user_uid,
    role: memberRow.role as ClientRole,
    accountStatus: memberRow.account_status as AccountStatus,
    hasReviewAuthority: memberRow.has_review_authority === 1,
    canPublish: memberRow.can_publish === 1,
    canSyncDrive: memberRow.can_sync_drive === 1
  };
}

export function assertAdminOrOwner(membership: ClientMembershipContext): void {
  if (membership.role !== 'Owner' && membership.role !== 'Admin') {
    throw new AuthorizationError(
      `Operation requires Admin or Owner authority. Current Client role: '${membership.role}'`,
      403
    );
  }
}

export function assertCanPublish(membership: ClientMembershipContext): void {
  assertAdminOrOwner(membership);
  if (!membership.canPublish && membership.role !== 'Owner') {
    throw new AuthorizationError(
      `Admin '${membership.userUid}' lacks publish authority (canPublish=false)`,
      403
    );
  }
}
