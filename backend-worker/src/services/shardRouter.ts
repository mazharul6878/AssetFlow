// Type-Safe Shard Router with Control DB Lookup and Fail-Closed Binding Allowlist

import { Env, ShardBindingName, VALID_SHARD_BINDINGS } from '../env';

export class ShardRoutingError extends Error {
  constructor(message: string, public statusCode: number = 500) {
    super(message);
    this.name = 'ShardRoutingError';
  }
}

export interface ClientShardRoute {
  clientId: string;
  shardBinding: ShardBindingName;
  databaseId: string;
  routingStatus: 'ACTIVE' | 'READ_ONLY' | 'MIGRATING' | 'DELETING';
}

/**
 * Resolves the target D1 Data Shard binding exclusively from trusted Control DB records.
 * Fails closed if the shard binding is not in the explicit allowlist or unconfigured.
 */
export async function resolveClientShard(
  clientId: string,
  env: Env
): Promise<{ route: ClientShardRoute; shardDb: D1Database }> {
  // 1. Query Control DB client_shard_routing table
  const routeRow = await env.CONTROL_DB.prepare(
    `SELECT client_id, shard_binding, database_id, routing_status
     FROM client_shard_routing
     WHERE client_id = ?`
  )
    .bind(clientId)
    .first<{
      client_id: string;
      shard_binding: string;
      database_id: string;
      routing_status: string;
    }>();

  if (!routeRow) {
    throw new ShardRoutingError(
      `No shard route mapped in Control Database for Client '${clientId}'`,
      500
    );
  }

  if (routeRow.routing_status === 'DELETING') {
    throw new ShardRoutingError(`Client '${clientId}' is currently in deletion lifecycle`, 410);
  }

  const bindingCandidate = routeRow.shard_binding as ShardBindingName;

  // 2. Enforce Explicit Allowlist Check
  const configuredAllowlist = (env.SHARD_ALLOWLIST || '')
    .split(',')
    .map(s => s.trim().toUpperCase())
    .filter(Boolean);

  const isAllowed =
    VALID_SHARD_BINDINGS.includes(bindingCandidate) &&
    (configuredAllowlist.length === 0 || configuredAllowlist.includes(bindingCandidate));

  if (!isAllowed) {
    throw new ShardRoutingError(
      `Shard binding '${bindingCandidate}' for Client '${clientId}' is not in the authorized allowlist`,
      500
    );
  }

  // 3. Acquire Binding on Env Object (Fail Closed)
  const shardDb = env[bindingCandidate] as D1Database | undefined;

  if (!shardDb || typeof shardDb.prepare !== 'function') {
    throw new ShardRoutingError(
      `Authorized shard binding '${bindingCandidate}' is not configured or bound in Worker environment`,
      500
    );
  }

  return {
    route: {
      clientId: routeRow.client_id,
      shardBinding: bindingCandidate,
      databaseId: routeRow.database_id,
      routingStatus: routeRow.routing_status as ClientShardRoute['routingStatus']
    },
    shardDb
  };
}
