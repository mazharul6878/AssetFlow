// Service for Client (Tenant) Operations and Routing Assignment

import { ControlRepository, ClientEntity } from '../repositories/controlRepository';
import { ShardRepository } from '../repositories/shardRepository';
import { Env } from '../env';

export class ClientService {
  constructor(private controlRepo: ControlRepository) {}

  async getClient(clientId: string): Promise<ClientEntity | null> {
    return this.controlRepo.getClient(clientId);
  }

  async listClientsForUser(userUid: string, isOwner: boolean): Promise<ClientEntity[]> {
    return this.controlRepo.listClientsForUser(userUid, isOwner);
  }

  async createClient(client: {
    clientId: string;
    clientName: string;
    clientCode: string;
    industry: string;
    contactPerson?: string;
    contactEmail?: string;
    activeFiscalYearId?: string;
    uniqueAssetKeyHeader?: string;
    createdByUid: string;
    shardBinding?: string;
  }): Promise<void> {
    await this.controlRepo.createClient({
      client_id: client.clientId,
      client_name: client.clientName,
      client_code: client.clientCode,
      industry: client.industry,
      contact_person: client.contactPerson,
      contact_email: client.contactEmail,
      active_fiscal_year_id: client.activeFiscalYearId,
      unique_asset_key_header: client.uniqueAssetKeyHeader,
      created_by_uid: client.createdByUid,
      shard_binding: client.shardBinding || 'SHARD_01'
    });
  }

  async createClientWithMembershipAndFiscalYear(
    params: {
      clientId?: string;
      clientName: string;
      clientCode: string;
      industry: string;
      contactPerson?: string;
      contactEmail?: string;
      fiscalYearLabel?: string;
      uniqueAssetKeyHeader?: string;
      driveRootFolderId?: string;
      provisionDriveFolders?: boolean;
      createdByUid: string;
      userRole: 'Owner' | 'Admin';
      shardBinding?: string;
    },
    shardRepo: ShardRepository
  ): Promise<{
    client: ClientEntity;
    fiscalYear: {
      fiscalYearId: string;
      fiscal_year_id: string;
      label: string;
      isClosed: boolean;
      is_closed: number;
    };
    isIdempotentReplay: boolean;
  }> {
    const cleanCode = params.clientCode.trim().toUpperCase();
    const cleanName = params.clientName.trim();
    const fyLabel = (params.fiscalYearLabel || '2026–27').trim();
    const fyId = fyLabel.replace(/[^0-9A-Za-z]/g, '_').replace(/^_+|_+$/g, '') || '2026_27';
    const clientId = params.clientId && params.clientId.trim().length > 0
      ? params.clientId.trim()
      : `${cleanCode.toLowerCase().replace(/[^a-z0-9]/g, '')}_${Date.now().toString().slice(-6)}`;

    const driveStatus = params.driveRootFolderId
      ? 'COMPLETED'
      : (params.provisionDriveFolders ? 'PENDING_AUTHORIZATION' : 'NOT_REQUESTED');

    const operationId = `op_onboard_${clientId}`;

    // Step 1: Initiate provisioning in Control DB
    const { client, isIdempotentReplay } = await this.controlRepo.initiateClientProvisioning({
      clientId,
      clientName: cleanName,
      clientCode: cleanCode,
      industry: params.industry,
      contactPerson: params.contactPerson,
      contactEmail: params.contactEmail,
      activeFiscalYearId: fyId,
      uniqueAssetKeyHeader: params.uniqueAssetKeyHeader || 'Asset ID',
      driveRootFolderId: params.driveRootFolderId,
      driveProvisioningStatus: driveStatus,
      createdByUid: params.createdByUid,
      userRole: params.userRole,
      shardBinding: params.shardBinding || 'SHARD_01',
      fiscalYearLabel: fyLabel
    });

    if (isIdempotentReplay) {
      return {
        client,
        fiscalYear: {
          fiscalYearId: fyId,
          fiscal_year_id: fyId,
          label: fyLabel,
          isClosed: false,
          is_closed: 0
        },
        isIdempotentReplay: true
      };
    }

    try {
      // Step 2: Populate initial fiscal year in assigned Shard DB (idempotent)
      await shardRepo.createFiscalYear(client.client_id, fyId, fyLabel);

      // Step 3: Finalize provisioning in Control DB
      await this.controlRepo.finalizeClientProvisioning(client.client_id, operationId, driveStatus, params.driveRootFolderId);

      const finalized = await this.controlRepo.getClient(client.client_id);
      return {
        client: finalized || client,
        fiscalYear: {
          fiscalYearId: fyId,
          fiscal_year_id: fyId,
          label: fyLabel,
          isClosed: false,
          is_closed: 0
        },
        isIdempotentReplay: false
      };
    } catch (shardError: any) {
      // Step 4: On shard failure, preserve client in SETUP_INCOMPLETE state for safe retry
      await this.controlRepo.failClientProvisioning(client.client_id, operationId, shardError.message || 'Shard fiscal year creation failed');
      throw shardError;
    }
  }
}

