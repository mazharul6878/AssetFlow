// Domain Definition for User Roles and Access Control

export type GlobalRole = 'Owner' | 'Admin' | 'Auditor' | 'Reviewer';
export type ClientRole = 'Owner' | 'Admin' | 'Auditor' | 'Reviewer';
export type AccountStatus = 'Active' | 'Suspended' | 'Deleted' | 'Revoked';

export interface AuthenticatedUser {
  uid: string;
  email: string;
  displayName: string;
  accountStatus: AccountStatus;
  globalRole: GlobalRole;
}

export interface ClientMembershipContext {
  membershipId: string;
  clientId: string;
  userUid: string;
  role: ClientRole;
  accountStatus: AccountStatus;
  hasReviewAuthority: boolean;
  canPublish: boolean;
  canSyncDrive: boolean;
}

export function canManageSchema(membership: ClientMembershipContext): boolean {
  if (membership.accountStatus !== 'Active') return false;
  return membership.role === 'Owner' || membership.role === 'Admin';
}

export function canPublishSchema(membership: ClientMembershipContext): boolean {
  if (membership.accountStatus !== 'Active') return false;
  return (membership.role === 'Owner' || membership.role === 'Admin') && membership.canPublish;
}

export function canSubmitCounts(membership: ClientMembershipContext): boolean {
  if (membership.accountStatus !== 'Active') return false;
  return ['Owner', 'Admin', 'Auditor', 'Reviewer'].includes(membership.role);
}

export function canReviewSubmissions(membership: ClientMembershipContext): boolean {
  if (membership.accountStatus !== 'Active') return false;
  return (
    membership.role === 'Owner' ||
    membership.role === 'Admin' ||
    (membership.role === 'Reviewer' && membership.hasReviewAuthority)
  );
}
