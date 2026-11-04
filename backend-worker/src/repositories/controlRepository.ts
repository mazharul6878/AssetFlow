// Repository for Control Database (CONTROL_DB) Operations

import { Env } from '../env';
import { GlobalRole, AccountStatus, ClientRole } from '../domain/permissions';

export interface UserEntity {
  uid: string;
  email: string;
  display_name: string;
  full_name: string;
  nickname: string;
  global_role: GlobalRole;
  account_status: AccountStatus;
  created_at: string;
  updated_at: string;
}

export interface ClientEntity {
  client_id: string;
  client_name: string;
  client_code: string;
  industry: string;
  contact_person?: string;
  contact_email?: string;
  status: string;
  setup_status?: string;
  active_fiscal_year_id: string;
  unique_asset_key_header: string;
  drive_root_folder_id?: string;
  drive_provisioning_status?: string;
  drive_provisioning_error?: string;
  created_by_uid: string;
  created_at: string;
  updated_at: string;
}

export interface ClientMembershipEntity {
  membership_id: string;
  client_id: string;
  user_uid: string;
  user_email?: string;
  user_name?: string;
  role: 'Owner' | 'Admin' | 'Auditor' | 'Reviewer';
  account_status: 'Active' | 'Suspended' | 'Revoked';
  has_review_authority: number;
  can_publish: number;
  can_sync_drive: number;
  assigned_by_uid: string;
  created_at?: string;
  updated_at?: string;
}

export class ControlRepository {
  constructor(private controlDb: D1Database) {}

  async getUser(uid: string): Promise<UserEntity | null> {
    return this.controlDb
      .prepare('SELECT * FROM users WHERE uid = ?')
      .bind(uid)
      .first<UserEntity>();
  }

  async upsertUser(user: {
    uid: string;
    email: string;
    display_name: string;
    full_name: string;
    nickname: string;
    global_role?: GlobalRole;
    account_status?: AccountStatus;
  }): Promise<void> {
    const role = user.global_role || 'Auditor';
    const status = user.account_status || 'Active';
    await this.controlDb
      .prepare(
        `INSERT INTO users (uid, email, display_name, full_name, nickname, global_role, account_status)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(uid) DO UPDATE SET
            email = excluded.email,
            display_name = excluded.display_name,
            full_name = excluded.full_name,
            nickname = excluded.nickname,
            updated_at = datetime('now')`
      )
      .bind(user.uid, user.email, user.display_name, user.full_name, user.nickname, role, status)
      .run();
  }

  async listUsers(filter?: { query?: string; role?: string; status?: string }): Promise<UserEntity[]> {
    let sql = 'SELECT * FROM users WHERE 1=1';
    const params: any[] = [];
    if (filter?.role) {
      sql += ' AND global_role = ?';
      params.push(filter.role);
    }
    if (filter?.status) {
      sql += ' AND account_status = ?';
      params.push(filter.status);
    }
    if (filter?.query) {
      sql += ' AND (email LIKE ? OR full_name LIKE ? OR nickname LIKE ? OR display_name LIKE ?)';
      const q = `%${filter.query}%`;
      params.push(q, q, q, q);
    }
    sql += ' ORDER BY full_name COLLATE NOCASE ASC';
    const res = await this.controlDb.prepare(sql).bind(...params).all<UserEntity>();
    return res.results || [];
  }

  async updateUserStatus(uid: string, status: AccountStatus): Promise<void> {
    await this.controlDb
      .prepare(`UPDATE users SET account_status = ?, updated_at = datetime('now') WHERE uid = ?`)
      .bind(status, uid)
      .run();
  }

  async updateUserRole(uid: string, role: GlobalRole): Promise<void> {
    await this.controlDb
      .prepare(`UPDATE users SET global_role = ?, updated_at = datetime('now') WHERE uid = ?`)
      .bind(role, uid)
      .run();
  }

  async listClientMemberships(clientId: string): Promise<ClientMembershipEntity[]> {
    const res = await this.controlDb
      .prepare(
        `SELECT m.*, u.email as user_email, u.full_name as user_name
         FROM client_memberships m
         LEFT JOIN users u ON m.user_uid = u.uid
         WHERE m.client_id = ? AND m.account_status != 'Revoked'
         ORDER BY m.role ASC, u.full_name COLLATE NOCASE ASC`
      )
      .bind(clientId)
      .all<ClientMembershipEntity>();
    return res.results || [];
  }

  async upsertClientMembership(m: {
    clientId: string;
    userUid: string;
    role: 'Owner' | 'Admin' | 'Auditor' | 'Reviewer';
    accountStatus?: 'Active' | 'Suspended' | 'Revoked';
    hasReviewAuthority?: boolean | number;
    canPublish?: boolean | number;
    canSyncDrive?: boolean | number;
    assignedByUid: string;
  }): Promise<void> {
    const membershipId = `mem_${m.clientId}_${m.userUid}`.slice(0, 36);
    const status = m.accountStatus || 'Active';
    const rev = m.hasReviewAuthority ? 1 : 0;
    const pub = m.canPublish ? 1 : 0;
    const sync = m.canSyncDrive ? 1 : 0;

    await this.controlDb
      .prepare(
        `INSERT INTO client_memberships (
            membership_id, client_id, user_uid, role, account_status,
            has_review_authority, can_publish, can_sync_drive, assigned_by_uid
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(client_id, user_uid) DO UPDATE SET
            role = excluded.role,
            account_status = excluded.account_status,
            has_review_authority = excluded.has_review_authority,
            can_publish = excluded.can_publish,
            can_sync_drive = excluded.can_sync_drive,
            assigned_by_uid = excluded.assigned_by_uid,
            updated_at = datetime('now')`
      )
      .bind(
        membershipId,
        m.clientId,
        m.userUid,
        m.role,
        status,
        rev,
        pub,
        sync,
        m.assignedByUid
      )
      .run();
  }

  async revokeClientMembership(clientId: string, userUid: string): Promise<void> {
    await this.controlDb
      .prepare(
        `UPDATE client_memberships
         SET account_status = 'Revoked', updated_at = datetime('now')
         WHERE client_id = ? AND user_uid = ?`
      )
      .bind(clientId, userUid)
      .run();
  }

  async getClient(clientId: string): Promise<ClientEntity | null> {
    return this.controlDb
      .prepare('SELECT * FROM clients WHERE client_id = ?')
      .bind(clientId)
      .first<ClientEntity>();
  }

  async getClientByCode(clientCode: string): Promise<ClientEntity | null> {
    return this.controlDb
      .prepare('SELECT * FROM clients WHERE client_code = ?')
      .bind(clientCode)
      .first<ClientEntity>();
  }

  async listClientsForUser(userUid: string, isOwner: boolean): Promise<ClientEntity[]> {
    if (isOwner) {
      const res = await this.controlDb
        .prepare(
          `SELECT client_id, client_name, client_code, industry, contact_person, contact_email,
                  status, setup_status, active_fiscal_year_id, unique_asset_key_header, drive_root_folder_id,
                  drive_provisioning_status, drive_provisioning_error, created_by_uid, created_at, updated_at
           FROM clients
           WHERE status != 'Deleted'
           ORDER BY client_name COLLATE NOCASE ASC`
        )
        .all<ClientEntity>();
      return res.results || [];
    } else {
      const res = await this.controlDb
        .prepare(
          `SELECT c.client_id, c.client_name, c.client_code, c.industry, c.contact_person, c.contact_email,
                  c.status, c.setup_status, c.active_fiscal_year_id, c.unique_asset_key_header, c.drive_root_folder_id,
                  c.drive_provisioning_status, c.drive_provisioning_error, c.created_by_uid, c.created_at, c.updated_at
           FROM clients c
           INNER JOIN client_memberships m ON c.client_id = m.client_id AND m.user_uid = ? AND m.account_status = 'Active'
           WHERE c.status != 'Deleted' AND (c.setup_status IS NULL OR c.setup_status = 'COMPLETED')
           ORDER BY c.client_name COLLATE NOCASE ASC`
        )
        .bind(userUid)
        .all<ClientEntity>();
      return res.results || [];
    }
  }

  async initiateClientProvisioning(params: {
    clientId: string;
    clientName: string;
    clientCode: string;
    industry: string;
    contactPerson?: string;
    contactEmail?: string;
    activeFiscalYearId?: string;
    uniqueAssetKeyHeader?: string;
    driveRootFolderId?: string;
    driveProvisioningStatus?: string;
    driveProvisioningError?: string;
    createdByUid: string;
    userRole: 'Owner' | 'Admin';
    shardBinding?: string;
    fiscalYearLabel?: string;
  }): Promise<{ client: ClientEntity; isIdempotentReplay: boolean }> {
    const existing = await this.controlDb
      .prepare('SELECT * FROM clients WHERE client_code = ? OR client_id = ?')
      .bind(params.clientCode, params.clientId)
      .first<ClientEntity>();

    if (existing) {
      if (existing.created_by_uid === params.createdByUid) {
        if (existing.setup_status === 'COMPLETED') {
          const membershipId = `mem_${existing.client_id}_${params.createdByUid}`.slice(0, 36);
          await this.controlDb
            .prepare(
              `INSERT OR IGNORE INTO client_memberships (
                  membership_id, client_id, user_uid, role, account_status,
                  has_review_authority, can_publish, can_sync_drive, assigned_by_uid
               ) VALUES (?, ?, ?, ?, 'Active', 1, 1, 1, ?)`
            )
            .bind(membershipId, existing.client_id, params.createdByUid, params.userRole, params.createdByUid)
            .run();
          return { client: existing, isIdempotentReplay: true };
        } else {
          // Resume / retry provisioning of setup-incomplete client
          await this.controlDb
            .prepare(`UPDATE clients SET setup_status = 'PROVISIONING', updated_at = datetime('now') WHERE client_id = ?`)
            .bind(existing.client_id)
            .run();
          return { client: existing, isIdempotentReplay: false };
        }
      }
      throw new Error(`Client with code '${params.clientCode}' already exists`);
    }

    const fy = params.activeFiscalYearId || '2026_27';
    const keyHeader = params.uniqueAssetKeyHeader || 'Asset ID';
    const shard = params.shardBinding || 'SHARD_01';
    const membershipId = `mem_${params.clientId}_${params.createdByUid}`.slice(0, 36);
    const operationId = `op_onboard_${params.clientId}`;
    const driveStatus = params.driveProvisioningStatus || 'NOT_REQUESTED';

    await this.controlDb.batch([
      this.controlDb
        .prepare(
          `INSERT INTO clients (
              client_id, client_name, client_code, industry, contact_person, contact_email,
              status, setup_status, active_fiscal_year_id, unique_asset_key_header, drive_root_folder_id,
              drive_provisioning_status, drive_provisioning_error, created_by_uid
           ) VALUES (?, ?, ?, ?, ?, ?, 'Active', 'PROVISIONING', ?, ?, ?, ?, ?, ?)`
        )
        .bind(
          params.clientId,
          params.clientName,
          params.clientCode,
          params.industry,
          params.contactPerson || null,
          params.contactEmail || null,
          fy,
          keyHeader,
          params.driveRootFolderId || null,
          driveStatus,
          params.driveProvisioningError || null,
          params.createdByUid
        ),
      this.controlDb
        .prepare(
          `INSERT OR IGNORE INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
           VALUES (?, ?, ?, 'ACTIVE')`
        )
        .bind(params.clientId, shard, 'shard-01-db'),
      this.controlDb
        .prepare(
          `INSERT OR IGNORE INTO client_memberships (
              membership_id, client_id, user_uid, role, account_status,
              has_review_authority, can_publish, can_sync_drive, assigned_by_uid
           ) VALUES (?, ?, ?, ?, 'Active', 1, 1, 1, ?)`
        )
        .bind(
          membershipId,
          params.clientId,
          params.createdByUid,
          params.userRole,
          params.createdByUid
        ),
      this.controlDb
        .prepare(
          `INSERT OR REPLACE INTO client_onboarding_operations (
              operation_id, client_id, client_code, stage, created_by_uid, fiscal_year_label
           ) VALUES (?, ?, ?, 'CONTROL_PROVISIONED', ?, ?)`
        )
        .bind(
          operationId,
          params.clientId,
          params.clientCode,
          params.createdByUid,
          params.fiscalYearLabel || '2026–27'
        )
    ]);

    const created = await this.getClient(params.clientId);
    return {
      client: created || {
        client_id: params.clientId,
        client_name: params.clientName,
        client_code: params.clientCode,
        industry: params.industry,
        contact_person: params.contactPerson,
        contact_email: params.contactEmail,
        status: 'Active',
        setup_status: 'PROVISIONING',
        active_fiscal_year_id: fy,
        unique_asset_key_header: keyHeader,
        drive_root_folder_id: params.driveRootFolderId,
        drive_provisioning_status: driveStatus,
        created_by_uid: params.createdByUid,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString()
      },
      isIdempotentReplay: false
    };
  }

  async finalizeClientProvisioning(clientId: string, operationId: string, driveStatus: string, driveFolderId?: string): Promise<void> {
    await this.controlDb.batch([
      this.controlDb
        .prepare(
          `UPDATE clients
           SET setup_status = 'COMPLETED',
               drive_provisioning_status = ?,
               drive_root_folder_id = COALESCE(?, drive_root_folder_id),
               updated_at = datetime('now')
           WHERE client_id = ?`
        )
        .bind(driveStatus, driveFolderId || null, clientId),
      this.controlDb
        .prepare(
          `UPDATE client_onboarding_operations
           SET stage = 'COMPLETED', updated_at = datetime('now')
           WHERE operation_id = ?`
        )
        .bind(operationId)
    ]);
  }

  async failClientProvisioning(clientId: string, operationId: string, errorMessage: string): Promise<void> {
    await this.controlDb.batch([
      this.controlDb
        .prepare(
          `UPDATE clients
           SET setup_status = 'SETUP_INCOMPLETE', updated_at = datetime('now')
           WHERE client_id = ?`
        )
        .bind(clientId),
      this.controlDb
        .prepare(
          `UPDATE client_onboarding_operations
           SET stage = 'FAILED_RETRYABLE', error_message = ?, updated_at = datetime('now')
           WHERE operation_id = ?`
        )
        .bind(errorMessage, operationId)
    ]);
  }

  async createClientAtomic(params: {
    clientId: string;
    clientName: string;
    clientCode: string;
    industry: string;
    contactPerson?: string;
    contactEmail?: string;
    activeFiscalYearId?: string;
    uniqueAssetKeyHeader?: string;
    driveRootFolderId?: string;
    createdByUid: string;
    userRole: 'Owner' | 'Admin';
    shardBinding?: string;
  }): Promise<{ client: ClientEntity; isIdempotentReplay: boolean }> {
    const res = await this.initiateClientProvisioning(params);
    if (!res.isIdempotentReplay) {
      await this.finalizeClientProvisioning(params.clientId, `op_onboard_${params.clientId}`, 'NOT_REQUESTED', params.driveRootFolderId);
    }
    const updated = await this.getClient(params.clientId);
    return { client: updated || res.client, isIdempotentReplay: res.isIdempotentReplay };
  }

  async createClient(client: {
    client_id: string;
    client_name: string;
    client_code: string;
    industry: string;
    contact_person?: string;
    contact_email?: string;
    active_fiscal_year_id?: string;
    unique_asset_key_header?: string;
    created_by_uid: string;
    shard_binding?: string;
  }): Promise<void> {
    const fy = client.active_fiscal_year_id || '2026_27';
    const keyHeader = client.unique_asset_key_header || 'Asset ID';
    const shard = client.shard_binding || 'SHARD_01';

    await this.controlDb
      .prepare(
        `INSERT INTO clients (client_id, client_name, client_code, industry, contact_person, contact_email, active_fiscal_year_id, unique_asset_key_header, created_by_uid)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind(
        client.client_id,
        client.client_name,
        client.client_code,
        client.industry,
        client.contact_person || null,
        client.contact_email || null,
        fy,
        keyHeader,
        client.created_by_uid
      )
      .run();

    // Setup initial shard routing
    await this.controlDb
      .prepare(
        `INSERT INTO client_shard_routing (client_id, shard_binding, database_id, routing_status)
         VALUES (?, ?, ?, 'ACTIVE')`
      )
      .bind(client.client_id, shard, 'local-shard-id')
      .run();
  }

  async addClientMembership(membership: {
    membership_id: string;
    client_id: string;
    user_uid: string;
    role: ClientRole;
    has_review_authority?: boolean;
    can_publish?: boolean;
    can_sync_drive?: boolean;
    assigned_by_uid: string;
  }): Promise<void> {
    await this.controlDb
      .prepare(
        `INSERT INTO client_memberships (
            membership_id, client_id, user_uid, role, account_status,
            has_review_authority, can_publish, can_sync_drive, assigned_by_uid
         ) VALUES (?, ?, ?, ?, 'Active', ?, ?, ?, ?)`
      )
      .bind(
        membership.membership_id,
        membership.client_id,
        membership.user_uid,
        membership.role,
        membership.has_review_authority ? 1 : 0,
        membership.can_publish ? 1 : 0,
        membership.can_sync_drive ? 1 : 0,
        membership.assigned_by_uid
      )
      .run();
  }

  async getSystemConfig(key: string): Promise<string | null> {
    const row = await this.controlDb
      .prepare('SELECT config_value FROM system_config WHERE config_key = ?')
      .bind(key)
      .first<{ config_value: string }>();
    return row ? row.config_value : null;
  }


  // =========================================================================
  // GOVERNANCE & PERMANENT DELETION WITH TOMBSTONES
  // =========================================================================

  async deleteClientControl(
    clientId: string,
    clientCode: string,
    deletedByUid: string,
    reason?: string
  ): Promise<void> {
    const tombstoneId = `tomb_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const hashBuf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(clientId));
    const subjectHash = Array.from(new Uint8Array(hashBuf))
      .map(b => b.toString(16).padStart(2, '0'))
      .join('');

    const deleteMembershipsStmt = this.controlDb
      .prepare('DELETE FROM client_memberships WHERE client_id = ?')
      .bind(clientId);

    const deleteRoutingStmt = this.controlDb
      .prepare('DELETE FROM client_shard_routing WHERE client_id = ?')
      .bind(clientId);

    const deleteSagaStmt = this.controlDb
      .prepare('DELETE FROM client_onboarding_operations WHERE client_id = ?')
      .bind(clientId);

    const deleteClientStmt = this.controlDb
      .prepare('DELETE FROM clients WHERE client_id = ?')
      .bind(clientId);

    const tombstoneStmt = this.controlDb
      .prepare(
        `INSERT INTO deleted_identities (
           tombstone_id, subject_type, subject_id_hash, original_identifier,
           deleted_by_uid, deletion_reason, deleted_at
         ) VALUES (?, 'CLIENT', ?, ?, ?, ?, datetime('now'))`
      )
      .bind(
        tombstoneId,
        subjectHash,
        clientCode,
        deletedByUid,
        reason || 'Client permanently deleted by System Owner'
      );

    await this.controlDb.batch([
      deleteMembershipsStmt,
      deleteRoutingStmt,
      deleteSagaStmt,
      deleteClientStmt,
      tombstoneStmt
    ]);
  }

  async deleteUserControl(
    targetUid: string,
    targetEmail: string,
    deletedByUid: string,
    reason?: string
  ): Promise<void> {
    const tombstoneId = `tomb_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
    const hashBuf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(targetUid));
    const subjectHash = Array.from(new Uint8Array(hashBuf))
      .map(b => b.toString(16).padStart(2, '0'))
      .join('');

    const deleteMembershipsStmt = this.controlDb
      .prepare('DELETE FROM client_memberships WHERE user_uid = ?')
      .bind(targetUid);

    const deleteUserStmt = this.controlDb
      .prepare('DELETE FROM users WHERE uid = ?')
      .bind(targetUid);

    const tombstoneStmt = this.controlDb
      .prepare(
        `INSERT INTO deleted_identities (
           tombstone_id, subject_type, subject_id_hash, original_identifier,
           deleted_by_uid, deletion_reason, deleted_at
         ) VALUES (?, 'USER', ?, ?, ?, ?, datetime('now'))`
      )
      .bind(
        tombstoneId,
        subjectHash,
        targetEmail,
        deletedByUid,
        reason || 'User permanently deleted by System Owner'
      );

    await this.controlDb.batch([deleteMembershipsStmt, deleteUserStmt, tombstoneStmt]);
  }

}
