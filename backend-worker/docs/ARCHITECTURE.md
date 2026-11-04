# AssetFlow Cloudflare Worker & D1 Sharded Architecture

## 1. System Overview
AssetFlow utilizes a hybrid architecture:
- **Authentication:** Firebase Authentication (Spark plan) establishes user identity via RS256 JWT tokens.
- **Backend Edge Compute:** Cloudflare Workers provides an edge-native, sub-10ms latency REST API.
- **Control Database (`CONTROL_DB`):** A centralized Cloudflare D1 database storing global user identities, tenant registry, single authoritative client memberships, and client-to-shard routing.
- **Data Shards (`SHARD_01` ... `SHARD_09`):** Distributed Cloudflare D1 databases hosting client-scoped Fixed Asset Registers (FAR), dynamic input schema definitions, dropdown options, and physical counting verification records.
- **Offline Storage:** Android Room database (`OfflineSyncDatabase.kt`) acts as an offline write buffer and sync queue.

## 2. Multi-Database Sharding Topology
```
                             [ Android Client ]
                                     |
                          HTTPS Bearer Firebase JWT
                                     v
                       [ Cloudflare Worker Edge API ]
                                     |
               +---------------------+---------------------+
               |                                           |
               v                                           v
    [ CONTROL_DB (D1) ]                          [ DATA SHARD (D1) ]
  - users & global profiles                     - input_schema_versions
  - clients (tenant registry)                   - input_field_definitions
  - client_memberships (authoritative roles)    - input_field_options
  - client_shard_routing (mapping to shard)     - far_assets (JSON baseline)
  - system_config & deletion_audit_events       - counting_records (values_json)
                                                - daily_batches & submissions
```

### Routing and Tenancy Rules
1. **Zero Client Trust:** The client never supplies the target shard identifier.
2. **Control DB Shard Routing:** Every client request queries `client_shard_routing` in `CONTROL_DB` to resolve the assigned `shard_binding`.
3. **Fail-Closed Allowlist:** The Worker matches the candidate binding against an explicit compile-time allowlist (`SHARD_01` ... `SHARD_09`). Unmapped or unauthorized bindings fail closed immediately.
4. **Single Authoritative Membership:** Client permissions are evaluated strictly against `client_memberships` in `CONTROL_DB`. No competing role assignments are stored in data shards.

## 3. Dynamic Input Schema Philosophy
- **No Fixed 29-Field Count Sheet:** Clients upload diverse Fixed Asset Registers with arbitrary headers (e.g., 8, 14, 25, or 40 columns).
- **Rule A (Uninitialized State):** Before an initial Master Input Sheet is uploaded, no client schema exists and no default form is rendered.
- **Rule B (Excel Header Analysis):** Column headers and data cells are inspected upon upload.
- **Rule C (Populated Headers):** Classified as `FIXED_IMPORTED` (read-only baseline). Can be promoted by Admin into an Auditor Input field.
- **Rule D (Completely Empty Headers):** Classified as `EMPTY_HEADER_PENDING_CONFIGURATION` awaiting Admin routing (Manual, Dropdown, Auto, Hidden). Sparse columns are preserved as populated.
- **Rule E (Custom Fields):** Admins can create independent audit verification fields not present in the original Excel file.

## 4. Option B Storage Model (values_json)
To stay strictly within the Cloudflare D1 Free Tier limit of **100,000 row writes per day account-wide**:
- Counting verification values are stored in a compact, structured `values_json` column within `counting_records`.
- Writes per 10k asset verification audit: **10,000 row writes** (vs 130,000 row writes under Option A EAV).
- Preserves 90% of the daily account quota for operational headroom.
