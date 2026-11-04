// Service for User Management, Access Governance, and Client Assignments

import { ControlRepository, UserEntity, ClientMembershipEntity } from '../repositories/controlRepository';
import { ShardRepository, AccessRequestEntity } from '../repositories/shardRepository';
import { GlobalRole, AccountStatus, ClientRole } from '../domain/permissions';

export class UserService {
  constructor(private controlRepo: ControlRepository) {}

  async listUsers(
    actor: { uid: string; globalRole: GlobalRole },
    filter?: { query?: string; role?: string; status?: string }
  ): Promise<UserEntity[]> {
    if (actor.globalRole !== 'Owner' && actor.globalRole !== 'Admin') {
      throw new Error('Forbidden: Only Owner or Admin can list system users');
    }

    const all = await this.controlRepo.listUsers(filter);
    if (actor.globalRole === 'Owner') {
      return all;
    }
    // Admin only sees Auditors and Reviewers, plus themselves
    return all.filter(u => u.uid === actor.uid || (u.global_role !== 'Owner' && u.global_role !== 'Admin'));
  }

  async getUser(uid: string): Promise<UserEntity | null> {
    return this.controlRepo.getUser(uid);
  }

  async updateUserStatus(
    actor: { uid: string; globalRole: GlobalRole },
    targetUid: string,
    newStatus: AccountStatus
  ): Promise<void> {
    const target = await this.controlRepo.getUser(targetUid);
    if (!target) throw new Error(`User '${targetUid}' not found`);

    if (target.global_role === 'Owner') {
      throw new Error('Security Error: System Owner account status cannot be modified');
    }

    if (actor.globalRole === 'Admin') {
      if (target.global_role === 'Admin') {
        throw new Error('Forbidden: Admins cannot suspend or modify other Admins or the System Owner');
      }
    } else if (actor.globalRole !== 'Owner') {
      throw new Error('Forbidden: Insufficient privileges to update user status');
    }

    await this.controlRepo.updateUserStatus(targetUid, newStatus);
  }

  async updateUserRole(
    actor: { uid: string; globalRole: GlobalRole },
    targetUid: string,
    newRole: GlobalRole
  ): Promise<void> {
    const target = await this.controlRepo.getUser(targetUid);
    if (!target) throw new Error(`User '${targetUid}' not found`);

    if (target.global_role === 'Owner') {
      throw new Error('Security Error: System Owner global role is immutable');
    }
    if (newRole === 'Owner') {
      throw new Error('Security Error: System Owner cannot be reassigned via role update');
    }

    if (actor.globalRole !== 'Owner') {
      throw new Error('Forbidden: Only the System Owner can promote or demote Admin roles');
    }

    await this.controlRepo.updateUserRole(targetUid, newRole);
  }

  // --- Client Memberships / Assignments ---

  async listMembershipsForClient(
    actor: { uid: string; globalRole: GlobalRole },
    clientId: string
  ): Promise<ClientMembershipEntity[]> {
    return this.controlRepo.listClientMemberships(clientId);
  }

  async assignUserToClient(
    actor: { uid: string; globalRole: GlobalRole },
    clientId: string,
    params: {
      userUid: string;
      role: 'Owner' | 'Admin' | 'Auditor' | 'Reviewer';
      hasReviewAuthority?: boolean;
      canPublish?: boolean;
      canSyncDrive?: boolean;
    }
  ): Promise<void> {
    const target = await this.controlRepo.getUser(params.userUid);
    if (!target) throw new Error(`User '${params.userUid}' does not exist`);
    if (target.account_status !== 'Active') {
      throw new Error(`Cannot assign user with status '${target.account_status}'`);
    }

    if (actor.globalRole !== 'Owner' && actor.globalRole !== 'Admin') {
      throw new Error('Forbidden: Only Client Admin or Owner can assign members');
    }

    await this.controlRepo.upsertClientMembership({
      clientId,
      userUid: params.userUid,
      role: params.role,
      accountStatus: 'Active',
      hasReviewAuthority: params.hasReviewAuthority ?? (params.role === 'Admin'),
      canPublish: params.canPublish ?? (params.role === 'Admin'),
      canSyncDrive: params.canSyncDrive ?? (params.role === 'Admin'),
      assignedByUid: actor.uid
    });
  }

  async revokeUserFromClient(
    actor: { uid: string; globalRole: GlobalRole },
    clientId: string,
    targetUid: string
  ): Promise<void> {
    const target = await this.controlRepo.getUser(targetUid);
    if (target && target.global_role === 'Owner') {
      throw new Error('Security Error: System Owner cannot be unassigned from a Client');
    }

    const memberships = await this.controlRepo.listClientMemberships(clientId);
    const activeAdmins = memberships.filter(m => m.account_status === 'Active' && (m.role === 'Admin' || m.role === 'Owner'));
    if (activeAdmins.length <= 1 && activeAdmins.some(m => m.user_uid === targetUid)) {
      throw new Error('Security Error: Cannot unassign the last Admin from the Client');
    }

    await this.controlRepo.revokeClientMembership(clientId, targetUid);
  }

  // --- Access Requests ---

  async submitAccessRequest(
    actor: { uid: string; email: string },
    clientId: string,
    fiscalYearId: string,
    reason?: string,
    shardRepo?: ShardRepository
  ): Promise<AccessRequestEntity> {
    if (!shardRepo) throw new Error('Shard repository required for access request');

    // Verify user is not already an active member of this client
    const memberships = await this.controlRepo.listClientMemberships(clientId);
    const existingMem = memberships.find(m => m.user_uid === actor.uid && m.account_status === 'Active');
    if (existingMem) {
      throw new Error('User already has active membership in this Client');
    }

    const existingPending = await shardRepo.getPendingAccessRequest(clientId, actor.uid);
    if (existingPending) {
      return existingPending;
    }

    const requestId = `req_${clientId}_${actor.uid}_${Date.now().toString().slice(-6)}`;
    const newReq: AccessRequestEntity = {
      request_id: requestId,
      client_id: clientId,
      fiscal_year_id: fiscalYearId,
      requester_uid: actor.uid,
      requester_email: actor.email,
      reason: reason || '',
      status: 'Pending',
      requested_at: new Date().toISOString()
    };

    await shardRepo.createAccessRequest(newReq);
    return newReq;
  }

  async decideAccessRequest(
    actor: { uid: string; globalRole: GlobalRole },
    clientId: string,
    requestId: string,
    decision: 'Approved' | 'Rejected',
    shardRepo: ShardRepository
  ): Promise<AccessRequestEntity> {
    if (actor.globalRole !== 'Owner' && actor.globalRole !== 'Admin') {
      throw new Error('Forbidden: Only Client Admin or Owner can decide access requests');
    }

    const updated = await shardRepo.decideAccessRequest(requestId, decision, actor.uid);
    if (!updated) {
      throw new Error(`Access request '${requestId}' not found`);
    }

    if (decision === 'Approved') {
      // Create membership in Control DB with Auditor role
      await this.controlRepo.upsertClientMembership({
        clientId,
        userUid: updated.requester_uid,
        role: 'Auditor',
        accountStatus: 'Active',
        hasReviewAuthority: 0,
        canPublish: 0,
        canSyncDrive: 0,
        assignedByUid: actor.uid
      });
    }

    return updated;
  }
}
