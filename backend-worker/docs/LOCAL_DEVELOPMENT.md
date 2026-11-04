# AssetFlow Local Development Guide

## Prerequisites
- Node.js v20+ (Node v26.13.0 verified)
- npm v10+ (npm 11.20.0 verified)
- Wrangler CLI (included in devDependencies)

## Setup
```bash
cd backend-worker
npm install
```

## Running Automated Tests
```bash
# Execute full Vitest test suite (7 suites, 33 tests)
npm test

# Run tests in watch mode
npm run test:watch
```

## Type Checking
```bash
# Verify TypeScript strict type safety without emitting JS
npm run typecheck
```

## Applying Local D1 Migrations
Cloudflare Wrangler provides a local SQLite emulation directory in `.wrangler/state/v3/d1`.

```bash
# Apply Control Database Migration locally
npm run db:migrate:control:local

# Apply Data Shard 01 Migration locally
npm run db:migrate:shard:local
```

## Starting Local Worker Dev Server
```bash
# Starts local development worker on http://127.0.0.1:8787
npm run dev
```

Test health check:
```bash
curl http://127.0.0.1:8787/health
```
