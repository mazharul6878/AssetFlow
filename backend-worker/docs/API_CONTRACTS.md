# AssetFlow Backend API Contracts

All endpoints are hosted at `https://<worker-domain>/api/v1` and require an `Authorization: Bearer <Firebase_JWT>` header (except `/health`).

## 1. System Health
- **`GET /health`**
- **Auth:** None
- **Response (200 OK):**
```json
{
  "status": "UP",
  "service": "assetflow-backend-worker",
  "environment": "local",
  "timestamp": "2026-11-01T00:30:00.000Z"
}
```

## 2. Client Management
- **`POST /api/v1/clients`**
- **Auth:** Admin or System Owner
- **Request Body:**
```json
{
  "clientId": "client_walton",
  "clientName": "Walton Hi-Tech Industries PLC",
  "clientCode": "WLT",
  "industry": "Consumer Electronics",
  "contactPerson": "Audit Coordinator",
  "contactEmail": "audit@walton.com",
  "shardBinding": "SHARD_01"
}
```
- **Response (201 Created):**
```json
{
  "message": "Client created successfully",
  "clientId": "client_walton"
}
```

## 3. Dynamic Schema Management
- **`GET /api/v1/clients/{clientId}/audit-cycles/{cycleId}/schema`**
- **Auth:** Any authorized member of Client
- **Description:** Returns the exact schema version pinned to the specified Audit Cycle. If the schema was subsequently archived after cycle start, it remains fully accessible and operational for this cycle.
- **Response (200 OK):**
```json
{
  "schema": {
    "schemaVersionId": "sch_wlt_fy26_v1",
    "clientId": "client_walton",
    "fiscalYearId": "2026_27",
    "versionNumber": 1,
    "status": "PUBLISHED",
    "sourceWorkbookName": "Walton_FAR_2026.xlsx",
    "fields": [
      {
        "fieldId": "fld_cond_01",
        "fieldKey": "condition",
        "displayLabel": "Physical Condition",
        "inputMode": "DROPDOWN",
        "dataType": "SINGLE_SELECT",
        "isRequired": true,
        "sortOrder": 1,
        "options": [
          { "optionId": "opt_good", "optionLabel": "Good", "optionValue": "Good" },
          { "optionId": "opt_damaged", "optionLabel": "Damaged", "optionValue": "Damaged" }
        ]
      }
    ]
  }
}
```

- **`GET /api/v1/clients/{clientId}/schemas/active?fiscalYear=2026_27`**
- **Auth:** Any authorized member of Client
- **Description:** General fallback to fetch the latest published schema for a fiscal year.

- **Response (200 OK):**
```json
{
  "schema": {
    "schemaVersionId": "sch_wlt_fy26_v1",
    "clientId": "client_walton",
    "fiscalYearId": "2026_27",
    "versionNumber": 1,
    "status": "PUBLISHED",
    "sourceWorkbookName": "Walton_FAR_2026.xlsx",
    "fields": [
      {
        "fieldId": "fld_cond_01",
        "fieldKey": "condition",
        "displayLabel": "Physical Condition",
        "inputMode": "DROPDOWN",
        "dataType": "SINGLE_SELECT",
        "isRequired": true,
        "sortOrder": 1,
        "options": [
          { "optionId": "opt_good", "optionLabel": "Good", "optionValue": "Good" },
          { "optionId": "opt_damaged", "optionLabel": "Damaged", "optionValue": "Damaged" }
        ]
      }
    ]
  }
}
```

- **`POST /api/v1/clients/{clientId}/schemas/analyze-headers`**
- **Auth:** Admin of Client
- **Request Body:**
```json
{
  "headers": ["Asset ID", "Description", "Location", "Tag Condition"],
  "rows": [
    ["00101", "CNC Lathe", "Building 1", ""],
    ["00102", "Milling Drill", "Building 1", ""]
  ]
}
```
- **Response (200 OK):**
```json
{
  "results": [
    {
      "headerText": "Asset ID",
      "columnIndex": 0,
      "columnLetter": "A",
      "nonBlankCount": 2,
      "totalRows": 2,
      "classification": "FIXED_IMPORTED",
      "isSparse": false
    },
    {
      "headerText": "Tag Condition",
      "columnIndex": 3,
      "columnLetter": "D",
      "nonBlankCount": 0,
      "totalRows": 2,
      "classification": "EMPTY_HEADER_PENDING_CONFIGURATION",
      "isSparse": false
    }
  ]
}
```

- **`POST /api/v1/clients/{clientId}/schemas/publish`**
- **Auth:** Admin with `canPublish=true`
- **Request Body:** Full `DynamicSchemaVersion` JSON object.
- **Response (200 OK):** `{"message": "Schema version published successfully"}`

## 4. Counting Verification Submissions
- **`POST /api/v1/clients/{clientId}/audit-cycles/{cycleId}/counts`** (Preferred Cycle-Scoped Route)
- **`POST /api/v1/clients/{clientId}/counts`** (Legacy Fallback)
- **Headers:** `Idempotency-Key: <UUID>`
- **Auth:** Auditor / Admin of Client
- **Conflict Prevention:** If an asset in the same cycle has already been verified by another auditor, rejects with HTTP 409 Conflict. Same-auditor retry with same `Idempotency-Key` returns HTTP 200 with `isIdempotentReplay: true`.
- **Request Body:**
```json
{
  "assetKey": "wlt_2026_27_000101_R2",
  "schemaVersionId": "sch_wlt_fy26_v1",
  "countDate": "2026-11-01",
  "batchId": "batch_morning_01",
  "values": {
    "fld_cond_01": "Good",
    "fld_qty_02": 1,
    "fld_location_03": "Building 1, Room 102"
  }
}
```
- **Response (201 Created):**
```json
{
  "recordId": "rec_0a1b2c3d4e5f6789",
  "status": "SUBMITTED",
  "isIdempotentReplay": false
}
```
*(If replayed with the same `Idempotency-Key`, returns HTTP 200 with `isIdempotentReplay: true`)*.
