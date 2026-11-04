# AssetFlow Backend Worker (Cloudflare Workers & D1)

This package contains the local Cloudflare Worker backend for the AssetFlow Fixed Asset Verification and Counting System (`com.aistudio.assetverify.faaudit`).

## Architecture Overview
- **Runtime:** Cloudflare Workers (TypeScript)
- **Control DB (`CONTROL_DB`):** Global user authentication, tenant registry, single authoritative client memberships, and client-to-shard routing.
- **Data Shard (`SHARD_01` ... `SHARD_09`):** Client-specific Fixed Asset Registers (FAR), dynamic input schema definitions, dropdown options, and dynamic counting records.
- **Dynamic Input Schema:** Zero hardcoded 29-field forms. Dynamic schema is analyzed from uploaded Master Excel workbooks and configured by Admins.
- **Authentication:** Firebase JWT verification via edge-compatible `jose` library + real-time Control DB account revocation check.

## Directory Structure
- `src/` — Worker entrypoint, routes, middleware, services, repositories, domain models, and utilities.
- `migrations/control/` — Control database DDL migrations.
- `migrations/shard/` — Data shard DDL migrations.
- `tests/` — Automated test suite covering Control DB, Data Shard, Dynamic Schema, Multi-tenancy, Counting Records, and Auth.
- `docs/` — Architecture, database schema, and API contracts documentation.

## Local Commands
```bash
# Run automated test suite
npm test

# Type-check TypeScript code
npm run typecheck

# Start local worker dev server
npm run dev

# Run local D1 migrations using Wrangler
npm run db:migrate:control:local
npm run db:migrate:shard:local
```
