// HTTP Router Dispatching and Endpoint Handlers

import { Env } from '../env';
import { authenticateFirebaseRequest, verifyFirebaseTokenOnly, AuthenticationError } from '../middleware/firebaseAuth';
import { authorizeClientAccess, assertAdminOrOwner } from '../middleware/authorization';
import { resolveClientShard } from '../services/shardRouter';
import { ControlRepository } from '../repositories/controlRepository';
import { ShardRepository, FoundNotInFarEntity } from '../repositories/shardRepository';
import { SchemaService } from '../services/schemaService';
import { CountingService } from '../services/countingService';
import { ClientService } from '../services/clientService';
import { UserService } from '../services/userService';
import { EvidenceService, EvidenceConflictError, EvidenceValidationError } from '../services/evidenceService';
import { BootstrapService, BootstrapError } from '../services/bootstrapService';
import { extractIdempotencyKey } from '../utils/idempotency';
import { handleRouteError } from '../middleware/errorHandler';

export async function handleRequest(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;

  // 1. Health Probe
  if (path === '/health' && method === 'GET') {
    return new Response(
      JSON.stringify({
        status: 'UP',
        service: 'assetflow-backend-worker',
        environment: env.ENVIRONMENT || 'local',
        timestamp: new Date().toISOString()
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }
    );
  }

  // 2. All protected routes require Firebase Authentication
  if (path.startsWith('/api/v1/')) {
    // Initial System Owner Bootstrap endpoint (requires valid token, but user does not need to exist in DB yet)
    if (path === '/api/v1/system/bootstrap' && method === 'POST') {
      try {
        const tokenUser = await verifyFirebaseTokenOnly(request, env);
        const body = await request.json<any>().catch(() => ({}));
        const bootstrapService = new BootstrapService(env.CONTROL_DB);
        const res = await bootstrapService.bootstrapInitialOwner(tokenUser, env, body);
        return new Response(JSON.stringify(res), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      } catch (err) {
        if (err instanceof BootstrapError) {
          return new Response(JSON.stringify({ error: err.code, message: err.message }), {
            status: err.statusCode,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        if (err instanceof AuthenticationError) {
          return new Response(JSON.stringify({ error: 'AUTH_TOKEN_INVALID', message: err.message }), {
            status: err.statusCode,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const msg = err instanceof Error ? err.message : String(err);
        return new Response(JSON.stringify({ error: 'BOOTSTRAP_DATABASE_ERROR', message: msg }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' }
        });
      }
    }

    // Authenticated User Session and Role Resolution endpoint
    if (path === '/api/v1/system/session' && method === 'GET') {
      try {
        const tokenUser = await verifyFirebaseTokenOnly(request, env);
        const bootstrapService = new BootstrapService(env.CONTROL_DB);
        const res = await bootstrapService.getUserSession(tokenUser);
        const statusCode = res.status === 'OK' ? 200 : 404;
        return new Response(JSON.stringify(res), {
          status: statusCode,
          headers: { 'Content-Type': 'application/json' }
        });
      } catch (err) {
        if (err instanceof BootstrapError) {
          return new Response(JSON.stringify({ error: err.code, message: err.message }), {
            status: err.statusCode,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        if (err instanceof AuthenticationError) {
          return new Response(JSON.stringify({ error: 'AUTH_TOKEN_INVALID', message: err.message }), {
            status: err.statusCode,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const msg = err instanceof Error ? err.message : String(err);
        return new Response(JSON.stringify({ error: 'SESSION_ERROR', message: msg }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' }
        });
      }
    }

    try {
      const user = await authenticateFirebaseRequest(request, env);

      // Route: List Clients (Owner sees all active; Admin/Auditor see assigned active)
      if (path === '/api/v1/clients' && method === 'GET') {
        const controlRepo = new ControlRepository(env.CONTROL_DB);
        const clientService = new ClientService(controlRepo);
        const isOwner = user.globalRole === 'Owner';
        const clients = await clientService.listClientsForUser(user.uid, isOwner);
        return new Response(JSON.stringify({ clients }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Create Client (Admin/Owner only)
      if (path === '/api/v1/clients' && method === 'POST') {
        if (user.globalRole !== 'Owner' && user.globalRole !== 'Admin') {
          return new Response(JSON.stringify({ error: 'Operation requires Admin or Owner role to register new Clients' }), {
            status: 403,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        const body = await request.json<any>();
        const clientName = (body.clientName || '').trim();
        const clientCode = (body.clientCode || '').trim().toUpperCase();
        if (!clientName || !clientCode) {
          return new Response(JSON.stringify({ error: 'Client Name and Client Code are required' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        const controlRepo = new ControlRepository(env.CONTROL_DB);
        const clientService = new ClientService(controlRepo);
        const shardBinding = body.shardBinding || 'SHARD_01';
        const shardDb = env[shardBinding as keyof Env] as D1Database | undefined;
        if (!shardDb || typeof shardDb.prepare !== 'function') {
          return new Response(JSON.stringify({ error: `Shard binding '${shardBinding}' is not available` }), {
            status: 500,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const shardRepo = new ShardRepository(shardDb);

        try {
          const result = await clientService.createClientWithMembershipAndFiscalYear({
            clientId: body.clientId,
            clientName,
            clientCode,
            industry: (body.industry || '').trim(),
            contactPerson: (body.contactPerson || '').trim(),
            contactEmail: (body.contactEmail || '').trim(),
            fiscalYearLabel: (body.fiscalYearLabel || body.initialFiscalYearLabel || '2026–27').trim(),
            uniqueAssetKeyHeader: (body.uniqueAssetKeyHeader || 'Asset ID').trim(),
            driveRootFolderId: body.driveRootFolderId,
            provisionDriveFolders: body.provisionDriveFolders === true,
            createdByUid: user.uid,
            userRole: user.globalRole === 'Owner' ? 'Owner' : 'Admin',
            shardBinding
          }, shardRepo);

          return new Response(JSON.stringify({
            message: 'Client created successfully',
            clientId: result.client.client_id,
            client: result.client,
            fiscalYear: result.fiscalYear
          }), {
            status: result.isIdempotentReplay ? 200 : 201,
            headers: { 'Content-Type': 'application/json' }
          });
        } catch (err: any) {
          if (err.message && err.message.includes('already exists')) {
            return new Response(JSON.stringify({ error: err.message, code: 'CLIENT_CODE_CONFLICT' }), {
              status: 409,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          throw err;
        }
      }

      // Route: List Users (Search & Filter)
      if (path === '/api/v1/users' && method === 'GET') {
        const controlRepo = new ControlRepository(env.CONTROL_DB);
        const userService = new UserService(controlRepo);
        const query = url.searchParams.get('q') || undefined;
        const role = url.searchParams.get('role') || undefined;
        const status = url.searchParams.get('status') || undefined;
        const users = await userService.listUsers(user, { query, role, status });
        return new Response(JSON.stringify({ users }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: User Profile & Role/Status Management: /api/v1/users/:uid...
      const userMatch = path.match(/^\/api\/v1\/users\/([a-zA-Z0-9_\-]+)(\/.*)?$/);
      if (userMatch) {
        const targetUid = userMatch[1];
        const userSubPath = userMatch[2] || '';
        const controlRepo = new ControlRepository(env.CONTROL_DB);
        const userService = new UserService(controlRepo);

        if ((userSubPath === '' || userSubPath === '/') && method === 'GET') {
          const target = await userService.getUser(targetUid);
          if (!target) {
            return new Response(JSON.stringify({ error: `User '${targetUid}' not found` }), {
              status: 404,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          return new Response(JSON.stringify({ user: target }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        if (userSubPath === '/status' && method === 'PATCH') {
          const body = await request.json<any>();
          const newStatus = body.status;
          if (!newStatus || !['Active', 'Suspended'].includes(newStatus)) {
            return new Response(JSON.stringify({ error: "Invalid status. Must be 'Active' or 'Suspended'" }), {
              status: 400,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          await userService.updateUserStatus(user, targetUid, newStatus);
          return new Response(JSON.stringify({ message: `User status updated to '${newStatus}'` }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        if (userSubPath === '/role' && method === 'PATCH') {
          const body = await request.json<any>();
          const newRole = body.role;
          if (!newRole || !['Admin', 'Auditor', 'Reviewer'].includes(newRole)) {
            return new Response(JSON.stringify({ error: "Invalid role. Must be 'Admin', 'Auditor', or 'Reviewer'" }), {
              status: 400,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          await userService.updateUserRole(user, targetUid, newRole);
          return new Response(JSON.stringify({ message: `User global role updated to '${newRole}'` }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        if (userSubPath === '/delete' && method === 'POST') {
          if (user.globalRole !== 'Owner') {
            return new Response(JSON.stringify({ error: 'Forbidden: Only the System Owner can permanently delete a user' }), {
              status: 403,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          const target = await userService.getUser(targetUid);
          if (!target) {
            return new Response(JSON.stringify({ error: `User '${targetUid}' not found` }), {
              status: 404,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          if (target.global_role === 'Owner') {
            return new Response(JSON.stringify({ error: 'Security Error: The System Owner account cannot be deleted' }), {
              status: 403,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          const body = await request.json<any>().catch(() => ({}));
          const confirmEmail = (body.confirmEmail || '').trim().toLowerCase();
          if (!confirmEmail || confirmEmail !== target.email.trim().toLowerCase()) {
            return new Response(JSON.stringify({
              error: 'CONFIRMATION_REQUIRED',
              message: 'Confirmation required: Enter exact user email to confirm permanent deletion',
              requiredEmail: target.email
            }), {
              status: 400,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          await controlRepo.deleteUserControl(targetUid, target.email, user.uid, body.reason);
          return new Response(JSON.stringify({
            message: 'User account and memberships permanently deleted',
            userUid: targetUid,
            email: target.email
          }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          });
        }
      }

      // Route: Submit Access Request (Auditor)
      if (path === '/api/v1/access-requests' && method === 'POST') {
        const body = await request.json<any>();
        const reqClientId = (body.clientId || '').trim();
        const reqFyId = (body.fiscalYearId || '2026_27').trim();
        const reason = (body.reason || '').trim();
        if (!reqClientId) {
          return new Response(JSON.stringify({ error: 'clientId is required' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const controlRepo = new ControlRepository(env.CONTROL_DB);
        const { shardDb } = await resolveClientShard(reqClientId, env);
        const shardRepo = new ShardRepository(shardDb);
        const userService = new UserService(controlRepo);

        const newReq = await userService.submitAccessRequest(user, reqClientId, reqFyId, reason, shardRepo);
        return new Response(JSON.stringify({ message: 'Access request submitted', accessRequest: newReq }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: List My Access Requests
      if (path === '/api/v1/access-requests/my' && method === 'GET') {
        const queryClientId = url.searchParams.get('clientId');
        if (queryClientId) {
          const { shardDb } = await resolveClientShard(queryClientId, env);
          const shardRepo = new ShardRepository(shardDb);
          const requests = await shardRepo.listAccessRequestsForUser(user.uid);
          return new Response(JSON.stringify({ accessRequests: requests }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const shardRepo = new ShardRepository(env.SHARD_01);
        const requests = await shardRepo.listAccessRequestsForUser(user.uid);
        return new Response(JSON.stringify({ accessRequests: requests }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

    // Client-Scoped Routes: /api/v1/clients/:clientId/...
    const clientMatch = path.match(/^\/api\/v1\/clients\/([a-zA-Z0-9_\-]+)(\/.*)?$/);
    if (clientMatch) {
      const clientId = clientMatch[1];
      const subPath = clientMatch[2] || '';

      // Authorize caller membership in Client
      const membership = await authorizeClientAccess(user, clientId, env);

      // Resolve target Shard Database via Control DB
      const controlRepo = new ControlRepository(env.CONTROL_DB);
      const { shardDb } = await resolveClientShard(clientId, env);
      const shardRepo = new ShardRepository(shardDb);
      const schemaService = new SchemaService(shardRepo);
      const countingService = new CountingService(shardRepo);
      const evidenceService = new EvidenceService(shardRepo, controlRepo);

      // Route: Get Single Client Metadata
      if ((subPath === '' || subPath === '/') && method === 'GET') {
        const client = await controlRepo.getClient(clientId);
        if (!client) {
          return new Response(JSON.stringify({ error: `Client '${clientId}' not found` }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        return new Response(JSON.stringify({ client }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: List Fiscal Years for Client
      if (subPath === '/fiscal-years' && method === 'GET') {
        const fiscalYears = await shardRepo.listFiscalYears(clientId);
        return new Response(JSON.stringify({ fiscalYears }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Create Fiscal Year for Client
      if (subPath === '/fiscal-years' && method === 'POST') {
        assertAdminOrOwner(membership);
        const body = await request.json<any>();
        const fiscalYearId = (body.fiscalYearId || '').trim();
        const label = (body.label || body.fiscalYearLabel || '').trim();
        if (!fiscalYearId || !label) {
          return new Response(JSON.stringify({ error: 'fiscalYearId and label are required' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const created = await shardRepo.createFiscalYear(clientId, fiscalYearId, label);
        return new Response(JSON.stringify({ message: 'Fiscal year created successfully', fiscalYear: created }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Permanent Client Deletion (System Owner only)
      if (subPath === '/delete' && method === 'POST') {
        if (user.globalRole !== 'Owner') {
          return new Response(JSON.stringify({ error: 'Forbidden: Only the System Owner can permanently delete a client' }), {
            status: 403,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const client = await controlRepo.getClient(clientId);
        if (!client) {
          return new Response(JSON.stringify({ error: `Client '${clientId}' not found` }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const body = await request.json<any>().catch(() => ({}));
        const confirmCode = (body.confirmClientCode || '').trim();
        if (!confirmCode || (confirmCode !== client.client_code && confirmCode.toUpperCase() !== 'DELETE')) {
          return new Response(JSON.stringify({
            error: 'CONFIRMATION_REQUIRED',
            message: 'Confirmation required: Enter exact client code to confirm permanent deletion',
            requiredCode: client.client_code
          }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        await shardRepo.deleteClientShardData(clientId);
        await controlRepo.deleteClientControl(clientId, client.client_code, user.uid, body.reason);
        return new Response(JSON.stringify({
          message: 'Client and all associated records permanently deleted',
          clientId,
          clientCode: client.client_code
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: List Client Memberships (Admin/Owner)
      if (subPath === '/memberships' && method === 'GET') {
        assertAdminOrOwner(membership);
        const userService = new UserService(controlRepo);
        const memberships = await userService.listMembershipsForClient(user, clientId);
        return new Response(JSON.stringify({ memberships }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Assign User to Client (Admin/Owner)
      if (subPath === '/memberships' && method === 'POST') {
        assertAdminOrOwner(membership);
        const body = await request.json<any>();
        const targetUid = (body.userUid || body.userId || '').trim();
        const role = body.role || 'Auditor';
        if (!targetUid) {
          return new Response(JSON.stringify({ error: 'userUid is required' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const userService = new UserService(controlRepo);
        await userService.assignUserToClient(user, clientId, {
          userUid: targetUid,
          role,
          hasReviewAuthority: body.hasReviewAuthority,
          canPublish: body.canPublish,
          canSyncDrive: body.canSyncDrive
        });
        return new Response(JSON.stringify({ message: 'User assigned to Client successfully' }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Update or Revoke Client Membership
      const memberMatch = subPath.match(/^\/memberships\/([a-zA-Z0-9_\-]+)$/);
      if (memberMatch) {
        assertAdminOrOwner(membership);
        const targetMemberUid = memberMatch[1];
        const userService = new UserService(controlRepo);

        if (method === 'PATCH') {
          const body = await request.json<any>();
          await userService.assignUserToClient(user, clientId, {
            userUid: targetMemberUid,
            role: body.role || 'Auditor',
            hasReviewAuthority: body.hasReviewAuthority,
            canPublish: body.canPublish,
            canSyncDrive: body.canSyncDrive
          });
          return new Response(JSON.stringify({ message: 'Client membership updated' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        if (method === 'DELETE') {
          await userService.revokeUserFromClient(user, clientId, targetMemberUid);
          return new Response(JSON.stringify({ message: 'User removed from Client successfully' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          });
        }
      }

      // Route: List Access Requests for Client (Admin/Owner)
      if (subPath === '/access-requests' && method === 'GET') {
        assertAdminOrOwner(membership);
        const requests = await shardRepo.listAccessRequestsForClient(clientId);
        return new Response(JSON.stringify({ accessRequests: requests }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Decide Access Request (Approve / Reject)
      const decideMatch = subPath.match(/^\/access-requests\/([a-zA-Z0-9_\-]+)\/decide$/);
      if (decideMatch && method === 'POST') {
        assertAdminOrOwner(membership);
        const requestId = decideMatch[1];
        const body = await request.json<any>();
        const decision = body.decision;
        if (decision !== 'Approved' && decision !== 'Rejected') {
          return new Response(JSON.stringify({ error: "Decision must be 'Approved' or 'Rejected'" }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        const userService = new UserService(controlRepo);
        const decided = await userService.decideAccessRequest(user, clientId, requestId, decision, shardRepo);
        return new Response(JSON.stringify({ message: `Access request ${decision}`, accessRequest: decided }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Batch Import FAR Assets (Admin/Owner)
      if (subPath === '/far/import' && method === 'POST') {
        assertAdminOrOwner(membership);
        const body = await request.json<any>();
        const fiscalYearId = (body.fiscalYearId || '2026_27').trim();
        const importBatchId = (body.importBatchId || `batch_${Date.now()}`).trim();
        const rawAssets = body.assets;

        if (!Array.isArray(rawAssets) || rawAssets.length === 0) {
          return new Response(JSON.stringify({ error: "Field 'assets' must be a non-empty array" }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        if (rawAssets.length > 500) {
          return new Response(JSON.stringify({ error: 'Batch size exceeds maximum limit of 500 records per request' }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        const assetsToInsert = rawAssets.map((a: any, idx: number) => {
          const assetKey = (a.assetKey || a.asset_key || '').trim();
          const assetId = (a.assetId || a.asset_id || assetKey).toString().trim();
          const sourceRow = typeof a.sourceRow === 'number' ? a.sourceRow : (a.source_row || idx + 1);
          const rawRowJson = typeof a.rawRowJson === 'string'
            ? a.rawRowJson
            : JSON.stringify(a.rawRowJson || a.rawRowByHeader || a.rawRow || {});
          const searchText = (a.searchText || a.search_text || `${assetId} ${a.description || ''}`).trim();

          return {
            assetKey: assetKey || `${clientId}_${fiscalYearId}_${assetId}_R${sourceRow}`,
            assetId,
            sourceRow,
            sourceWorksheet: a.sourceWorksheet || a.source_worksheet || 'Count Sheet',
            sourceWorksheetIndex: a.sourceWorksheetIndex ?? 0,
            rawRowJson,
            searchText
          };
        });

        const count = await shardRepo.batchInsertFarAssets(
          clientId,
          fiscalYearId,
          importBatchId,
          user.uid,
          assetsToInsert
        );

        return new Response(JSON.stringify({
          message: 'Assets imported successfully',
          importedCount: count,
          importBatchId
        }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Paginated Search FAR Assets
      if (subPath === '/far/assets' && method === 'GET') {
        const fiscalYearId = url.searchParams.get('fiscalYearId') || '2026_27';
        const query = url.searchParams.get('q') || url.searchParams.get('query') || undefined;
        const cursor = url.searchParams.get('cursor') || undefined;
        const limitParam = url.searchParams.get('limit');
        const limit = limitParam ? parseInt(limitParam, 10) : 50;

        const result = await shardRepo.searchFarAssets({
          clientId,
          fiscalYearId,
          query,
          cursor,
          limit
        });

        return new Response(JSON.stringify(result), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Record Unlisted Asset ("Found Not In FAR")
      if (subPath === '/found-not-in-far' && method === 'POST') {
        const body = await request.json<any>();
        const tempId = (body.tempId || body.temp_id || `UNLISTED_${Date.now()}`).trim();
        const fiscalYearId = (body.fiscalYearId || body.fiscal_year_id || '2026_27').trim();
        const description = (body.description || '').trim();
        const countDate = (body.countDate || body.count_date || new Date().toISOString().slice(0, 10)).trim();
        const rawValues = body.rawValuesJson || body.raw_values_json || body.rawValues || {};
        const rawValuesJson = typeof rawValues === 'string' ? rawValues : JSON.stringify(rawValues);

        if (!description) {
          return new Response(JSON.stringify({ error: "Field 'description' is required for unlisted assets" }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        try {
          JSON.parse(rawValuesJson);
        } catch {
          return new Response(JSON.stringify({ error: "Invalid JSON in rawValuesJson" }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        const entity: FoundNotInFarEntity = {
          temp_id: tempId,
          client_id: clientId,
          fiscal_year_id: fiscalYearId,
          description,
          raw_values_json: rawValuesJson,
          auditor_uid: user.uid,
          auditor_name: user.email || user.uid,
          count_date: countDate
        };

        await shardRepo.insertFoundNotInFar(entity);

        return new Response(JSON.stringify({
          message: 'Unlisted asset recorded successfully',
          record: entity
        }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: List Unlisted Assets ("Found Not In FAR")
      if (subPath === '/found-not-in-far' && method === 'GET') {
        const fiscalYearId = url.searchParams.get('fiscalYearId') || undefined;
        const records = await shardRepo.listFoundNotInFar(clientId, fiscalYearId);
        return new Response(JSON.stringify({ records }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Submit Daily Sheet Batch (Auditor / Admin / Owner)
      if (subPath === '/daily-sheets' && method === 'POST') {
        const body = await request.json<any>();
        const batchId = (body.batchId || `batch_${Date.now()}`).trim();
        const fiscalYearId = (body.fiscalYearId || '2026_27').trim();
        const batchDate = (body.batchDate || new Date().toISOString().slice(0, 10)).trim();
        const items = Array.isArray(body.items) ? body.items : [];

        if (items.length === 0) {
          return new Response(JSON.stringify({ error: "Field 'items' must be a non-empty array of counting records" }), {
            status: 400,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        const result = await shardRepo.createOrSubmitDailyBatch({
          clientId,
          fiscalYearId,
          batchId,
          batchDate,
          auditorUid: user.uid,
          auditorName: user.email || user.uid,
          items: items.map((it: any) => ({
            recordId: it.recordId || it.record_id,
            assetKey: it.assetKey || it.asset_key,
            schemaVersionId: it.schemaVersionId || it.schema_version_id || 'default_v1',
            countDate: it.countDate || it.count_date || batchDate,
            valuesJson: typeof it.valuesJson === 'string' ? it.valuesJson : JSON.stringify(it.valuesJson || it.values || {}),
            idempotencyKey: it.idempotencyKey || it.idempotency_key
          }))
        });

        return new Response(JSON.stringify({
          message: 'Daily sheet submitted successfully',
          ...result
        }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: List Daily Sheets (Review Queue for Admin / Submission History for Auditor)
      if (subPath === '/daily-sheets' && method === 'GET') {
        const status = url.searchParams.get('status') || undefined;
        const mine = url.searchParams.get('mine') === 'true';

        // Auditors only ever see their own submissions
        // Admins and Owners see all client submissions, or filter by mine=true
        const auditorUid = (membership.role === 'Auditor' || mine) ? user.uid : undefined;

        const dailySheets = await shardRepo.listDailyBatches(clientId, auditorUid, status);
        return new Response(JSON.stringify({ dailySheets }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Get Daily Sheet Detail (with counting records)
      const dailySheetDetailMatch = subPath.match(/^\/daily-sheets\/([a-zA-Z0-9_\-]+)$/);
      if (dailySheetDetailMatch && method === 'GET') {
        const batchId = dailySheetDetailMatch[1];
        const detail = await shardRepo.getDailyBatch(clientId, batchId);
        if (!detail) {
          return new Response(JSON.stringify({ error: `Daily sheet '${batchId}' not found` }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        // Auditor can only view their own sheet
        if (membership.role === 'Auditor' && detail.batch.auditor_uid !== user.uid) {
          return new Response(JSON.stringify({ error: 'Access denied: You can only view your own daily sheets' }), {
            status: 403,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        return new Response(JSON.stringify(detail), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Return Daily Sheet with Remarks (Admin/Owner review)
      const dailySheetReviewMatch = subPath.match(/^\/daily-sheets\/([a-zA-Z0-9_\-]+)\/review$/);
      if (dailySheetReviewMatch && method === 'POST') {
        assertAdminOrOwner(membership);
        const batchId = dailySheetReviewMatch[1];
        const body = await request.json<any>().catch(() => ({}));
        const remarks = (body.remarks || '').trim();

        const result = await shardRepo.reviewDailyBatch(clientId, batchId, user.uid, 'REJECT', remarks);
        return new Response(JSON.stringify({
          message: 'Daily sheet returned with remarks',
          ...result
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Approve Daily Sheet for Master Sync (Admin/Owner review)
      const dailySheetApproveMatch = subPath.match(/^\/daily-sheets\/([a-zA-Z0-9_\-]+)\/approve$/);
      if (dailySheetApproveMatch && method === 'POST') {
        assertAdminOrOwner(membership);
        const batchId = dailySheetApproveMatch[1];
        const body = await request.json<any>().catch(() => ({}));
        const remarks = (body.remarks || '').trim();

        const result = await shardRepo.reviewDailyBatch(clientId, batchId, user.uid, 'APPROVE', remarks);
        return new Response(JSON.stringify({
          message: 'Daily sheet approved for master sync',
          ...result
        }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Get Master Workbook State & Latest Job
      if (subPath === '/master-workbook/state' && method === 'GET') {
        const fiscalYearId = url.searchParams.get('fiscalYearId') || '2026_27';
        const result = await shardRepo.getMasterWorkbookState(clientId, fiscalYearId);
        return new Response(JSON.stringify(result), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Acquire Master Sync Lease Lock (Admin/Owner)
      if (subPath === '/master-workbook/acquire-lease' && method === 'POST') {
        assertAdminOrOwner(membership);
        const body = await request.json<any>();
        const fiscalYearId = (body.fiscalYearId || '2026_27').trim();
        const jobId = (body.jobId || `job_${Date.now()}`).trim();
        const batchId = body.batchId ? String(body.batchId).trim() : undefined;
        const leaseDurationMs = typeof body.leaseDurationMs === 'number' ? body.leaseDurationMs : 60000;

        const result = await shardRepo.acquireMasterSyncLease({
          clientId,
          fiscalYearId,
          userUid: user.uid,
          jobId,
          batchId,
          leaseDurationMs
        });

        return new Response(JSON.stringify(result), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Release Master Sync Lease Lock (Admin/Owner)
      if (subPath === '/master-workbook/release-lease' && method === 'POST') {
        assertAdminOrOwner(membership);
        const body = await request.json<any>();
        const fiscalYearId = (body.fiscalYearId || '2026_27').trim();
        const jobId = (body.jobId || '').trim();
        const status = body.status === 'FAILED' ? 'FAILED' : 'COMPLETED';
        const errorMessage = body.errorMessage ? String(body.errorMessage).trim() : undefined;
        const fileChecksum = body.fileChecksum ? String(body.fileChecksum).trim() : undefined;
        const driveFileId = body.driveFileId ? String(body.driveFileId).trim() : undefined;
        const submissionId = body.submissionId ? String(body.submissionId).trim() : undefined;

        const result = await shardRepo.releaseMasterSyncLease({
          clientId,
          fiscalYearId,
          jobId,
          status,
          errorMessage,
          fileChecksum,
          driveFileId,
          submissionId
        });

        return new Response(JSON.stringify(result), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Get Schema Pinned to Specific Audit Cycle
      const cycleSchemaMatch = subPath.match(/^\/audit-cycles\/([a-zA-Z0-9_\-]+)\/schema$/);
      if (cycleSchemaMatch && method === 'GET') {
        const cycleId = cycleSchemaMatch[1];
        const schema = await schemaService.getSchemaForCycle(clientId, cycleId);

        if (!schema) {
          return new Response(
            JSON.stringify({
              message: `No schema version pinned or published for client '${clientId}' in cycle '${cycleId}'`,
              schema: null
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }
          );
        }

        return new Response(JSON.stringify({ schema }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Get Active Published Schema (General Fallback)
      if (subPath === '/schemas/active' && method === 'GET') {
        const fiscalYear = url.searchParams.get('fiscalYear') || '2026_27';
        const schema = await schemaService.getActiveSchema(clientId, fiscalYear);

        if (!schema) {
          return new Response(
            JSON.stringify({
              message: `No active published schema for client '${clientId}' in cycle '${fiscalYear}'`,
              schema: null
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } }
          );
        }

        return new Response(JSON.stringify({ schema }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Analyze Uploaded Excel Headers
      if (subPath === '/schemas/analyze-headers' && method === 'POST') {
        assertAdminOrOwner(membership);
        const body = await request.json<{ headers: string[]; rows: (string | null | undefined)[][] }>();

        const analysis = schemaService.analyzeWorkbookColumns(body.headers || [], body.rows || []);
        return new Response(JSON.stringify({ results: analysis }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Publish Schema Version
      if (subPath === '/schemas/publish' && method === 'POST') {
        assertAdminOrOwner(membership);
        const body = await request.json<any>();

        body.publishedByUid = user.uid;
        await schemaService.publishSchema(body);

        return new Response(JSON.stringify({ message: 'Schema version published successfully' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Submit Counting Record Scoped to Specific Audit Cycle
      const cycleCountPostMatch = subPath.match(/^\/audit-cycles\/([a-zA-Z0-9_\-]+)\/counts$/);
      if (cycleCountPostMatch && method === 'POST') {
        const cycleId = cycleCountPostMatch[1];
        const body = await request.json<any>();
        const idempotencyKey = extractIdempotencyKey(request) || body.idempotencyKey;

        const result = await countingService.submitCount(
          clientId,
          cycleId,
          {
            assetKey: body.assetKey,
            schemaVersionId: body.schemaVersionId,
            countDate: body.countDate || new Date().toISOString().slice(0, 10),
            batchId: body.batchId,
            idempotencyKey,
            values: body.values || {}
          },
          user,
          membership
        );

        return new Response(JSON.stringify(result), {
          status: result.isIdempotentReplay ? 200 : 201,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: List Photos for Specific Asset (must precede single asset route)
      const cycleAssetPhotosMatch = subPath.match(/^\/audit-cycles\/([a-zA-Z0-9_\-]+)\/assets\/([a-zA-Z0-9_\-\.\:\/]+)\/photos$/);
      if (cycleAssetPhotosMatch && method === 'GET') {
        const cycleId = cycleAssetPhotosMatch[1];
        const assetKey = cycleAssetPhotosMatch[2];
        const photos = await evidenceService.listEvidenceForAsset(clientId, cycleId, assetKey);
        return new Response(JSON.stringify({ photos }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Get FAR Asset Baseline Scoped to Specific Audit Cycle
      const cycleAssetMatch = subPath.match(/^\/audit-cycles\/([a-zA-Z0-9_\-]+)\/assets\/([a-zA-Z0-9_\-\.\:\/]+)$/);
      if (cycleAssetMatch && method === 'GET') {
        const cycleId = cycleAssetMatch[1];
        const assetKey = cycleAssetMatch[2];
        const asset = await shardRepo.getFarAsset(clientId, cycleId, assetKey);

        if (!asset) {
          return new Response(JSON.stringify({ error: `FAR asset '${assetKey}' not found in cycle '${cycleId}'` }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        return new Response(JSON.stringify({ asset }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Register Photo Evidence Scoped to Specific Audit Cycle
      const cyclePhotoPostMatch = subPath.match(/^\/audit-cycles\/([a-zA-Z0-9_\-]+)\/photos$/);
      if (cyclePhotoPostMatch && method === 'POST') {
        const cycleId = cyclePhotoPostMatch[1];
        try {
          const body = await request.json<any>();
          const result = await evidenceService.registerEvidence(clientId, cycleId, body, user, membership);
          return new Response(JSON.stringify(result), {
            status: result.isIdempotentReplay ? 200 : 201,
            headers: { 'Content-Type': 'application/json' }
          });
        } catch (err: any) {
          if (err instanceof EvidenceValidationError) {
            return new Response(JSON.stringify({ error: err.message, errors: err.errors }), {
              status: 400,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          if (err instanceof EvidenceConflictError) {
            return new Response(JSON.stringify({ error: err.message }), {
              status: 409,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          throw err;
        }
      }

      // Route: List Photos for Specific Audit Cycle
      if (cyclePhotoPostMatch && method === 'GET') {
        const cycleId = cyclePhotoPostMatch[1];
        const limit = parseInt(url.searchParams.get('limit') || '100', 10);
        const photos = await evidenceService.listEvidenceForCycle(clientId, cycleId, limit);
        return new Response(JSON.stringify({ photos }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }


      // Route: Get Single Photo Evidence by photoId
      const cyclePhotoGetMatch = subPath.match(/^\/audit-cycles\/([a-zA-Z0-9_\-]+)\/photos\/([a-zA-Z0-9_\-]+)$/);
      const photoGetMatch = subPath.match(/^\/photos\/([a-zA-Z0-9_\-]+)$/);
      if ((cyclePhotoGetMatch || photoGetMatch) && method === 'GET') {
        const photoId = cyclePhotoGetMatch ? cyclePhotoGetMatch[2] : photoGetMatch![1];
        const photo = await evidenceService.getEvidence(clientId, photoId);
        if (!photo) {
          return new Response(JSON.stringify({ error: `Photo with ID '${photoId}' not found for client '${clientId}'` }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' }
          });
        }
        return new Response(JSON.stringify({ photo }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Register Photo Evidence Generic /photos
      if (subPath === '/photos' && method === 'POST') {
        try {
          const body = await request.json<any>();
          const cycleId = body.cycleId || url.searchParams.get('fiscalYear') || '2026_27';
          const result = await evidenceService.registerEvidence(clientId, cycleId, body, user, membership);
          return new Response(JSON.stringify(result), {
            status: result.isIdempotentReplay ? 200 : 201,
            headers: { 'Content-Type': 'application/json' }
          });
        } catch (err: any) {
          if (err instanceof EvidenceValidationError) {
            return new Response(JSON.stringify({ error: err.message, errors: err.errors }), {
              status: 400,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          if (err instanceof EvidenceConflictError) {
            return new Response(JSON.stringify({ error: err.message }), {
              status: 409,
              headers: { 'Content-Type': 'application/json' }
            });
          }
          throw err;
        }
      }

      // Route: Submit Counting Record
      if (subPath === '/counts' && method === 'POST') {
        const body = await request.json<any>();
        const fiscalYear = body.fiscalYearId || url.searchParams.get('fiscalYear') || '2026_27';
        const idempotencyKey = extractIdempotencyKey(request) || body.idempotencyKey;

        const result = await countingService.submitCount(
          clientId,
          fiscalYear,
          {
            assetKey: body.assetKey,
            schemaVersionId: body.schemaVersionId,
            countDate: body.countDate || new Date().toISOString().slice(0, 10),
            batchId: body.batchId,
            idempotencyKey,
            values: body.values || {}
          },
          user,
          membership
        );

        return new Response(JSON.stringify(result), {
          status: result.isIdempotentReplay ? 200 : 201,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Get Single Counting Record
      const countMatch = subPath.match(/^\/counts\/([a-zA-Z0-9_\-\.\:\/]+)$/);
      if (countMatch && method === 'GET') {
        const assetKey = countMatch[1];
        const record = await shardRepo.getCountingRecord(clientId, assetKey);

        if (!record) {
          return new Response(JSON.stringify({ error: 'Counting record not found' }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        return new Response(JSON.stringify({ record }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }

      // Route: Get FAR Asset Baseline
      const assetMatch = subPath.match(/^\/assets\/([a-zA-Z0-9_\-\.\:\/]+)$/);
      if (assetMatch && method === 'GET') {
        const assetKey = assetMatch[1];
        const fiscalYear = url.searchParams.get('fiscalYear') || '2026_27';
        const asset = await shardRepo.getFarAsset(clientId, fiscalYear, assetKey);

        if (!asset) {
          return new Response(JSON.stringify({ error: 'FAR asset not found' }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' }
          });
        }

        return new Response(JSON.stringify({ asset }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        });
      }
    }
  } catch (err) {
    return handleRouteError(err);
  }
}

  return new Response(JSON.stringify({ error: `Not Found: ${method} ${path}` }), {
    status: 404,
    headers: { 'Content-Type': 'application/json' }
  });
}
