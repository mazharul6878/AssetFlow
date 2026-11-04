# AssetFlow — Complete Original Application Plan & Updated Implementation Architecture

> **Document Purpose & Structure**: This single, comprehensive Markdown document preserves **100% of the Original Complete AssetFlow Application Plan** (Part I-A: *AssetFlow Detailed Functional Planning & UX Specification v1.0*, including all 17 functional sections, Definition of Done, and Tables 1–20; and Part I-B: *Original Complete 18-Section Application & Architecture Plan*, including product vision, complete feature list, modules, user journeys, navigation, UI/UX requirements, 29-column dynamic schema, role permissions, Owner-controlled Admin governance, permanent Client/User deletion workflows, reporting requirements, and roadmap) as the authoritative foundation, supplemented by **Updated Implementation Sections (Sections II–XII)** reflecting all finalized technical decisions (**A through I**).

---

## Table of Contents

- **I. Original Complete Application Plan (Preserved in Full)**
  - **Part I-A: AssetFlow Detailed Functional Planning & UX Specification (Version 1.0 — Complete Text & Tables 1–20)**
  - **Part I-B: Original Complete 18-Section Application & Architecture Plan (Preserved with Updated Technical Decisions)**
- **II. Verified Existing Implementation and Gap Analysis**
- **III. Updated Technical Architecture and Data-Storage Responsibilities (Decisions A–I)**
- **IV. Implementation Design for Each Original Module (Full Traceability)**
- **V. Database Schema, Security Rules and Mandatory Trusted Backend**
- **VI. Google Drive Master Workbook Synchronization (In-Place Patching, Concurrency & Crash Recovery)**
- **VII. Migration and Compatibility Plan (Development vs. Blank Production Separation)**
- **VIII. Phased Implementation Roadmap**
- **IX. Testing and Acceptance Criteria**
- **X. Deployment, Configuration, Backup and Recovery**
- **XI. Outstanding Decisions and Implementation Readiness Checklist**
- **XII. Validation Summary (Preservation Confirmation, Updated Sections, Module Traceability Matrix & Prerequisites)**

---

# I. Original Complete Application Plan (Preserved in Full)

## Part I-A: AssetFlow Detailed Functional Planning & UX Specification (Version 1.0)

**ASSETFLOW — Detailed Functional Planning & UX Specification**  
**Fixed Asset Verification System**  
*Welcome → Login → Client Selection → Admin/Auditor Workspaces → Asset Counting → Review & Approval → Google Drive*  
*Version 1.0 | 29 October 2026*  
*A standalone reference for product owners, UI/UX designers, developers, testers and administrators.*

### Document purpose and how to use it
This document describes how each AssetFlow screen, feature, button, icon and workflow is expected to behave, when it is available, whose identity is attached to the action, where data is scoped, and what happens on success or failure. It records the previously agreed requirements and identifies implementation decisions that must not be silently guessed.

### 1. Product principles
- Role determines which modules a user may access; client assignment determines which client data that user may access. These are separate permissions.
- Admin navigation: Home → Resources → Review → Control Panel. Control Panel includes User & Role Management, Client Assignment, Access Requests, Google Drive & Settings and Admin Logout.
- Auditor navigation: Home → Counting → Activity → Profile. Auditor logout exists in Profile.
- Client Selection and Fiscal Year Selection happen after authentication and before workspace entry. Switch Client returns there without logging out.
- All authorization must be enforced by backend/Firebase security rules. Hiding a button is not security.
- Auditor work is stored as draft/submitted data before Admin approval. Approved and Drive-published are separate statuses.
- Admin approval updates the existing configured Master Input workbook; it must not create a new master workbook on every approval.
- New features must not remove existing functionality. Role assignment and client assignment both remain available.
- Never show success until the server or external provider confirms it; provide visible failure and retry states.

### 2. User identity, roles and ownership
#### 2.1 User ID rules
- Firebase Auth generates a unique UID. Use it as `actorUid`, `auditorUid`, `createdByUid`, `updatedByUid` and `reviewerUid` as appropriate.
- Email and display name are profile attributes, not primary keys. Do not identify ownership by display name alone.
- Never store plaintext passwords or expose Google access/refresh tokens to the app UI.
- Role is read from a trusted server-controlled profile/custom claim. A client-supplied role value cannot grant privileges.
- Changing a user's role or disabling an account must take effect on future server requests; historical records keep the original actor UID.

### 3. Complete application journey
*(Refer to Table 4 below for the step-by-step end-to-end journey from Welcome through Logout).*

### 4. Welcome, Login and Registration
#### 4.1 Registration fields and rules
- Fields: full name, email, password, confirm password, requested role if enabled, and terms/privacy acceptance.
- Validate required fields, email format, password policy and matching passwords before account creation.
- Recommended safe default: new account is pending or Auditor until an authorized Admin provisions/approves it; self-selected Admin must never grant Admin access.
- Google first-time sign-in follows the same provisioning policy; Google identity alone does not grant a role or client access.
- After registration, clearly show whether email verification or Admin approval is still required.
- Confirm before production: public self-registration policy, email verification requirement and whether new Auditors need Admin approval.

### 5. Client Selection, Fiscal Year and Access Requests
#### 5.1 Admin Create Client wizard
- Enter client name/code and validate duplicates.
- Create client record and configure fiscal year dates/status.
- Upload or later attach Asset Register and Master Input Sheet.
- Connect the client's Drive and save actual folder/file IDs.
- Assign Auditors through Control Panel → Client Assignment.
- Show setup checklist; never label Drive connected until OAuth and a test operation succeed.

#### 5.2 Access request lifecycle
- Request stores `requestId`, `requesterUid`, `clientId`, optional `fiscalYearId`, `reason`, `requestedAt` and `status`.
- Admin reviews in Control Panel → Access Requests. Approve must create/activate assignment; reject must not.
- Prevent duplicate pending requests for the same UID/client/scope. Notify requester after decision.
- Notifications are informational; assignment records are the authorization source of truth.

### 6. Admin workspace
#### 6.1 Home
*(See Table 8 for Dashboard cards, Recent Activity Log, Quick Access Links, Refresh icon, and Client/year context behaviors).*

#### 6.2 Resources
*(See Table 9 for Asset Register Upload/Manage, Master Input Sheet Upload/Manage, Input Form Designer, Save Draft, Publish Configuration, Preview Form, and Upload error behaviors).*

#### 6.3 Field Mapping Designer
- Map three separate concepts: source register header, form label, and destination master column; their names may differ.
- Allow add/edit/remove/reorder field, required flag, help text, dropdown options, automatic rule, include/exclude and preview.
- Do not publish if required mapping is missing, destination columns conflict or automatic rule is invalid.
- Configuration is scoped by client/fiscal year and versioned. Each count record stores `configVersion`.
- Changes must not silently rewrite old approved records.

#### 6.4 Review
- List sheets grouped by Auditor/date, filtered by client/year/status.
- Each row shows Auditor, work date, asset count, submission time, review status and Drive publishing status.
- Admin reviews the complete daily sheet; individual-asset approval is not a mandatory separate workflow.
- Approve & Publish is enabled only after validations pass and the sheet is eligible.
- Return for Correction requires a reason and creates a new editable version for the owning Auditor; preserve the previous submission.
- Final Reject, if supported, is distinct from Return for Correction.
- Approval and Drive publishing are separate: `Approved + Publish Failed` is a valid state. Retry must not duplicate rows/files.

### 7. Control Panel
#### 7.1 User/role management behavior
- Creating/inviting a user links the profile to Firebase UID; never store plaintext password.
- Changing role requires Admin permission and confirmation; save old/new role in audit log.
- Deactivation blocks future access but preserves historical records and actor IDs.
- Show success only after server confirms profile update.

#### 7.2 Client assignment behavior
- Save creates/activates assignment records with `assignedByUid` and timestamp.
- Unassign revokes future access server-side; do not delete historical work.
- Warn if drafts or pending sheets exist before unassignment; policy must decide whether these become read-only or remain accessible in limited form.
- Show clients assigned per Auditor and Auditors assigned per client.

#### 7.3 Google Drive settings
- Connect Drive uses real OAuth with minimum required scopes; never ask users to paste access tokens.
- Persist actual root folder ID, Image Evidence folder ID, Daily Input Sheets folder ID and Master Input workbook ID.
- Provide Test Connection, Reconnect, Disconnect and Sync Health.
- Disconnect warns about consequences and must not delete remote files by default.
- Protect refresh tokens server-side; never expose them in client-readable documents or logs.

### 8. Auditor workspace
#### 8.1 Home
- Dashboard shows assigned client/year, workload or assigned assets if configured, counted today, drafts/submissions and status summaries.
- Today's Assignment may show department/location/tasks only if those fields are configured.
- Notifications show due dates, returned sheets, Admin messages and publishing outcomes; selecting one opens the relevant record.
- Switch Client returns to Client Selection and clears old client data from UI cache before loading new scope.

#### 8.2 Counting
*(See Table 12 for Search, Filter, Asset result card, Asset detail, Start Count / Edit Count, Not Found / Manual Asset, Save Draft, Add Photo / Camera, and Submit Daily Sheet behaviors).*

#### 8.3 Input form
- Fixed fields are prefilled and read-only; dropdown values/options and automatic values come from active config version; manual fields validate type/range/length.
- Show field-level errors and preserve other valid input when validation fails.
- Each draft belongs to `auditorUid`, `clientId`, `fiscalYearId`, `sheetId`, asset/verification ID and `configVersion`.
- Auditor can edit own Draft and eligible Returned for Correction records only; cannot edit another Auditor's records by changing a request.
- Images may be replaced/removed while record is editable; log evidence changes and follow retention policy.
- Submitted version is frozen. A returned sheet creates a correction version while retaining the submitted version.

#### 8.4 Activity
- Show current Auditor's daily sheets by date, asset count, last updated time and status.
- Open a sheet to view permitted assets/evidence; download only through secure export.
- Do not expose another Auditor's private drafts. Admin reviews submitted sheets in Review.

#### 8.5 Profile
- Show name/email and editable permitted profile fields.
- Role, UID and client assignments are read-only for Auditor.
- Password change/reset uses Firebase Auth, never a profile text field.
- Auditor Logout lives here; end session and clear protected local context.

### 9. Daily sheets, evidence and Drive workflow
#### 9.1 Daily sheet lifecycle
- Create/reuse a draft according to the configured one-sheet-per-Auditor/client/fiscal-year/date policy.
- Every verification references `sheetId` and is attributed to the signed-in Auditor UID.
- Autosave may be used, but Saved/Saving/Offline/Failed status must be honest.
- Submission validates required fields, duplicates and evidence requirements, then freezes a submitted version.
- Admin review/approval references the exact submitted version, not mutable client-side state.

#### 9.2 Evidence
- Store `evidenceId`, client/year/sheet/verification IDs, `uploadedByUid`, `capturedAt`, MIME, file size, location and upload status.
- Recommended filename: `nickname_assetID_1.jpg`. Sanitize names and ensure uniqueness with a suffix or evidence ID.
- Image Evidence folder contains images only; workbooks are stored in their own folders.
- Failed image upload stays visibly Failed/Pending and can be retried; never label it uploaded.
- Replacement/deletion follows edit permissions and retention policy; preserve audit reference to old evidence where required.

#### 9.3 Drive folder structure
```text
AssetFlow / [Client Name] /
├── Image Evidence/  → nickname_assetID_1.jpg, nickname_assetID_2.jpg, ...
├── Master Input File/  → Client_Master_Input.xlsx (persistent workbook)
└── Daily Input Sheets/ → [Auditor Name]/[YYYY-MM-DD]/Daily_Input.xlsx
```
Recommended authority split: Firebase holds operational drafts, permissions, statuses and metadata; Drive holds approved output files and persistent master workbook. Make the current authoritative copy clear to users.

### 10. Status model and failure handling
*(See Table 14 and Table 15 below).*

### 11. Button and icon dictionary
#### 11.1 Interaction states for every control
- Define default, focus/hover, pressed, disabled, loading, success and failure states as relevant.
- Destructive actions require confirmation showing the target name.
- Icons without text need accessible labels/tooltips; status must not rely on color alone.
- Disable repeated taps while requests run and use backend idempotency for mutations.
- After a successful mutation, refresh from authoritative server state.

### 12. Recommended Firebase data model
- `users/{uid}`
- `clients/{clientId}`
- `clients/{clientId}/fiscalYears/{fiscalYearId}`
- `assignments/{assignmentId}`
- `accessRequests/{requestId}`
- `clientScopes/{clientId}_{fiscalYearId}/registerVersions/{versionId}`
- `clientScopes/{clientId}_{fiscalYearId}/formConfigs/{configVersion}`
- `dailySheets/{sheetId}`
- `dailySheets/{sheetId}/verifications/{verificationId}`
- `approvals/{approvalId}`
- `driveConfigs/{scopeId}`
- `publishJobs/{jobId}`
- `auditEvents/{eventId}`
*(Note: Section V of this document maps these logical entities onto the reconciled existing codebase paths `/users/{orgWorkspaceId}/...` with full backward compatibility).*

### 13. Security rules that must hold
- All private reads/writes require authenticated UID.
- Resolve role from trusted server data, never from a client-supplied role field.
- Only Admin may create clients, manage users/roles, assign clients, decide access requests, upload/configure resources, approve/publish or manage Drive.
- Auditor may access assigned client/year only and may edit only own Draft/Returned sheets by default.
- Every data/storage access must validate `clientId` and `fiscalYearId` scope, not merely screen navigation.
- Unassignment, role change and deactivation must revoke future requests server-side.
- Only trusted backend workflow may set Approved/Published; do not trust client-sent status values.
- Use least-privilege OAuth scopes; protect tokens and secrets; validate workbook/image uploads.
- Log denied attempts without revealing protected client data; use secure/expiring download access.
- Define retention, backup, export and deletion policy before production.

### 14. Audit trail
Use server-generated timestamps and immutable event IDs. Keep logs useful but avoid storing passwords, tokens, full image content or unnecessary sensitive asset details.

### 15. Acceptance tests
*(See Table 19 below).*

### 16. Recommended implementation order
1. Confirm registration policy and daily-sheet uniqueness rule.
2. Implement Firebase Authentication, user profile provisioning and trusted role enforcement.
3. Implement Client/Fiscal Year selection, assignment validation and Access Request lifecycle.
4. Implement client setup and full Control Panel: User & Role Management, Client Assignment, Access Requests, Drive & Settings, Admin Logout.
5. Implement workbook upload/parse/versioning for Asset Register and Master Input Sheet.
6. Implement Field Mapping Designer, preview, validation and configuration versioning.
7. Implement Auditor Home, Counting, draft persistence, evidence, Activity and Profile.
8. Implement submission state machine and Admin Review/approval.
9. Implement real Drive OAuth, folder mapping, daily exports, evidence upload and idempotent master workbook update.
10. Implement notifications, audit trail, retry/reconciliation, security rules and status UI.
11. Test with at least two clients and two Auditors; include role mismatch, offline mode, token expiry and partial publish failure.
12. Complete backup/restore and retention policy before production launch.

### 17. Decisions to confirm before production
*(See Table 20 below).*

### Definition of done
- Every screen, button, icon and field has a defined action, permission and state.
- Every protected request checks UID, role, client/year scope, ownership and record status.
- Every workflow has loading, success, failure and retry behavior.
- All old features remain available when new ones are added.
- Approval, Drive upload and master workbook update are separately tracked and auditable.
- Two-client/two-Auditor tests prove that client data does not leak across assignments.

### Original Specification Reference Tables (Tables 1–20)

#### Table 1 — System Identity & Core Scope
| Item | Definition |
| --- | --- |
| Product | AssetFlow — Fixed Asset Verification System |
| Primary roles | Admin; End User (Auditor) *(plus Owner governance authority)* |
| Identity | Firebase Authentication UID is the immutable user identifier. |
| Core scope | Every client record is scoped by `clientId`; year-specific data also uses `fiscalYearId`. |
| Important boundary | Functional specification baseline supplemented by verified codebase audit and implementation plan below. |

#### Table 2 — Role Capability Matrix
| Capability | Admin | End User (Auditor) |
| --- | --- | --- |
| Create/configure clients | Yes | No |
| Upload Asset Register/Master Input Sheet | Yes | No |
| Configure form mapping | Yes | No |
| Create users / assign Admin or Auditor role | Yes *(Owner governs Admin role)* | No |
| Assign/unassign clients to Auditors | Yes | No |
| Request access to unassigned client | Not applicable | Yes |
| Count assets and add evidence | Review/inspect as authorized | Yes, assigned scope only |
| Approve/publish daily sheets | Yes | No |
| Configure Drive | Yes | No |
| Logout location | Control Panel | Profile |

#### Table 3 — Core Record Identity & Scope Fields
| Record | Identity/scope fields |
| --- | --- |
| User | `uid`, `email`, `displayName`, `role`, `active`, `createdAt`, `updatedAt` |
| Client | `clientId`, `clientName`, `status`, `createdByUid`, `createdAt` |
| Fiscal year | `fiscalYearId`, `clientId`, `label`, `startDate`, `endDate`, `status` |
| Assignment | `assignmentId`, `clientId`, `auditorUid`, `assignedByUid`, `assignedAt`, `active` |
| Daily sheet | `sheetId`, `clientId`, `fiscalYearId`, `auditorUid`, `workDate`, `status`, `version` |
| Verification | `verificationId`, `sheetId`, `assetId`/`manualAssetId`, `enteredByUid`, `updatedByUid` |
| Approval | `approvalId`, `sheetId`, `reviewedByUid`, `decision`, `approvedVersion`, `approvedAt` |
| Drive config | `clientId`/`scopeId`, `connectedByUid`, `rootFolderId`, `masterWorkbookId` |
| Audit event | `eventId`, `actorUid`, `action`, `targetType`, `targetId`, `clientId`, `fiscalYearId`, `timestamp`, `result` |

#### Table 4 — Complete Application Journey
| Step | Actor | Action | Result |
| --- | --- | --- | --- |
| 1 Welcome | Visitor | Tap Get Started / Sign In | Login screen opens. |
| 2 Choose role | Visitor | Select Admin or End User (Auditor) | Requested role context only; not permission proof. |
| 3 Authenticate | Visitor | Email/password or Google Sign-In | Firebase verifies identity. |
| 4 Validate profile | System | Check account status and trusted role | Invalid, disabled or mismatched account is blocked. |
| 5 Select client/year | Authenticated user | Choose an authorized client and fiscal year | Backend validates assignment and scope. |
| 6 Workspace | System | Load role-specific navigation | Admin Home or Auditor Home. |
| 7 Perform work | Admin/Auditor | Configure resources or count assets | All records include correct UID/client/year scope. |
| 8 Submit | Auditor | Submit daily sheet | Submitted version enters Admin Review queue. |
| 9 Review | Admin | Inspect/download; approve or return/reject | Decision, reviewer UID and timestamp are saved. |
| 10 Publish | System/service | Update master workbook and upload daily sheet/evidence | Publish status is independently tracked. |
| 11 Logout | User | Admin: Control Panel; Auditor: Profile | Session ends and Login appears. |

#### Table 5 — Login & Registration Screen Elements
| Screen element | What it does | When / validation |
| --- | --- | --- |
| AssetFlow logo | Brand identity / home navigation if clickable | Must not trigger data changes. |
| Get Started / Sign In | Opens Login | Visitor taps. |
| Admin role card / shield | Selects requested Admin context | Must match trusted role after authentication. |
| End User card / person icon | Selects Auditor context | Must match trusted role after authentication. |
| Email field / mail icon | Collects email | Trim spaces and validate format. |
| Password field / lock | Collects password | Masked by default; never logged. |
| Eye icon | Shows/hides password visually | Does not alter credential value. |
| Email & Password Sign In | Calls Firebase Auth then loads trusted profile | On success validate active status and role before navigation. |
| Google Sign-In | Starts Google OAuth credential flow | Apply the same profile/role/provisioning checks. |
| Create Account | Opens registration | New user does not automatically receive Admin privileges. |
| Forgot Password | Starts password reset | Use neutral confirmation to avoid exposing whether an email exists. |
| Loading indicator | Shows request in progress | Disable repeated taps while request is running. |
| Error area | Explains safe next step | No secrets, tokens or unnecessary account details. |

#### Table 6 — Client & Fiscal Year Selection Controls
| Control | Admin | Auditor |
| --- | --- | --- |
| Client dropdown | Active clients Admin can manage | Only active assigned clients |
| Fiscal Year dropdown | Configured years for selected client | Only permitted years for assigned client |
| Continue | Validate scope and open Admin Home | Validate assignment server-side and open Auditor Home |
| Create Client | Visible; opens client setup wizard | Hidden and blocked server-side |
| Switch Client | Return to selector without logout | Return to selector without logout |
| No clients | Show setup guidance | Show no-assignment message; do not load client data |
| Access Denied | Normally not expected for authorized scope | Explain not assigned; offer Request Access when allowed |

#### Table 7 — Access Request States
| State | Meaning | Permission |
| --- | --- | --- |
| Pending | Auditor requested access | No access granted |
| Approved | Admin approved and assignment created | Access granted to approved client/scope |
| Rejected | Admin rejected request | No access granted; optional reason shown |
| Cancelled (optional) | Requester withdrew request | No access granted |

#### Table 8 — Admin Home Elements
| Element | Behavior |
| --- | --- |
| Dashboard cards | Show scoped counts: active clients/users, submitted sheets, pending reviews, approved sheets and publishing failures. |
| Recent Activity Log | Show actor, action, target and timestamp; detail view is read-only and permission-checked. |
| Quick Access Links | Navigate to existing modules; do not create duplicate workflows or separate data sources. |
| Refresh icon | Reload metrics and show last refresh time/error. |
| Client/year context | Make current scope visible; organization-wide metrics must be labelled explicitly. |

#### Table 9 — Admin Resources & Field Mapping Features
| Feature | Behavior |
| --- | --- |
| Asset Register Upload/Manage | Validate workbook type/size; parse sheet names, headers and sample rows; preview; save source version and parse result. |
| Master Input Sheet Upload/Manage | Identify target worksheet/header row and store persistent workbook ID for this client/scope. Confirm before replacement. |
| Input Form Designer | Map source Asset Register header → Auditor-facing label/input → Master Input Sheet destination column. |
| Save Draft | Save configuration draft with editor UID/time; not yet active for new forms. |
| Publish Configuration | Validate mappings/rules, create version and activate it. |
| Preview Form | Render the actual Auditor form using current/draft configuration. |
| Upload error | Keep prior active version; show cause and retry. |

#### Table 10 — Input Form Field Natures
| Field nature | Expected behavior | Validation |
| --- | --- | --- |
| Fixed / Read-only | Prefill from Asset Register; Auditor cannot edit | Must match selected client/year and asset row |
| Dropdown | Auditor selects Admin-defined option | Required and option membership validation |
| Automatic | System calculates using configured rule | Validate rule; record rule/config version |
| Manual | Auditor enters value | Validate type, length, range and required status |

#### Table 11 — Admin Control Panel Subsections
| Subsection | Controls and behavior | Boundary |
| --- | --- | --- |
| User & Role Management | Add/invite user, search/filter, activate/deactivate, edit permitted profile fields, assign Admin/Auditor role, save with confirmation *(plus permanent User Deletion)*. | Role controls modules; never changes client assignment implicitly. |
| Client Assignment | Choose Auditor, assign/unassign clients and optionally fiscal years, view assignment lists, save changes *(plus Client Management & permanent Client Deletion)*. | Client access is independent of role. |
| Access Requests | View pending requests, approve/reject with optional reason, review history and notify requester. | Pending/rejected request never grants access. |
| Google Drive & Settings | Connect/reconnect/disconnect, select/create root folder, configure folders, test connection, show sync health. | A saved email or folder URL is not a working OAuth connection. |
| Admin Logout | End session and return to Login. | Only in Admin Control Panel. |

#### Table 12 — Auditor Counting & Input Form Controls
| Element | Action and expected behavior |
| --- | --- |
| Search / magnifier | Search selected client's register by asset ID, tag, description or configured searchable fields only. |
| Filter / funnel | Filter by available fields such as department, location, asset class or count status; include Clear Filters. |
| Asset result card | Show key read-only register data and count status; tap opens asset detail/form. |
| Asset detail | Show register values, allowed history, draft values and evidence summary. |
| Start Count / Edit Count | Open form using active configuration version; existing draft opens in edit mode. |
| Not Found / Manual Asset | If enabled, create separate exception/manual record; never fabricate a register asset ID; auto-populate Excel `Remarks` with `Not Found in Asset Register`. |
| Save Draft | Persist partial data; keep status Draft and show saved/saving/failed state. |
| Add Photo / Camera | Request permission when invoked; capture/select, preview and attach to correct verification. |
| Submit Daily Sheet | Validate fields, duplicates and evidence rules; confirm, then create submitted version. |

#### Table 13 — Google Drive Operations
| Operation | Trigger | Required result |
| --- | --- | --- |
| Connect/create folders | Client setup or Drive settings | Save actual remote IDs and verify access. |
| Upload evidence | Capture/upload or configured sync step | Save remote file ID and status under correct client. |
| Upload daily sheet | Approved publishing workflow | Place under correct Auditor/date path; no duplicates on retry. |
| Update master workbook | After Admin approval | Update existing workbook by stable key and mapped destination columns. |
| Retry/reconcile | Partial failure or timeout | Check completed operation IDs before retry; avoid duplicate effects. |

#### Table 14 — Status Model Across Entities
| Object | Statuses | Rules |
| --- | --- | --- |
| User | Pending, Active, Disabled | Only Active users with valid role can enter. |
| Client | Setup Incomplete, Active, Archived | Archived clients are not selectable for new work unless explicitly permitted. |
| Drive connection | Not Connected, Connected, Needs Reauthorization, Error | Connected means test succeeded. |
| Daily sheet | Draft, Submitted, Pending Review, Returned for Correction, Rejected, Approved | Review status is separate from publishing status. |
| Publish job | Queued, Publishing, Published, Publish Failed | Track per-step results and retry safely. |
| Access request | Pending, Approved, Rejected, Cancelled | Only approved request creates/activates assignment. |
| File upload | Queued, Uploading, Uploaded, Failed | Persist retry state and remote IDs. |

#### Table 15 — Failure Cases & System Responses
| Failure case | UI response | System behavior |
| --- | --- | --- |
| Invalid login | Safe generic credentials message | Do not reveal whether email exists. |
| Role mismatch | Explain selected role does not match assigned role | Block navigation and log security event. |
| No client assigned | Show empty state and Admin guidance | Do not load client data. |
| Unauthorized client | Access Denied + Request Access if allowed | Return no protected data. |
| Invalid workbook | Show worksheet/header/file error | Preserve previous active version. |
| Offline save | Show Not Saved/Offline and retry | Do not claim cloud persistence. |
| Drive token expired | Needs Reauthorization | Pause publish job without losing approved state. |
| Partial publishing | Approved — Publish Failed with failed step | Retry idempotently; do not repeat successful steps. |
| Duplicate submit | Show current submission status | Use transaction/idempotency key. |
| Session expired | Return to Login | Clear protected cached data. |

#### Table 16 — Icon & Control Dictionary
| Icon/control | Meaning | Expected action / rule |
| --- | --- | --- |
| Home / house | Dashboard | Open role-specific Home; show active navigation state. |
| Resources / file-sheet | Source workbooks and mapping | Admin only. |
| Review / checklist | Submitted daily sheets | Admin only; opens review queue. |
| Control Panel / gear | Administration | Admin only; backend authorization required. |
| User/group | User management or profile | Label clearly based on context. |
| Client/building | Client selection or assignment | Clarify whether changing context or permission. |
| Calendar | Fiscal year/work date | Validate allowed year/date. |
| Search/magnifier | Find asset/user/sheet | Search only authorized scope. |
| Filter/funnel | Narrow results | Provide Clear Filters. |
| Upload/cloud arrow | Select and upload file | Validate; show progress, result and retry. |
| Download/down arrow | Export/download | Check permission before issuing file. |
| Camera/image | Capture evidence | Request permission on demand; preview and show upload state. |
| Plus | Create/add | Open form; prevent duplicate records on repeated taps. |
| Pencil/edit | Edit | Enable only for permitted owner/status. |
| Trash/delete | Delete/remove | Confirm target; apply retention/soft-delete or permanent deletion policy. |
| Check/Approve | Approve/publish | Validate and confirm; record approver UID/time. |
| X/Reject/Return | Reject or return | Require reason; distinguish final rejection from correction. |
| Refresh | Reload/retry | Do not duplicate side effects. |
| Bell | Notifications | Open details; unread state updates server-side. |
| Eye | Show/hide password | Visual masking only; never log credentials. |
| Back arrow | Return to previous screen | Warn if unsaved edits may be lost. |
| Switch Client | Change active scope | Return to selector and clear old scope cache. |
| Logout | End session | Clear local session/context; never delete server records. |

#### Table 17 — Minimum Required Firestore Collection Fields
| Collection | Minimum required fields |
| --- | --- |
| `dailySheets` (`daily_batches`) | `sheetId`/`batchId`, `clientId`, `fiscalYearId`, `auditorUid`/`submitterUid`, `workDate`/`countingDate`, `status`, `configVersion`, `version`/`versionNumber`, `timestamps` |
| `verifications` (`counting_records`) | `verificationId`, `sheetId`, `clientId`, `fiscalYearId`, `assetId`/`manualAssetId`, `values`, `evidenceIds`, `createdByUid`, `updatedByUid` |
| `publishJobs` (`master_sync_jobs`) | `jobId`, `sheetId`, `approvedVersion`, `masterFileId`, `state`, `stepResults`, `attempts`, `idempotencyKey`, `lastError` |
| `auditEvents` (`activity_logs`) | `eventId`, `actorUid`, `actorRoleAtTime`, `clientId`, `fiscalYearId`, `action`, `target`, `result`, `timestamp`, `correlationId` |

#### Table 18 — Required Audit Event Details
| Event | Required audit details |
| --- | --- |
| Login/logout | UID, provider, time, result; no password/token |
| Role change/deactivation | Actor UID, target UID, old/new value, timestamp |
| Assignment change | Actor UID, Auditor UID, client/scope, old/new state |
| Access request decision | Request ID, requester UID, client, reviewer UID, decision, reason, time |
| Workbook upload/mapping publish | Scope, source/config version, uploader/editor UID, validation result |
| Evidence change | Evidence/verification ID, actor UID, action, time, upload state |
| Submission/return/approval | Sheet ID/version, actor UID, transition, reason, timestamp |
| Drive publishing | Job ID, approved version, master file ID, per-step results, retries and remote IDs |

#### Table 19 — Core Functional Acceptance Tests
| ID | Scenario | Expected result |
| --- | --- | --- |
| AUTH-01 | Unregistered account signs in | Blocked; no client data loaded. |
| AUTH-02 | Auditor selects Admin role | Role mismatch blocks entry. |
| AUTH-03 | First-time Google identity has no provisioned role | No automatic Admin access; provisioning policy applies. |
| CLIENT-01 | Auditor tries unassigned Client B | No Client B data; Access Denied/request option. |
| CLIENT-02 | Admin approves request | Assignment is created; access works after refresh. |
| CLIENT-03 | Admin unassigns Auditor | Future access denied; history preserved. |
| RES-01 | Invalid register workbook uploaded | Errors shown; prior active version preserved. |
| MAP-01 | Required mapping missing | Configuration cannot be published. |
| COUNT-01 | Auditor saves incomplete draft | Draft persists with correct UID/scope and can resume. |
| COUNT-02 | Auditor edits another user's draft | Server denies. |
| EVID-01 | Photo upload fails | Failed/pending state; retry offered. |
| SUB-01 | Valid sheet submitted twice quickly | One submission only. |
| REV-01 | Admin returns sheet with reason | Auditor can correct; previous version preserved. |
| REV-02 | Admin approves sheet | Approved version, reviewer UID and timestamp saved. |
| DRIVE-01 | Master update succeeds but daily upload fails | Approved + Publish Failed; retry only missing step. |
| DRIVE-02 | Publish retry repeated | No duplicate master rows or daily files. |
| LOG-01 | Admin changes role/assignment | Audit event captures actor, target, change and time. |
| SESSION-01 | User switches client | Old client cache cleared before new data loads. |
| SESSION-02 | User logs out | Session ends; protected screens inaccessible. |

#### Table 20 — Operational Policy Defaults
| Decision | Recommended default |
| --- | --- |
| Public self-registration | New account provisioned as Active Auditor with zero client assignments; no self-granted Admin. |
| Fiscal-year scope | Client assignment plus year-level restriction where required. |
| Daily sheet uniqueness | One logical daily sheet per Auditor + client + fiscal year + work date (versioned). |
| Returned vs rejected | Returned = editable correction version; Rejected = final refusal. |
| Data authority | Firebase for Asset Register records, operational drafts/submissions, permissions, statuses and metadata; Drive for approved output files, evidence images, and persistent Master Input workbook. |
| Offline support | Label local-only changes clearly; never say cloud-saved before sync. |
| Evidence retention | Preserve audit references to replaced/deleted evidence according to policy. |
| Admin count entry | Default Admin reviews; direct count entry requires explicit authorization. |

---

## Part I-B: Original Complete 18-Section Application & Architecture Plan (Preserved in Full with Updated Technical Decisions)

### Section 1: Executive Summary & Finalized Product Decisions
1. **4-Tab Navigation Structure**:
   - **Admin Workspace**: `Home`, `Resources`, `Review`, `Control Panel`.
   - **End User (Auditor) Workspace**: `Home`, `Counting`, `Activity`, `Profile`.
2. **Owner-Controlled Admin Governance (`Owner` → `Admin` → `Auditor`)**:
   - **Initial Owner & First Admin**: The Firebase account designated by the project owner (`mislamabed@gmail.com` / `DESIGNATED_OWNER_UID`) is provisioned as the initial **Owner** and first **Admin** via a controlled, one-time, server-enforced bootstrap.
   - **No Self-Promotion**: No user can ever become `Owner` or `Admin` by selecting `"Admin"` on the login screen, registering a new account, calling a client-side self-bootstrap helper, or editing their own Firestore profile.
   - **Trusted Role Source of Truth**: Authorization is determined exclusively by server-protected Firestore documents (`/users/{orgId}/system_config/ownership` and `/users/{orgId}/role_assignments/{uid}`) and/or Firebase Auth custom claims managed by the mandatory trusted backend—**never** by `user_profile/main.role` or UI state.
   - **Owner Protection**: An `Admin` cannot delete, demote, deactivate, or strip permissions from the `Owner`. Only the `Owner` (or an `Admin` explicitly granted `canManageAdmins: true` by the `Owner`) may appoint or revoke other `Admin` roles.
3. **Admin-Controlled Permanent Client and User Deletion**:
   - **Delete Client (`Control Panel → Client Management`)**: Permanently deletes **only** the selected `targetClientId` and its scoped records across Firestore collections, client-scoped photo metadata, and local device cache (`OfflineSyncDatabase`), while preserving all other clients, user accounts, and shared files. Google Drive folder/workbook deletion requires a **separate, explicit confirmation** and is blocked if any file ID is shared with another client.
   - **Delete User (`Control Panel → User & Role Management`)**: Permanently deletes the selected user's account (`FirebaseAuth` user via the mandatory Firebase Admin SDK backend, `/user_profile/main`, `/role_assignments/{targetUid}`, `/client_assignments`, `/access_requests`, `/notifications`, and unsubmitted personal drafts), while **retaining and pseudonymizing** (`"Deleted User [#" + shortHash + "]"`) submitted/approved client counting records, daily sheets, and audit logs so client verification history is never corrupted.
4. **New User Provisioning & Zero Unassigned Access**:
   - First-time sign-in creates an `Active` **Auditor** profile with **zero client assignments**.
   - Unassigned Auditors have **zero read/write access** to any client's assets, workbooks, counts, or daily sheets in both the UI and `firestore.rules`.
5. **Non-Intrusive Access Request UX**:
   - Unassigned Auditors see a calm inline **"No Client Assigned / Request Access"** card on `ClientSelectionScreen`. Duplicate pending requests for the same `(clientId, fiscalYearId, uid)` are blocked via deterministic document IDs. No recurring modal popups are ever shown.
6. **Multi-Client & Fiscal-Year Isolation & Environment Separation**:
   - **Development**: Existing unscoped Walton records are migrated to `clientId = "walton_hitech_dev"`, `fiscalYearId = "2026_27"` with provenance verification, pre-migration backups, quarantine for ambiguous records, and rollback.
   - **Production**: Starts completely blank with **zero preloaded clients, zero fiscal years, zero Asset Registers, and zero sample workbooks**.
7. **Complete Removal of Obsolete Features**:
   - Completely remove the 34-Day Roadmap (`PlanningAndForecastEngine.kt`), standalone PDF/audit-pack export engine (`AuditReportExportEngine.kt`, after moving shared Excel structure validation into `ExcelWorkbookEngine.kt`), unreachable legacy screens (`DashboardScreen.kt`, `MasterDataScreen.kt`, `ExceptionsAndFoundScreen.kt`), standalone `DriveScreen.kt` / `UsersScreen.kt`, `AdminReviewRequestsTab.kt`, and `SyncConflictEntity`.
8. **Finalized Data Storage & Master Workbook Sync Architecture (Decisions A–D & H)**:
   - **Asset Register**: Stored as structured records in Firebase Firestore (`far_assets`) to power Auditor search, selection, and form prepopulation; original `.xlsx` optionally archived in Google Drive.
   - **Auditor Daily Input & Approvals**: Stored in Firebase Firestore (`counting_records`, `daily_batches`, `submissions`, `activity_logs`).
   - **Master Input Excel Workbook**: Lives in **Google Drive as the primary binary file**; Firebase stores **only** metadata, destination mappings, revision/checksum state, and the applied-submission ledger (`/master_workbook_state`, `/master_sync_jobs`). Approved submissions update **only** mapped destination cells in place while preserving worksheets, headers, column order, formulas, styles, and merged cells.
   - **Image Evidence**: Stored in **device-local app storage (`filesDir/audit_photos`) and Google Drive (`Image Evidence/`)**, with **metadata only in Firebase Firestore (`asset_photos`)**. **No image binaries are uploaded to Firebase Storage**, and no Firebase Storage SDK or Storage rules are used.

---

### Section 2: Current Project Audit (Verified Against Codebase)

#### 2.1 Technology Stack & Build Configuration
- **Platform**: Native Android (Kotlin, Jetpack Compose Material 3, `minSdk = 24`, `compileSdk = 36`, `targetSdk = 35`).
- **Application ID & Namespace**: `applicationId = "com.aistudio.fixedassetcounter.walton"`, `namespace = "com.example"`.
- **Backend & Persistence**:
  - **Firebase Firestore** (`libs.firebase.firestore`, BoM `34.10.0`) using named database ID `ai-studio-20090969-10c1-4df6-983b-563f1387fa3b` configured in `app/src/main/res/values/firebase_applet_config.xml` and `firebase.json` (project ID: `spherical-booth-366013`).
  - **Firebase Authentication** (`libs.firebase.auth`) + **Jetpack Credential Manager** (`libs.androidx.credentials`, `libs.googleid`).
  - **Firebase Crashlytics** (`libs.firebase.crashlytics`) initialized in `MainActivity.kt`.
  - **Firebase Storage**: **Not installed** in `gradle/libs.versions.toml` or `app/build.gradle.kts` (confirming Decision D: zero Firebase Storage usage).
  - **Room Local Database** (`OfflineSyncDatabase.kt`, version 4) storing `OfflineQueuedRecordEntity`, `OfflinePhotoQueueEntity`, and legacy `SyncConflictEntity`.
  - **Google Drive REST API v3** (`GoogleDriveService.kt` + `OkHttpDriveTransport`) using `com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient` with `https://www.googleapis.com/auth/drive.file` scope.
  - **Custom OpenXML `.xlsx` Streaming Engine** (`ExcelWorkbookEngine.kt`) using `ZipInputStream`, `XmlPullParser`, and `ZipOutputStream`.

#### 2.2 Complete Inventory of Existing Source Files (34 Kotlin Files)
1. `/app/src/main/java/com/example/MainActivity.kt` (927 lines)
2. `/app/src/main/java/com/example/AdminNavTab.kt` (50 lines)
3. `/app/src/main/java/com/example/data/model/AuditModels.kt` (559 lines)
4. `/app/src/main/java/com/example/data/schema/DynamicSchemaEngine.kt` (562 lines)
5. `/app/src/main/java/com/example/data/excel/ExcelWorkbookEngine.kt` (913 lines)
6. `/app/src/main/java/com/example/data/export/AuditReportExportEngine.kt` (632 lines)
7. `/app/src/main/java/com/example/data/drive/GoogleDriveService.kt` (956 lines)
8. `/app/src/main/java/com/example/data/offline/OfflineSyncDatabase.kt` (279 lines)
9. `/app/src/main/java/com/example/data/storage/PhotoStorageManager.kt` (230 lines)
10. `/app/src/main/java/com/example/data/remote/TeraBoxStorageEvaluation.kt` (64 lines)
11. `/app/src/main/java/com/example/data/repository/AuditRepository.kt` (2,193 lines)
12. `/app/src/main/java/com/example/domain/PlanningAndForecastEngine.kt` (201 lines)
13. `/app/src/main/java/com/example/ui/viewmodel/AuditViewModel.kt` (1,073 lines)
14. `/app/src/main/java/com/example/ui/viewmodel/AdminDashboardViewModel.kt` (194 lines)
15. `/app/src/main/java/com/example/ui/screens/AuthScreen.kt` (340 lines)
16. `/app/src/main/java/com/example/ui/screens/AdminDashboardScreen.kt` (258 lines)
17. `/app/src/main/java/com/example/ui/screens/ResourcesScreen.kt` (889 lines)
18. `/app/src/main/java/com/example/ui/screens/DriveScreen.kt` (361 lines)
19. `/app/src/main/java/com/example/ui/screens/UsersScreen.kt` (492 lines)
20. `/app/src/main/java/com/example/ui/screens/AdminControlPanelScreen.kt` (340 lines)
21. `/app/src/main/java/com/example/ui/screens/AdminColumnMappingTab.kt` (148 lines)
22. `/app/src/main/java/com/example/ui/screens/AdminFieldConfigTab.kt` (199 lines)
23. `/app/src/main/java/com/example/ui/screens/AdminSchemaAndDriveScreens.kt` (507 lines)
24. `/app/src/main/java/com/example/ui/screens/AdminUserManagementTab.kt` (183 lines)
25. `/app/src/main/java/com/example/ui/screens/admin/AdminReviewQueueTab.kt` (406 lines)
26. `/app/src/main/java/com/example/ui/screens/admin/AdminReviewRequestsTab.kt` (153 lines)
27. `/app/src/main/java/com/example/ui/screens/UserDashboardScreen.kt` (297 lines)
28. `/app/src/main/java/com/example/ui/screens/AssetListScreen.kt` (453 lines)
29. `/app/src/main/java/com/example/ui/screens/CountingFormScreen.kt` (646 lines)
30. `/app/src/main/java/com/example/ui/screens/ManualObservationFormScreen.kt` (279 lines)
31. `/app/src/main/java/com/example/ui/screens/DailyInputSheetsScreen.kt` (504 lines)
32. `/app/src/main/java/com/example/ui/screens/SubmissionHistoryScreen.kt` (464 lines)
33. `/app/src/main/java/com/example/ui/screens/NotificationsScreen.kt` (154 lines)
34. `/app/src/main/java/com/example/ui/screens/ProfileScreen.kt` (381 lines)
35. `/app/src/main/java/com/example/ui/screens/DashboardScreen.kt`, `MasterDataScreen.kt`, `ExceptionsAndFoundScreen.kt` (legacy prototype screens)

---

### Section 3: Gap Analysis Matrix — Current Project vs. Reference Plan & Flowchart

| Area / Feature | Reference Plan & Flowchart Requirement | Verified Current Codebase State | Gap Status |
| :--- | :--- | :--- | :--- |
| **1. Initial Owner & Admin Governance** | Only the designated Owner (`mislamabed@gmail.com`) can bootstrap as initial `Owner` & `Admin`. Only `Owner` (or authorized `Admin`) can grant `Admin`. Zero self-promotion. | `MainActivity.kt` shows a `"Request Admin Access"` dialog calling `AuditRepository.bootstrapOwnerAdminRole()`. `firestore.rules` (lines 18–25, 198–204) allows any user in their own workspace where `!exists(role_assignments/{uid})` to act as Admin and self-create `role == 'Admin'`. `UserProfileDialog` also exposes clickable role chips. | **CRITICAL SECURITY GAP** |
| **2. Permanent Client & User Deletion** | Admin Control Panel must provide target-scoped permanent **Delete Client** and **Delete User** workflows with impact preview, typed confirmation, durable backend execution, Auth account deletion (`deleteUser`), and audit logging. | Neither Client deletion nor User deletion exists in `AuditRepository.kt`, `AdminControlPanelScreen.kt`, or `firestore.rules` (`allow delete: if false` on `user_profile` and `role_assignments`). | **MISSING (New Requirement)** |
| **3. Account Type Selection & Role Mismatch Check** | Visitor selects `Admin` or `End User (Auditor)` card before signing in; post-auth checks trusted server role and blocks role mismatch (`AUTH-02`). | `AuthScreen.kt` has no Account Type selector and performs no role mismatch check. | **MISSING** |
| **4. Client Selection & Fiscal Year Gate** | Mandatory post-login screen (`Select Client` + `Fiscal Year` + `Continue`); `Switch Client` returns here without logout. | Does not exist. App goes straight from `AuthScreen` into single-tenant workspace. | **MISSING** |
| **5. Unassigned Auditor Access & Request Flow** | Newly registered Auditors have 0 assigned clients, cannot read any client data, and see a calm inline Request Access view (no popup loops). | Every user currently reads/writes their own `/users/{currentUserId}/...` tree or triggers `importDefaultWorkbookIfEmpty()` on startup. | **CRITICAL GAP** |
| **6. Multi-Client & Fiscal-Year Data Isolation** | Every business record scoped by `clientId` and `fiscalYearId`; rules verify active assignment in `/client_assignments/{clientId}_{uid}`. | All 17 Firestore subcollections under `/users/{userId}/...` lack `clientId` and `fiscalYearId`. | **CRITICAL GAP** |
| **7. Clean Production vs. Dev Migration** | Production starts with zero preloaded business data; Development migrates existing Walton test data safely. | `MainActivity.kt` and `AuditViewModel.kt` call `importDefaultWorkbookIfEmpty()` and `seedInitial34DayPlanIfEmpty()` on every fresh launch. | **CRITICAL GAP** |
| **8. 4-Tab Admin & 4-Tab Auditor Navigation** | Admin: `Home`, `Resources`, `Review`, `Control Panel`. Auditor: `Home`, `Counting`, `Activity`, `Profile`. | Admin has 5 tabs (`Dashboard`, `Resources`, `Drive`, `Users`, `ControlPanel`). Auditor has 6 tabs (`Home`, `Assets`, `Sheets`, `History`, `Alerts`, `Profile`). | **NEEDS ALIGNMENT** |
| **9. 3-Way Field Mapping & 29-Field Default Schema** | Map `Source Register Header` ↔ `Input Form Field` ↔ `Destination Master Column` while preserving the 29-column default schema (`A..AC`). | `SchemaFieldConfig` only stores a single `columnOrder`/`displayLabel`; does not separate source register header from destination master column. | **PARTIAL** |
| **10. In-Place Master Workbook Update on Google Drive** | Keep Master `.xlsx` in Google Drive; update only approved mapped cells in place while preserving sheets, headers, styles, formulas, and merged cells. | `AuditRepository.syncMasterWorkbookToDrive` calls `AuditReportExportEngine.generateValidatedExcelBytes()`, overwriting the Drive file with a newly generated 7-sheet workbook from scratch. | **CRITICAL GAP** |
| **11. Image Evidence Storage Boundaries** | Store photos locally (`filesDir/audit_photos`) and upload to Google Drive (`Image Evidence/`); store metadata only in Firebase (`asset_photos`); zero Firebase Storage. | `PhotoStorageManager` + `GoogleDriveService` + Firestore `asset_photos` already follow this pattern, but lack `clientId`/`fiscalYearId` scoping and explicit local-retention UI warnings. | **PARTIAL** |

---

### Section 4: Navigation & Screen Architecture Redesign

#### 4.1 Top-Level Application State Machine (`MainActivity.kt`)
```text
[1. Unauthenticated: Welcome / Login / Registration (AuthScreen)]
       │  (Choose Account Type: Admin | End User (Auditor))
       │  (Authenticate via Google Sign-In or Email & Password)
       ▼
[2. Server Role, Tombstone & Status Verification Gate]
       ├── If `exists(/deleted_identities/{uid})` or `accountStatus == 'Deleted'` ──▶ Block & Sign Out
       ├── If `accountStatus != 'Active'` ──▶ Account Disabled / Pending Screen
       ├── If Chosen Role == 'Admin' && Trusted Server Role !in ['Owner', 'Admin'] ──▶ Role Mismatch Error (AUTH-02)
       ▼
[3. Client & Fiscal Year Selection Gate (ClientSelectionScreen)]
       ├── Owner / Admin:
       │     ├── Select any Active Client + Fiscal Year ──▶ [4A. Admin Workspace (4 Tabs)]
       │     └── "+ Create New Client" Wizard (First-run empty state in Production)
       └── End User (Auditor):
             ├── Assigned Client(s) + Fiscal Year ──▶ [4B. Auditor Workspace (4 Tabs)]
             └── 0 Assigned Clients or Unassigned Client Selected ──▶
                   Inline "No Client Assigned / Request Access" View (Single calm state; no popup loops)
```

#### 4.2 Admin Workspace — 4 Bottom Navigation Tabs (`AdminNavTab.kt`)
1. **`Home` (`AdminNavTab.Home` → `AdminDashboardScreen.kt`)**:
   - Active Client & Fiscal Year header badge with **Switch Client** action.
   - Scoped KPI summary cards: Assigned Auditors, Register Assets (`far_assets`), Pending Sheet Reviews (`Awaiting`), Approved Sheets, and Master Workbook Sync Failures (`Publish Failed` / `FailedRetryable` / `FailedActionRequired`).
   - Scoped **Recent Activity Log** and **Quick Access Links** to `Resources`, `Review`, and `Control Panel`.
2. **`Resources` (`AdminNavTab.Resources` → `ResourcesScreen.kt`)**:
   - **Sub-section 1 — Asset Register (Firebase-Backed + Optional Drive Archive)**: Upload/replace client `.xlsx` Asset Register, inspect worksheets/headers, configure Unique Asset-Matching Key, validate duplicates/missing keys, and import structured `FARAsset` records into Firestore (`far_assets`).
   - **Sub-section 2 — Master Input Sheet (Google Drive Primary File + Firebase Metadata)**: Select or link the persistent Google Drive Master Input `.xlsx` workbook, select target worksheet and header row, verify column headers, and view sync health / last Drive revision.
   - **Sub-section 3 — Input Form Designer (3-Way Field Mapping & Dropdown Config)**: Map `Source Register Header` → `Auditor Form Field (Fixed / Dropdown / Automatic / Manual)` → `Master Input Destination Column`, auto-detect categorical dropdown values from Excel, manage dropdown options, preview form, and publish versioned `WorkbookSchemaVersion`.
3. **`Review` (`AdminNavTab.Review` → `AdminReviewQueueTab.kt`)**:
   - Daily Sheet Review Queue grouped by Auditor and work date, filterable by status (`Awaiting`, `Returned`, `Approved`, `Published`, `Sync Failed`).
   - **Three Primary Actions per Sheet**:
     1. **Review**: Opens complete Batch Review Detail (all rows, read-only register reference vs. counted values, quantity differences, version history, and Google Drive/local photo evidence links).
     2. **Send Remarks (Return for Correction)**: Inline dialog requiring remarks, creating a new editable correction revision for the Auditor and sending an in-app notification.
     3. **Approve for Update to Master File**: Confirmation dialog that records `approvalStatus = APPROVED` with reviewer Full Name/UID/timestamp and enqueues the durable in-place Master Workbook sync job (`MasterWorkbookSyncJob`). Separately tracks `Approval Status` vs. `Master Drive Sync Status` with an idempotent **Retry Master Sync** button if Drive sync fails.
4. **`Control Panel` (`AdminNavTab.ControlPanel` → `AdminControlPanelScreen.kt`)**:
   - **Sub-section 1 — User & Role Management**: Search/filter users, view role (`Owner`, `Admin`, `Auditor`), activate/deactivate accounts, grant/revoke `Admin` role (Owner-governed), grant/revoke `Review Authority` and separate `Publication Permission`, and execute **Permanent User Deletion** (`Delete User`).
   - **Sub-section 2 — Client Management & Client Assignment**: Create/edit/archive clients and fiscal years, assign/unassign Auditors to `(clientId, fiscalYearId)` with pending-draft warnings, and execute **Permanent Client Deletion** (`Delete Client`).
   - **Sub-section 3 — Access Requests**: View `Pending` Auditor client access requests, **Approve (Auto-Assign Client)** or **Reject with Reason**, and view request history.
   - **Sub-section 4 — Google Drive & Settings**: Per-client Google Drive OAuth connection (`drive.file` scope), folder structure provisioning (`AssetFlow/[Client Name]/Image Evidence/`, `Master Input File/`, `Daily Input Sheets/`), **Test Connection**, **Verify & Reconcile Master Sheet**, and **Disconnect**.
   - **Sub-section 5 — Admin Logout**: Ends session, clears local credential/scope cache, and returns to `AuthScreen`.

#### 4.3 End User (Auditor) Workspace — 4 Bottom Navigation Tabs
1. **`Home` (`AuditorNavTab.Home` → `UserDashboardScreen.kt`)**:
   - Active Client & Fiscal Year bar with **Switch Client** button.
   - Scoped metrics: Accessible Register Assets, Counted Today, Drafts Pending Submission, Returned for Correction, and Approved/Published sheets.
   - Today's Assignment summary (when department/location filters are configured) and real-time in-app **Notifications** feed (Admin remarks, returned sheets, access request approvals, sync outcomes).
2. **`Counting` (`AuditorNavTab.Counting` → `AssetListScreen.kt` + `CountingFormScreen.kt`)**:
   - **Find / Search Asset**: Scoped search against Firebase `far_assets` by Asset ID/Tag (preserving leading zeros), Description, Class, Department, or Location, plus department/location/status filters.
   - **In-Workflow Manual Entry (Not Found in FAR)**: When an Asset ID search returns no match, displays the inline **"Asset Not Found in Register — Record as Unregistered / Manual Entry"** action within the same workflow (no separate Manual Entry tab), opening `CountingFormScreen` and automatically populating Excel `Remarks` with `Not Found in Asset Register`.
   - **Physical Count Form**: Pre-fills read-only `Fixed` register fields, renders configured `Dropdown`, `Automatic` (`Qty Diff`, `Result`), and `Manual` fields, attaches standardized camera/picker photos (`{nickname}_{assetId}.jpg`), saves drafts (`Saved` / `Saving` / `Offline`), and submits the consolidated **Daily Input Sheet**.
3. **`Activity` (`AuditorNavTab.Activity` → `DailyInputSheetsScreen.kt` + `SubmissionHistoryScreen.kt`)**:
   - Segmented view combining **My Daily Sheets** (date-wise consolidated batches, asset counts, version numbers, Approval Status, Master Sync Status, Admin remarks, and **Request Admin Review**) and **Submission History & Corrections** (both **Method A: Direct Record Correction** and **Method B: Manual Excel Download, Edit & Re-Upload**).
4. **`Profile` (`AuditorNavTab.Profile` → `ProfileScreen.kt`)**:
   - Displays authenticated email, read-only Role (`Auditor`), read-only Assigned Clients & Fiscal Years, editable **Full Name** (used in Excel `Counted By` / `Reviewed By`) and editable **Nickname** (used in UI and photo filenames `{nickname}_{assetId}.jpg`), Firebase Auth password reset/change, and **End User Logout**.

---

### Section 5: Data Model & Firestore Schema Evolution (3-Way Mapping & 29-Column Default Schema)

#### 5.1 Preserved 29-Column Default Schema (`DynamicSchemaEngine.kt`) with 3-Way Mapping
Every field in `SchemaFieldConfig` maps three distinct coordinates so arbitrary client Asset Registers and Master Input Workbooks work seamlessly while preserving the 29-column Walton baseline (`Columns A..AC`):

| Order | Col | `fieldId` | Default Form Label | Field Nature (`fieldType`) | Source Register Header (`sourceRegisterHeader`) | Destination Master Column (`destinationMasterHeader`) | Formula / Protection Rule |
| :---: | :---: | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | A | `asset_id` | Asset ID | `Fixed` (Matching Key) | `Asset ID` | `Asset ID` | Unique Asset-Matching Key (Read-Only) |
| 2 | B | `sub_number` | Sub-Number | `Fixed` | `Sub-Number` | `Sub-Number` | Optional Composite Disambiguator |
| 3 | C | `asset_class` | Asset Class | `Fixed` | `Asset Class` | `Asset Class` | Read-Only Reference |
| 4 | D | `gl_account` | G/L Account | `Fixed` | `G/L Account` | `G/L Account` | Read-Only Reference |
| 5 | E | `cost_center` | Cost Center | `Fixed` | `Cost Center` | `Cost Center` | Read-Only Reference |
| 6 | F | `department` | Department | `Dropdown` (Auto-detectable) | `Department` | `Department` | Writable Destination |
| 7 | G | `asset_description` | Asset Description | `Fixed` | `Asset Description` | `Asset Description` | Read-Only Reference |
| 8 | H | `serial_number` | Serial / Part No. | `Fixed` | `Serial Number` | `Serial Number` | Read-Only Reference |
| 9 | I | `capitalized_on` | Capitalized On | `Fixed` | `Capitalized On` | `Capitalized On` | Read-Only Reference |
| 10 | J | `useful_life` | Useful Life | `Fixed` | `Useful Life` | `Useful Life` | Read-Only Reference |
| 11 | K | `cost_tk` | Cost (Tk) | `Fixed` | `Cost (Tk)` | `Cost (Tk)` | Read-Only Reference |
| 12 | L | `accum_dep_tk` | Accum. Dep. (Tk) | `Fixed` | `Accum. Dep. (Tk)` | `Accum. Dep. (Tk)` | Read-Only Reference |
| 13 | M | `nbv_tk` | NBV (Tk) | `Fixed` | `NBV (Tk)` | `NBV (Tk)` | Read-Only Reference |
| 14 | N | `far_qty` | FAR Qty | `Fixed` | `FAR Qty` | `FAR Qty` | Read-Only Reference |
| 15 | O | `far_unit` | Unit of Measure | `Fixed` | `Unit` | `Unit` | Read-Only Reference |
| 16 | P | `far_location` | Register Location | `Fixed` | `Location` | `Location` | Read-Only Reference |
| 17 | Q | `found_status` | Found? | `Dropdown` (`Yes`, `No`, `Not Accessible`) | *(None)* | `Found?` | Writable Destination |
| 18 | R | `physical_qty` | Physical Qty | `Manual` (Numeric) | *(None)* | `Physical Qty` | Writable Destination |
| 19 | S | `qty_diff` | Qty Diff | `Automatic` (`Physical - FAR`) | *(None)* | `Qty Diff` | Writable or Formula-Protected (`<f>`) |
| 20 | T | `condition_status` | Condition | `Dropdown` (`Good`, `Fair`, `Damaged`, `Scrap`, `Obsolete`) | *(None)* | `Condition` | Writable Destination |
| 21 | U | `in_use_status` | In Use? | `Dropdown` (`In Use`, `Idle`, `Under Repair`, `Disposed`) | *(None)* | `In Use?` | Writable Destination |
| 22 | V | `tag_status` | Tag Status | `Dropdown` (`Tagged`, `Untagged`, `Damaged Tag`) | *(None)* | `Tag Status` | Writable Destination |
| 23 | W | `tag_number_observed` | Observed Tag No. | `Manual` (Text) | *(None)* | `Observed Tag No.` | Writable Destination |
| 24 | X | `actual_location` | Actual Location | `Manual` / `Dropdown` | `Location` | `Actual Location` | Writable Destination |
| 25 | Y | `user_custodian` | Custodian / User | `Manual` (Text) | `Custodian` | `Custodian` | Writable Destination |
| 26 | Z | `verification_result` | Result | `Automatic` | *(None)* | `Result` | Writable or Formula-Protected (`<f>`) |
| 27 | AA | `counted_by` | Counted By | `Automatic` (Auth Full Name) | *(None)* | `Counted By` | Writable Destination (Full Name) |
| 28 | AB | `count_date` | Count Date | `Automatic` (`YYYY-MM-DD`) | *(None)* | `Count Date` | Writable Destination |
| 29 | AC | `remarks` | Remarks | `Manual` (+ Auto Manual Marker) | *(None)* | `Remarks` | Writable Destination (`Not Found in Asset Register` preserved) |

*(Note: When `reviewed_by` or `photo_evidence_link` columns exist in a client's Master Input Sheet, they can also be mapped in `SchemaFieldConfig` without altering the 29 default columns).*

---

### Section 6: Authentication, Role Verification & Client Selection Flow
1. **Login Page Minimalism & Account Type Selection**:
   - `AuthScreen.kt` displays the AssetFlow logo, title (**AssetFlow**), subtitle (**Fixed Asset Verification System**), the two Account Type cards (`Admin` and `End User (Auditor)`), **Sign in with Google** (via Jetpack `CredentialManager`), and **Email & Password** login/registration/reset.
2. **Server-Side Role & Status Verification**:
   - Immediately after Firebase Auth succeeds, `AuditRepository.verifySessionAndResolveRole()` checks:
     1. `/users/{orgId}/deleted_identities/{uid}`: if present, immediately signs out and displays `"This account has been permanently deleted."`
     2. `/users/{orgId}/role_assignments/{uid}` and `/users/{orgId}/system_config/ownership`: resolves trusted `serverRole` (`Owner`, `Admin`, or `Auditor`) and `accountStatus` (`Active`, `Disabled`).
     3. **Role Mismatch Check (`AUTH-02`)**: If the user selected the `Admin` card on `AuthScreen` but `serverRole == 'Auditor'`, navigation is blocked with `"Access Denied: Your account is registered as an End User (Auditor), not an Admin."` and a security event is logged.
3. **Client & Fiscal Year Selection (`ClientSelectionScreen.kt`)**:
   - **For `Owner` / `Admin`**: Lists all active clients in `/users/{orgId}/clients` and their fiscal years in `/users/{orgId}/fiscal_years`, plus a **"+ Create New Client"** wizard button.
   - **For `Auditor`**: Queries `/users/{orgId}/client_assignments` where `auditorUid == uid && active == true`.
     - If assigned clients exist, populates the Client and Fiscal Year dropdowns with assigned scopes and a secondary `"Request Access to Another Client"` link.
     - If `0` clients are assigned, renders a single calm inline **"No Client Assigned Yet"** state with a selectable list of organization client names (loaded from a lightweight directory projection `/client_directory/{clientId}` exposing only `clientId`, `clientName`, `status`) and a **"Request Access to Admin"** button. Submitting creates `/access_requests/${clientId}_${fiscalYearId}_${uid}` (`status = 'Pending'`) and replaces the button with a calm `"Access Request Pending Admin Review"` status pill.

---

### Section 7: Owner Identity, Initial Admin Bootstrap & Role Authorization

#### 7.1 Preconditions & Authoritative Setup Input (`DESIGNATED_OWNER_UID`)
- The project Owner signs in with `mislamabed@gmail.com` and locks their immutable Firebase Auth `UID` (`DESIGNATED_OWNER_UID`) into `firestore.rules` (`function designatedOwnerUid()`) and the trusted backend configuration.
- **Controlled One-Time Bootstrap**:
  - Creating `/users/{orgId}/system_config/ownership` and `/users/{orgId}/role_assignments/{ownerUid}` with `role: "Owner"`, `canManageAdmins: true`, `canDeleteClients: true`, `canDeleteUsers: true` is permitted **only once**, and **only** when `request.auth.uid == designatedOwnerUid()` and `!exists(/system_config/ownership)`.
  - Once `/system_config/ownership` exists (`bootstrapLocked: true`), it is immutable (`allow update, delete: if false`).

#### 7.2 Role Hierarchy & Permissions (`Owner` → `Admin` → `Auditor`)
| Capability | `Owner` | `Admin` | `Auditor` (with Optional Review/Publish Flags) |
| :--- | :---: | :---: | :---: |
| Bootstrap initial ownership | One-time (`uid == DESIGNATED_OWNER_UID`) | No | No |
| Promote / demote `Admin` accounts | **Yes** | Only if `canManageAdmins == true` | No |
| Modify, deactivate, or delete `Owner` | Protected (Never deletable) | **No (Strictly Blocked)** | No |
| Create / configure Clients & Fiscal Years | **Yes** | **Yes** | No |
| Permanently Delete Client (`Delete Client`) | **Yes** | If `canDeleteClients == true` | No |
| Permanently Delete User (`Delete User`) | **Yes** (except self/Owner) | If `canDeleteUsers == true` (Auditor targets only) | No |
| Upload Asset Register, Link Master Sheet, Publish Mapping | **Yes** | **Yes** | No |
| Assign / unassign Clients & Approve Access Requests | **Yes** | **Yes** | No |
| Review Daily Sheets & Send Remarks | **Yes** | **Yes** | Only if granted `hasReviewAuthority` for assigned client |
| Approve & Trigger Master Workbook Update | **Yes** | **Yes** | Only if granted `hasPublishPermission` (never own sheet) |
| Count Assets & Submit Daily Sheets | Authorized scopes | Authorized scopes | Assigned `(clientId, fiscalYearId)` only |

---

### Section 8: Admin-Controlled Permanent Client and User Deletion — Impact & Scope

#### 8.1 Complete Deletion-Scope Matrix (Verified Against All Codebase Data Stores)
Every collection under `/users/{orgId}/...`, local Room table in `OfflineSyncDatabase`, device file directory (`filesDir/audit_photos`), Firebase Auth record, and Google Drive folder was audited to define exact target-scoped boundaries:

| Resource / Collection | **Client Deletion (`targetClientId`)** | **User Deletion (`targetUid`)** | Safety & Retention Invariant |
| :--- | :--- | :--- | :--- |
| `/system_config/ownership` | **PRESERVE** | **PRESERVE** (`Owner` cannot be deleted) | Singleton governance lock. |
| `/clients/{clientId}` & `/client_directory/{clientId}` | **DELETE** only `docId == targetClientId` | **PRESERVE** | Deleting a user never deletes a client. |
| `/fiscal_years/{fyId}` | **DELETE** where `clientId == targetClientId` | **PRESERVE** | Strictly scoped to `targetClientId`. |
| `/far_assets/{assetDocId}` | **DELETE** where `clientId == targetClientId` | **PRESERVE** | Client's Asset Register belongs to the client. |
| `/schema_versions/{id}`, `/column_mapping/{id}`, `/master_data/{id}`, `/project_settings/{id}`, `/master_workbook_state/{id}`, `/master_sync_jobs/{id}` | **DELETE** where `clientId == targetClientId` | **PRESERVE** (pseudonymize actor UID/name fields matching `targetUid`) | Preserves client configuration and Master sync history when a user is deleted. |
| `/counting_records/{recordId}` | **DELETE** where `clientId == targetClientId` | • **DELETE** if `countedByUserId == targetUid && countingStatus == 'Draft'`<br>• **RETAIN & PSEUDONYMIZE** (`countedBy = "Deleted User [#" + shortHash + "]"`, `countedByUserId = "deleted_" + shortHash`) if submitted/approved | Preserves official client verification records while removing personal identity PII. |
| `/daily_batches/{batchId}` & `/submissions/{subId}` | **DELETE** where `clientId == targetClientId` | • **DELETE** if `submitterUid == targetUid && status == 'Draft'`<br>• **RETAIN & PSEUDONYMIZE** (`submitterFullName = "Deleted User [#" + shortHash + "]"`) if `status in ['Awaiting', 'Approved', 'Published', 'Returned', 'Rejected']` | Submitted/approved daily sheets belong to the client audit trail. |
| `/exceptions/{id}` & `/found_not_in_far/{id}` | **DELETE** where `clientId == targetClientId` | • **DELETE** if unsubmitted draft by `targetUid`<br>• **RETAIN & PSEUDONYMIZE** if linked to submitted/approved sheet | Preserves manual/exception audit records. |
| `/asset_photos/{photoId}` (Firestore Metadata) | **DELETE** metadata docs where `clientId == targetClientId` | • **DELETE** metadata doc if `capturedByUid == targetUid` and linked count is `Draft`<br>• **RETAIN & PSEUDONYMIZE** (`capturedByName = "Deleted User [#" + shortHash + "]"`) if linked count is submitted/approved | Zero Firebase Storage binaries exist; metadata follows parent count retention. |
| `/client_assignments/{id}` | **DELETE** where `clientId == targetClientId` | **DELETE** where `auditorUid == targetUid` | Deterministic ID `${clientId}_${auditorUid}`. |
| `/access_requests/{id}` | **DELETE** where `clientId == targetClientId` | **DELETE** where `requesterUid == targetUid` | Deterministic ID `${clientId}_${fyId}_${requesterUid}`. |
| `/notifications/{notifId}` | **DELETE** where `clientId == targetClientId` | **DELETE** where `recipientUid == targetUid` | Removes target-scoped notifications. |
| `/users/{targetUid}/user_profile/main` | **PRESERVE** | **DELETE** | Removes personal profile document. |
| `/role_assignments/{targetUid}` & `/deleted_identities/{targetUid}` | **PRESERVE** | **WRITE TOMBSTONE** `/deleted_identities/{targetUid}`, then **DELETE** `/role_assignments/{targetUid}` | Immediately blocks all future Firestore reads/writes by `targetUid`. |
| Local Room (`OfflineSyncDatabase`) & `filesDir/audit_photos/` | **DELETE** local queued rows & local photo files where `clientId == targetClientId` | **DELETE** local draft rows & unshared draft photo files where `userId == targetUid` | Never deletes local files referenced by another client or submitted record. |
| **Firebase Authentication (`FirebaseAuth`)** | **PRESERVE** | **DELETE** `targetUid` via Mandatory Trusted Backend (`admin.auth().revokeRefreshTokens(targetUid)` + `admin.auth().deleteUser(targetUid)`) | Permanently removes Firebase Auth login identity. |
| **Google Drive (`AssetFlow/[Client Name]/`)** | **SEPARATE EXPLICIT OPT-IN CONFIRMATION**: Only trashes/deletes client Drive folder/files if Admin explicitly checks the external Drive deletion option, authorizes Drive, and backend verifies that `rootFolderId`/`masterDriveFileId` are not referenced by any other client. | **PRESERVE** (Client's Master Input workbook, Image Evidence, and submitted Daily Input Sheets in Google Drive are never deleted when a user is deleted). | Protects external Google Drive files and shared workbooks. |
| `/deletion_audit_events/{eventId}` | **APPEND** minimal `CLIENT_DELETION` record | **APPEND** minimal `USER_DELETION` record | Append-only compliance log (no PII, emails, or tokens). |

---

### Section 9: Trusted Backend Deletion Workflow, State Machine & Safeguards

```text
[Admin Selects Target in Control Panel & Reviews Scoped Impact Preview Counts]
       │  (Offers soft Archive Client / Deactivate User first)
       │  (Requires typing exact clientCode or user email + fresh auth check)
       ▼
[1. PendingConfirmation ──▶ 2. Queued (`/deletion_jobs/{jobId}`)]
       │  (For User Deletion: writes `/deleted_identities/{targetUid}` tombstone FIRST)
       │  (For Client Deletion: sets `clients/{targetClientId}.status = 'Deleting'` FIRST)
       ▼
[3. DeletingFirestore (Bounded 250-doc paginated batches with per-collection checkpoints)]
       ▼
[4. CleaningLocalAndPhotoMetadata (Purges scoped `asset_photos` metadata & local Room/filesDir cache)]
       ▼
[5A. User Deletion: DeletingAuthentication]          [5B. Client Deletion (Opt-In): DeletingExternalDrive]
     (Trusted Backend calls Firebase Admin SDK            (Verifies folder/file IDs are exclusive to
      `revokeRefreshTokens` + `deleteUser(targetUid)`)     `targetClientId`, then trashes/deletes via Drive API)
       │                                                    │
       └────────────────────────┬───────────────────────────┘
                                ▼
[6. Verifying (Re-queries all scoped collections to confirm 0 unauthorized residual documents)]
       ├── All steps verified ──▶ [7A. Completed] (Writes minimal `DeletionAuditEvent`)
       └── Transient failure  ──▶ [7B. PartiallyFailed] (Exposes idempotent "Resume & Retry Deletion" button)
```

---

### Section 10: Admin Workspace Modules (Home, Resources, Review, Control Panel)
- **Home (`AdminDashboardScreen.kt`)**: Displays active `(clientName, fiscalYearLabel)` context pill, **Switch Client** action, 5 real-time scoped KPI cards (`Active Auditors`, `Register Assets`, `Pending Review Sheets`, `Approved Sheets`, `Master Sync Alerts`), recent activity feed, and quick navigation links.
- **Resources (`ResourcesScreen.kt`)**:
  - **Tab 1 — Asset Register**: `.xlsx` picker, worksheet & header row selector, Unique Asset-Matching Key selector, pre-commit duplicate/validation report, batched Firestore `far_assets` importer, and optional Drive archive toggle.
  - **Tab 2 — Master Input Sheet**: Google Drive file picker/linker for the existing persistent Master `.xlsx`, worksheet & header row verifier, revision/MD5 status card, and **Verify & Reconcile Master Sheet** diagnostic action.
  - **Tab 3 — Input Form Designer**: Interactive 3-way mapping editor (`Source Register Header` ↔ `Form Field` ↔ `Destination Master Column`), automatic categorical dropdown value extractor from Excel, option manager (add/deactivate without altering historical records), live form preview, and versioned schema publisher.
- **Review (`AdminReviewQueueTab.kt`)**:
  - Lists submitted `DailySheetBatch` items with filters for User, Date, Approval Status, and Master Sync Status.
  - Provides the **3 mandatory primary actions**: **Review** (full-batch inspection modal with register vs. physical comparison and photo evidence viewer), **Send Remarks** (inline correction modal with required remarks and notification dispatch), and **Approve for Update to Master File** (records approval + triggers in-place Drive Master Workbook sync job).
- **Control Panel (`AdminControlPanelScreen.kt`)**:
  - Houses **User & Role Management** (including `Owner`-governed `Admin` appointment, `Review Authority`, `Publication Permission`, deactivation, and **Delete User**), **Client Management & Assignment** (including Create Client wizard, Auditor assignment matrix, and **Delete Client**), **Access Requests** inbox, **Google Drive & Settings**, and **Admin Logout**.

---

### Section 11: End User (Auditor) Workspace Modules (Home, Counting, Activity, Profile)
- **Home (`UserDashboardScreen.kt`)**: Displays assigned `(clientName, fiscalYearLabel)`, **Switch Client**, personal counting progress cards (`Register Assets`, `Counted Today`, `Drafts`, `Returned for Correction`, `Approved`), Today's Assignment filter shortcuts, and interactive **Notifications** list.
- **Counting (`AssetListScreen.kt` + `CountingFormScreen.kt`)**:
  - Scoped search & filter over Firebase `far_assets` (preserving leading zeros).
  - Inline **Not Found in Register → Manual Entry** transition when search yields 0 matches (auto-populating `Not Found in Asset Register` in Excel `Remarks`).
  - Dynamic **Physical Count Form** driven by the client's active `WorkbookSchemaVersion`, with camera/photo picker integration (`PhotoStorageManager`) and honest save/sync indicators (`Saved to Cloud`, `Saved Locally — Offline`, `Photo Pending Drive Upload`).
  - **Submit Daily Sheet** bar validating required fields, duplicates, and photo evidence before freezing the submitted version (`status = 'Awaiting'`).
- **Activity (`DailyInputSheetsScreen.kt` + `SubmissionHistoryScreen.kt`)**:
  - Date-wise **My Daily Sheets** list showing asset count, version, Approval Status, Drive Master Sync Status, Admin remarks, and **Request Admin Review** button.
  - **Submission History & Dual Correction Workflows**:
    - **Method A (Direct Record Correction)**: Open returned record in app, edit permitted fields, save new batch revision (`versionNumber + 1`), and resubmit the complete daily batch.
    - **Method B (Manual Excel Download & Re-Upload)**: Export the returned daily sheet `.xlsx`, edit in Excel, re-upload through the validator (checking batch ID, date, columns, Asset IDs, required fields, and duplicates), and resubmit as `versionNumber + 1`.
- **Profile (`ProfileScreen.kt`)**:
  - Editable **Full Name** and **Nickname**, read-only Role (`Auditor`), UID (with copy button), read-only Assigned Clients list, Firebase Auth password reset, and **End User Logout**.

---

### Section 12: Excel & Google Drive Integration Architecture
1. **Two Strict Workbook Roles**:
   - **Asset Register (Source Reference)**: Imported into Firebase Firestore (`far_assets`) for fast, offline-capable, client-scoped search and read-only reference prepopulation; never modified by counting submissions.
   - **Master Input Sheet (Persistent Output Workbook in Google Drive)**: Lives in Google Drive (`AssetFlow/[Client Name]/Master Input File/`). Updated **in place** after Admin approval by matching the unique asset key row and writing only approved mapped destination cells while preserving worksheets, headers, styles, formulas, and merged cells.
2. **Per-Client Google Drive Hierarchy**:
   ```text
   AssetFlow / [Client Name] /
   ├── Image Evidence / [YYYY-MM-DD] / {nickname}_{assetId}.jpg
   ├── Master Input File / [Client_Master_Input.xlsx]
   └── Daily Input Sheets / [Auditor Full Name] / [YYYY-MM-DD] / Daily_Input_v{N}.xlsx
   ```
3. **Photo Sharing & Metadata Policy**:
   - Uploaded evidence photos in `Image Evidence/[YYYY-MM-DD]/` receive Drive `appProperties` (`assetflowPhotoId`, `clientId`, `fiscalYearId`) and optional `Anyone with the link — Viewer` permission on the photo file only (never on the Master Input Sheet or Asset Register). If Google Workspace domain policy restricts public link sharing, the actual Drive API warning is surfaced honestly while preserving authenticated Drive preview links.

---

### Section 13: Firestore Security Rules & Backend Enforcement Architecture
*(See Section V below for the complete reconciled Firestore collection schema, rule invariants, and Mandatory Trusted Backend specification).*

---

### Section 14: Data Migration Strategy & Clean Production Separation
*(See Section VII below for the complete Development `DevelopmentMigrationManager` provenance, backup, quarantine, and rollback specification vs. 100% blank Production initialization).*

---

### Section 15: Removal of Legacy / Out-of-Scope Features
*(See Section II.3 below for the exact shared-code refactoring and 8 obsolete file deletions).*

---

### Section 16: Phased Implementation Roadmap
*(See Section VIII below for the complete dependency-ordered implementation phases).*

---

### Section 17: Verification & Testing Plan
*(See Section IX below for the complete test matrix covering authentication, RBAC, client isolation, Asset Register import, in-place Master Workbook patching, idempotency, concurrency, crash recovery, and permanent Client/User deletion).*

---

### Section 18: Configuration & Operational Prerequisites
*(See Section X and Section XI below for the mandatory Firebase Admin SDK backend deployment, billing prerequisites, OAuth setup, backup/recovery, and readiness checklists).*

---
---

# II. Verified Existing Implementation and Gap Analysis

To ensure zero assumptions and complete accuracy, the existing project files were inspected line by line. Below is the verified distinction between **Existing Working Code**, **Conflicting / Incomplete Code**, and **Missing Functionality**:

### II.1 Verified Codebase Facts vs. Required Target Behavior

| Subsystem | Verified Existing Implementation (Actual Codebase) | Gap / Technical Issue Identified | Required Implementation Change |
| :--- | :--- | :--- | :--- |
| **Firestore Root Path (`AuditRepository.kt`, `MainActivity.kt`, `firestore.rules`)** | `AuditRepository` stores all 17 collections under `/users/{workspaceUserId}/...`. However, `MainActivity.kt` (line 214) initializes `AuditRepository` with `currentUserId` (the logged-in user's own UID), whereas `AuditRepository.resolveWorkspaceOwnerUid()` checks a `SharedPreferences` key `shared_workspace_owner_uid` defaulting back to `currentUserId`. | On a second device or account, an Auditor reads/writes `/users/{auditorUid}/...` instead of the shared organization workspace `/users/{orgWorkspaceId}/...`, breaking multi-user collaboration and rule lookups. | Introduce a single deterministic organization workspace root resolver (`/system_directory/org_config` or `DESIGNATED_OWNER_UID` root `/users/{orgWorkspaceId}/...`) so all authenticated users in the organization read and write the same canonical `/users/{orgWorkspaceId}/...` collections, governed by `firestore.rules`. |
| **Master Input Workbook Sync (`AuditRepository.kt` lines 1825–1840, `ExcelWorkbookEngine.kt`)** | `AuditRepository.syncMasterWorkbookToDrive()` calls `AuditReportExportEngine.generateValidatedExcelBytes()`, which builds a brand-new 7-sheet `.xlsx` from scratch and uploads it over `masterWorkbookFileId`. | **Destroys the client's original Master Input `.xlsx` workbook** (wiping custom worksheets, `xl/styles.xml`, formulas `<f>`, merged cells `<mergeCells>`, and custom column layouts). | Replace scratch generation in the Master Sync flow with `ExcelWorkbookEngine.applyApprovedMappingsToExistingWorkbookInPlace()`: download the existing `.xlsx` from Google Drive (`OkHttpDriveTransport.downloadFile` already exists at line 293), locate the target worksheet and matching asset row, patch **only** the approved mapped destination cells in the worksheet XML while copying all other ZIP entries and XML nodes intact, and `PATCH` back to Drive. |
| **Asset Register Import (`ExcelWorkbookEngine.kt` lines 88–320)** | `importWorkbookFromStream()` parses a fixed 6-sheet Walton template (`Count Sheet` with 29 fixed column indices) into `FARAsset`. | Cannot import an arbitrary client's Asset Register `.xlsx` with custom sheet names or column orders, and does not store dynamic source headers (`rawRowByHeader`) or `clientId`/`fiscalYearId`. | Add `ExcelWorkbookEngine.inspectWorkbookStructure()` and `importClientAssetRegisterFromStream()` supporting configurable worksheet name, header row index, unique asset-matching key column, duplicate key detection, and storage of both standardized fields and `rawRowByHeader: Map<String, String>` + `mappedValuesByFieldId: Map<String, String>` in Firestore `far_assets`. |
| **Image Evidence (`PhotoStorageManager.kt`, `GoogleDriveService.kt`)** | `firebase-storage` is not present. Photos are saved locally to `context.filesDir/audit_photos/`, queued in Room (`OfflinePhotoQueueEntity`), uploaded to Drive via `GoogleDriveService.uploadStandardizedAssetPhoto`, and saved as metadata in Firestore `/users/{userId}/asset_photos/{photoId}`. | Complies with Decision D (zero Firebase Storage!), but lacks `clientId`/`fiscalYearId` fields, per-client Drive folder routing (`AssetFlow/[Client Name]/Image Evidence/[YYYY-MM-DD]/`), and UI indicators for local-only retention risk before Drive upload completes. | Add `clientId`, `fiscalYearId`, and `storageLocationState` to `AssetPhotoRecord` and `OfflinePhotoQueueEntity`; route Drive uploads into the active client's `Image Evidence` folder; display local-only backup status in Counting and Review UIs. |
| **Owner/Admin Bootstrap & Security Rules (`MainActivity.kt`, `firestore.rules`)** | `MainActivity.kt` displays a `"Request Admin Access"` dialog calling `bootstrapOwnerAdminRole()`. `firestore.rules` (lines 18–25, 198–204) grants Admin rights if `request.auth.uid == workspaceId && !hasAssignment` and allows any user to create their own `role_assignments` document with `role == 'Admin'`. | Critical privilege-escalation vulnerability: any user can self-promote to Admin. | Enforce one-time `DESIGNATED_OWNER_UID` bootstrap via `/system_config/ownership`; remove `"Request Admin Access"` dialog and role chips in `UserProfileDialog`; restrict `role_assignments` writes to `Owner` (or `Admin` with `canManageAdmins`) and the mandatory trusted backend. |
| **Startup Auto-Seeders (`MainActivity.kt` line 258, `AuditViewModel.kt` line 182)** | Automatically calls `importDefaultWorkbookIfEmpty()` and `seedInitial34DayPlanIfEmpty()` on startup. | Violates Decision G (Production must start with blank business data). | Delete `importDefaultWorkbookIfEmpty()` and `seedInitial34DayPlanIfEmpty()` and remove all startup auto-seeding calls. |

### II.2 Shared-Code Refactoring & Obsolete Files to Delete
Before deleting obsolete files, `ExcelWorkbookEngine.kt` currently imports `AuditReportExportEngine.validateGeneratedExcelStructure` (at lines 900–903).
1. **Step 1 — Move Shared Validator**: Move `validateGeneratedExcelStructure` and `ExcelStructureValidationReport` into `ExcelWorkbookEngine.kt`.
2. **Step 2 — Delete 8 Obsolete Source Files**:
   - `/app/src/main/java/com/example/domain/PlanningAndForecastEngine.kt`
   - `/app/src/main/java/com/example/data/export/AuditReportExportEngine.kt`
   - `/app/src/main/java/com/example/ui/screens/DashboardScreen.kt`
   - `/app/src/main/java/com/example/ui/screens/MasterDataScreen.kt`
   - `/app/src/main/java/com/example/ui/screens/ExceptionsAndFoundScreen.kt`
   - `/app/src/main/java/com/example/ui/screens/admin/AdminReviewRequestsTab.kt`
   - `/app/src/main/java/com/example/ui/screens/DriveScreen.kt`
   - `/app/src/main/java/com/example/ui/screens/UsersScreen.kt`

---

# III. Updated Technical Architecture and Data-Storage Responsibilities (Decisions A–I)

### III.1 Authoritative Data-Storage Responsibility Matrix

| Data Domain / Artifact | Firebase Firestore (`/users/{orgId}/...`) | Google Drive (`AssetFlow/[Client Name]/...`) | Device-Local Storage (`Room` / `filesDir`) | Mandatory Trusted Backend (`Firebase Admin SDK`) | Firebase Storage |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **A. Asset Register (`.xlsx` & Parsed Assets)** | **REQUIRED PRIMARY STORE**: Stores parsed, structured `FARAsset` records in `/far_assets/{assetDocId}` scoped by `(clientId, fiscalYearId)`, including core fields, `rawRowByHeader`, `mappedValuesByFieldId`, and `searchTokens` for Auditor search & form prepopulation. | **OPTIONAL ARCHIVE**: Original uploaded Asset Register `.xlsx` may be archived under `AssetFlow/[Client Name]/Asset Register/` if Drive is connected. | Temporary file stream during `.xlsx` parsing; Firestore offline cache for assigned scope. | Optional server-side batch validation/import helper for very large registers. | **NEVER USED** |
| **B. Auditor Inputs, Drafts, Daily Sheets & Approvals** | **REQUIRED PRIMARY STORE**: Stores `/counting_records`, `/daily_batches`, `/submissions`, `/exceptions`, `/found_not_in_far`, and `/activity_logs` with strict UID, role, `(clientId, fiscalYearId)` scope, version history, and approval metadata. | Optional exported daily sheet `.xlsx` under `Daily Input Sheets/[Auditor]/[YYYY-MM-DD]/` upon publication. | `OfflineQueuedRecordEntity` queues offline draft saves until network restores. | Enforces server-side approval verification and triggers Master Workbook sync jobs. | **NEVER USED** |
| **C. Master Input Excel Workbook (`.xlsx`)** | **METADATA & SYNC LEDGER ONLY**: Stores `/master_workbook_state/{clientId}_{fyId}` and `/master_sync_jobs/{jobId}` (Drive `fileId`, target sheet, header row, matching key, destination column mappings, Drive revision/MD5, and `appliedSubmissionLedger`). **Never stores duplicate `.xlsx` binary or full workbook rows.** | **PRIMARY AUTHORITATIVE BINARY FILE**: The existing persistent Master Input `.xlsx` workbook resides in `AssetFlow/[Client Name]/Master Input File/`. Updated **in place** after Admin approval. | Ephemeral temp file (`cacheDir/master_patch_{jobId}.xlsx`) during in-place XML cell patching only; deleted immediately after upload. | Coordinates durable `MasterWorkbookSyncJob` execution, per-client lease lock, and Drive OAuth token refresh. | **NEVER USED** |
| **D. Image Evidence (Photos)** | **METADATA ONLY**: Stores `/asset_photos/{photoId}` (`clientId`, `fiscalYearId`, `assetKey`, `capturedByUid`, `standardizedFileName`, `driveFileId`, `driveWebViewLink`, `storageLocationState`). **Never stores image bytes.** | **PRIMARY REMOTE BINARY STORE**: Stores image files in `AssetFlow/[Client Name]/Image Evidence/[YYYY-MM-DD]/{nickname}_{assetId}.jpg`. | **INITIAL CAPTURE STORE**: Stores captured image files in `context.filesDir/audit_photos/` + `OfflinePhotoQueueEntity` until verified uploaded to Google Drive. | N/A | **NEVER USED** (No Storage SDK or Storage rules) |
| **E. Roles, Ownership, Assignments & Access Requests** | **PRIMARY AUTHORITATIVE STORE**: `/system_config/ownership`, `/role_assignments/{uid}`, `/client_assignments/{clientId}_{uid}`, `/access_requests/{id}`, `/deleted_identities/{uid}`. | N/A | Cached active session state (cleared on logout or client switch). | Sets Firebase Auth Custom Claims and enforces Owner/Admin governance. | **NEVER USED** |
| **F. Permanent Client & User Deletion** | Stores `/deletion_jobs/{jobId}`, `/deleted_identities/{uid}`, and append-only `/deletion_audit_events/{eventId}`. | Separate opt-in external deletion for exclusive Client folders only; preserved on User deletion. | Purges target-scoped Room rows and `filesDir/audit_photos` files. | **MANDATORY**: Executes multi-batch Firestore cleanup and calls `admin.auth().deleteUser(targetUid)`. | **NEVER USED** |

---

### III.2 Detailed Design for Each Finalized Decision (A–I)

#### A. Asset Register Storage & Re-Import Architecture
1. **Configurable Source Headers & Matching Key**:
   - When an Admin selects an Asset Register `.xlsx` in `Resources → Asset Register`, `ExcelWorkbookEngine.inspectWorkbookStructure()` reads all sheet names and candidate header rows.
   - The Admin selects the source worksheet, header row number, and the **Unique Asset-Matching Key Column** (defaulting to `Asset ID` / `Tag Number`), and maps source headers to AssetFlow fields.
2. **Pre-Commit Validation & Duplicate Detection**:
   - Before writing to Firestore, the parser scans all countable rows and checks for:
     - Blank matching keys (flagged as `MISSING_ASSET_KEY`, skipping non-countable G/L total rows).
     - Duplicate matching keys within the same register worksheet. If duplicates exist, the Admin is shown the exact row numbers and must choose either **Composite Key Mode** (combining `Asset ID + Sub-Number / Source Row`) or **Abort & Fix Source Workbook**.
3. **Firestore Document Structure & Bounded Batching**:
   - Each asset row is stored in `/users/{orgId}/far_assets/${clientId}_${fiscalYearId}_${sanitizedKey}_${sourceRow}` (~1.5 KB to 3 KB per document, well under Firestore's 1 MiB limit).
   - Writes execute in **bounded batches of 250 documents per `WriteBatch`** with progress checkpoints (`importedCount / totalRows`) so registers with 5,000–50,000+ assets import reliably without hitting Firestore's 500-operation or 10 MiB batch limits.
4. **Controlled Re-Imports**:
   - Re-importing an updated Asset Register updates existing `far_assets` documents in place by `matchingKeyValue` without breaking existing `counting_records`. Any asset removed in the new workbook that already has submitted/approved counts is retained with `registerStatus = "ARCHIVED_HAS_COUNTS"` to preserve audit integrity.

#### B. Auditor Input, Versioned Daily Submissions & Approval Gate
1. **Strict Ownership & Scope**:
   - Every `CountingRecord` and `DailySheetBatch` carries `clientId`, `fiscalYearId`, `countedByUserId` / `submitterUid`, `countedBy` / `submitterFullName` (authenticated **Full Name** snapshot), `configVersion`, and `versionNumber`.
2. **Large Daily Submission Scaling (Firestore 1 MiB Document Limit Protection)**:
   - Currently, `DailySheetBatch` serializes all records into `recordsSnapshotJson` inside a single Firestore document. If an Auditor counts 400+ assets in one day, a single inline JSON string could approach Firestore's 1 MiB document limit.
   - **Solution**: Keep `recordsSnapshotJson` for normal batches (≤150 rows) AND store immutable versioned row chunks in `/users/{orgId}/daily_batches/{batchId}/version_chunks/{versionNumber}_chunk_{index}` (up to 100 rows per chunk document) whenever a daily sheet exceeds 150 records.
3. **Approval Gate**:
   - Only records with `approvalStatus == "APPROVED"` (set by an authorized `Admin`, `Owner`, or `Publication-Authorized Reviewer` who is not the submitter) can enqueue a `MasterWorkbookSyncJob`.

#### C. Master Input Excel Workbook — Google Drive Primary Store & Metadata-Only Firebase State
- The persistent Master Input `.xlsx` workbook remains in Google Drive (`masterDriveFileId`).
- Firebase stores **only** `/users/{orgId}/master_workbook_state/{clientId}_{fiscalYearId}` and `/users/{orgId}/master_sync_jobs/{jobId}` (containing the Drive file ID, target worksheet name, header row index, asset-matching column header, destination column mappings, Drive `headRevisionId` / `md5Checksum`, lease lock, and `appliedSubmissionLedger`).
- **Approval status (`daily_batches.status = 'Approved'`)** and **Master Workbook synchronization status (`daily_batches.masterSyncStatus = 'Pending' | 'Processing' | 'Succeeded' | 'FailedRetryable' | 'FailedActionRequired'`)** are stored and displayed as two distinct statuses in both Admin and Auditor UIs.

#### D. Image Evidence — Device-Local + Google Drive + Firebase Metadata Only
- **Zero Firebase Storage**: No image binaries are ever uploaded to Firebase Storage. No Firebase Storage SDK (`firebase-storage`) or `storage.rules` are added.
- **Capture & Offline Queue**: `PhotoStorageManager` writes captured photos to `context.filesDir/audit_photos/{nickname}_{assetId}[_N].jpg` and inserts an `OfflinePhotoQueueEntity` row in Room with `clientId` and `fiscalYearId`.
- **Google Drive Upload & Verification**: `GoogleDriveService.uploadStandardizedAssetPhoto()` uploads the binary to `AssetFlow/[Client Name]/Image Evidence/[YYYY-MM-DD]/{fileName}` with `appProperties = { "assetflowPhotoId": photoId, "clientId": clientId, "fiscalYearId": fiscalYearId }`. Only after Google Drive returns a valid HTTP 200/201 response with a non-empty `id` does Firestore `/users/{orgId}/asset_photos/{photoId}` record `driveFileId` and `storageLocationState = "UPLOADED_TO_DRIVE"`.
- **Safe Local Retention & Cleanup**: Local files in `filesDir/audit_photos/` are **never** auto-purged while `driveFileId` is empty. The UI displays a clear `"Local Only — Pending Drive Upload"` badge until Drive verification succeeds.

---

# IV. Implementation Design for Each Original Module (Full Traceability)

Below is the module-by-module implementation design mapping every module in the Original Complete Application Plan to its verified codebase status, required changes, data/storage dependencies, security rules, acceptance tests, and implementation phase:

### Module 1: Welcome, Login, Registration & Role Mismatch Gate (`AuthScreen.kt`, `MainActivity.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (Google Sign-In via `CredentialManager` and Email/Password login exist in `AuthScreen.kt`, but Account Type selector cards, role-mismatch blocking, and tombstone checks are missing; `MainActivity.kt` has an insecure `"Request Admin Access"` self-promotion dialog).
- **Required Code/UI Changes**:
  - Add `Admin` and `End User (Auditor)` Account Type selection cards on `AuthScreen.kt` (keeping the clean NovaDesk light aesthetic `#F4F5FF` / `#8B79FF` with zero glassmorphism).
  - Remove the `"Request Admin Access"` dialog from `MainActivity.kt`.
  - Add post-auth gate in `MainActivity.kt` / `AuditViewModel.kt` verifying `/deleted_identities/{uid}`, `/role_assignments/{uid}`, and checking that selecting `Admin` fails with a clear Role Mismatch screen if the user's server role is `Auditor`.
- **Data & Storage Dependencies**: Firebase Auth, `/users/{orgId}/system_config/ownership`, `/users/{orgId}/role_assignments/{uid}`, `/users/{orgId}/deleted_identities/{uid}`, `/users/{uid}/user_profile/main`.
- **Security & Validation**: Enforce `isNotDeletedUser()` and server role lookup in `firestore.rules`; newly registered users default to `role = "Auditor"`, `accountStatus = "Active"`, with `0` client assignments.
- **Acceptance Tests**: `AUTH-01`, `AUTH-02`, `AUTH-03`, `SEC-BOOT-01`, `SEC-BOOT-02`, `SEC-ROLE-01`.
- **Implementation Phase**: **Phase 2**.

### Module 2: Client Selection, Fiscal Year Selection & Calm Access Request Flow (`ClientSelectionScreen.kt`)
- **Existing Status**: `MISSING` (No client/fiscal-year selector screen exists yet).
- **Required Code/UI Changes**:
  - Create `/app/src/main/java/com/example/ui/screens/ClientSelectionScreen.kt`.
  - For `Owner` / `Admin`: display Client dropdown, Fiscal Year dropdown, **Continue**, and **"+ Create New Client"** setup wizard dialog.
  - For `Auditor`: display only active assigned clients/fiscal years from `/client_assignments`. If `0` clients are assigned (or an unassigned client is selected from the directory), render an inline **"No Client Assigned / Request Access"** card that creates `/access_requests/${clientId}_${fiscalYearId}_${uid}` without any recurring modal popups.
  - Wire **Switch Client** in Admin Home and Auditor Home/Profile to clear active client state in `AuditViewModel` and return to `ClientSelectionScreen` without logging out.
- **Data & Storage Dependencies**: `/clients/{clientId}`, `/client_directory/{clientId}`, `/fiscal_years/{fyId}`, `/client_assignments/${clientId}_${uid}`, `/access_requests/${clientId}_${fyId}_${uid}`.
- **Security & Validation**: `firestore.rules` verifies `isAssignedToClientAndYear(orgId, clientId, fyId)` before allowing any business collection read/write; `access_requests` enforces deterministic document ID to prevent duplicate pending requests.
- **Acceptance Tests**: `CLIENT-01`, `CLIENT-02`, `CLIENT-03`, `SESSION-01`, `SEC-ISO-01` through `SEC-ISO-05`.
- **Implementation Phase**: **Phase 2**.

### Module 3: Admin Workspace — Home (`AdminDashboardScreen.kt`, `AdminDashboardViewModel.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (Exists in `AdminDashboardScreen.kt`, but queries unscoped collections and uses 5-tab navigation).
- **Required Code/UI Changes**:
  - Scope all dashboard metrics and the Recent Activity Log to `(activeClientId, activeFiscalYearId)`.
  - Display active Client & Fiscal Year badge with **Switch Client** button in the top bar.
  - Show scoped cards for Active Auditors, Register Assets, Pending Reviews, Approved Sheets, and Master Workbook Sync Failures.
- **Data & Storage Dependencies**: `/far_assets`, `/daily_batches`, `/master_sync_jobs`, `/activity_logs`, `/client_assignments`.
- **Security & Validation**: Admin/Owner read access verified via `isAdminOrOwner(orgId)`.
- **Acceptance Tests**: `LOG-01`, `SESSION-01`.
- **Implementation Phase**: **Phase 4**.

### Module 4: Admin Workspace — Resources (`ResourcesScreen.kt`, `ExcelWorkbookEngine.kt`, `DynamicSchemaEngine.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (`ResourcesScreen.kt` exists with tabs for Register, Master Sheet, Mapping, and Fields, but `ExcelWorkbookEngine` assumes a fixed 29-column sheet and `SchemaFieldConfig` lacks 3-way `sourceRegisterHeader` vs. `destinationMasterHeader` separation).
- **Required Code/UI Changes**:
  - **Asset Register Sub-tab**: Add worksheet/header inspection (`inspectWorkbookStructure`), Unique Asset-Matching Key selection, duplicate/missing key validation summary, and bounded 250-doc batch import into `/far_assets` with `rawRowByHeader` and `mappedValuesByFieldId`.
  - **Master Input Sheet Sub-tab**: Link existing Google Drive Master `.xlsx` (`masterDriveFileId`), select target worksheet and header row, save metadata to `/master_workbook_state/{clientId}_{fyId}`, and provide **Verify & Reconcile Master Sheet**.
  - **Input Form Designer Sub-tab**: Implement 3-way mapping (`sourceRegisterHeader` → `SchemaFieldConfig` → `destinationMasterHeader`), deterministic categorical dropdown value extraction from mapped Excel columns, dropdown option add/deactivate management (preserving historical values), form preview, and versioned configuration publishing (`WorkbookSchemaVersion`).
- **Data & Storage Dependencies**: `/far_assets`, `/master_workbook_state`, `/schema_versions`, `/column_mapping`, Google Drive REST API v3.
- **Security & Validation**: Only `Owner`/`Admin` can import registers, configure Master workbook metadata, or publish schema versions; publishing is blocked if required mappings or the unique matching key are missing.
- **Acceptance Tests**: `RES-01`, `MAP-01`, Required Tests #11, #12, #13, #27.
- **Implementation Phase**: **Phase 3**.

### Module 5: Admin Workspace — Review Queue & Batch Review Detail (`AdminReviewQueueTab.kt`, `AuditRepository.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (`AdminReviewQueueTab.kt` exists, but lacks `(clientId, fiscalYearId)` filtering, separate Approval vs. Master Sync status badges, and currently overwrites the Master `.xlsx` from scratch on approval).
- **Required Code/UI Changes**:
  - Filter submitted `daily_batches` by `(activeClientId, activeFiscalYearId)` plus Auditor, Date, Approval Status, and Master Sync Status.
  - Enforce the **exact three primary actions** on each Daily Input Sheet card:
    1. **Review** (opens full-batch detail dialog showing all asset rows, register vs. counted values, version number, and photo evidence links).
    2. **Send Remarks** (inline dialog requiring remarks, setting `status = 'Returned'`, preserving the submitted snapshot, and creating an in-app notification for the Auditor).
    3. **Approve for Update to Master File** (confirmation dialog checking `hasPublishPermission` and ensuring reviewer != submitter; records `approvalStatus = 'APPROVED'` with reviewer's authenticated **Full Name** and enqueues `MasterWorkbookSyncJob` for in-place Google Drive Master `.xlsx` update).
  - Display distinct **Approval Status** (`Approved`) and **Master Workbook Sync Status** (`Pending`, `Processing`, `Succeeded`, `Failed — Retry Available`, `Action Required`) with an idempotent **Retry Master Sync** button.
- **Data & Storage Dependencies**: `/daily_batches`, `/counting_records`, `/master_workbook_state`, `/master_sync_jobs`, `/notifications`, `/activity_logs`, Google Drive REST API v3.
- **Security & Validation**: Separation of duties (`submitterUid != request.auth.uid` for regular reviewers), `Review Authority` vs. `Publication Permission` enforcement, idempotency check via `appliedSubmissionLedger`.
- **Acceptance Tests**: `REV-01`, `REV-02`, `DRIVE-01`, `DRIVE-02`, `SYNC-IDEM-01` through `SYNC-SEP-08`, Required Tests #3, #4, #16, #19, #24, #25, #26, #30.
- **Implementation Phase**: **Phase 3 & Phase 4**.

### Module 6: Admin Workspace — Control Panel (`AdminControlPanelScreen.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (`AdminControlPanelScreen.kt`, `DriveScreen.kt`, and `UsersScreen.kt` exist separately, lacking Client Assignment, Access Request review, Owner-governed Admin management, and permanent Client/User deletion).
- **Required Code/UI Changes**:
  - Consolidate all 5 Control Panel subsections into `AdminControlPanelScreen.kt` (and delete standalone `DriveScreen.kt` and `UsersScreen.kt`):
    1. **User & Role Management**: Search users, manage `Admin` role (`Owner`-controlled), toggle `Review Authority` and `Publication Permission` separately, activate/deactivate users, and execute **Delete User** (with impact preview, typed confirmation, tombstone, draft purge, submitted-record pseudonymization, and backend Firebase Auth `deleteUser`).
    2. **Client Management & Client Assignment**: Create/edit/archive clients & fiscal years, assign/unassign Auditors to clients with pending-draft warnings, and execute **Delete Client** (with impact preview, typed confirmation, target-scoped Firestore/local purge, and separate opt-in exclusive Google Drive deletion).
    3. **Access Requests**: Approve (auto-creating `/client_assignments/${clientId}_${uid}`) or Reject pending Auditor requests with reason and notification.
    4. **Google Drive & Settings**: Per-client Drive OAuth connection, folder provisioning (`Image Evidence`, `Master Input File`, `Daily Input Sheets`), **Test Connection**, and **Disconnect**.
    5. **Admin Logout**: Sign out and clear session state.
- **Data & Storage Dependencies**: `/role_assignments`, `/user_profile`, `/clients`, `/fiscal_years`, `/client_assignments`, `/access_requests`, `/deletion_jobs`, `/deleted_identities`, `/deletion_audit_events`, Mandatory Trusted Backend (`functions/`).
- **Security & Validation**: `Owner` protection (`targetUid != ownerUid`), self-deletion block (`targetUid != callerUid`), server-enforced permission checks.
- **Acceptance Tests**: `LOG-01`, `CLIENT-02`, `CLIENT-03`, `DEL-ISO-01` through `DEL-AUD-12`, Required Tests #1, #5.
- **Implementation Phase**: **Phase 4**.

### Module 7: Auditor Workspace — Home (`UserDashboardScreen.kt`, `NotificationsScreen.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (`UserDashboardScreen.kt` and `NotificationsScreen.kt` exist as separate tabs without `(clientId, fiscalYearId)` scoping).
- **Required Code/UI Changes**:
  - Display active `(clientName, fiscalYearLabel)` and **Switch Client** button.
  - Show scoped workload/progress cards (`Register Assets`, `Counted Today`, `Drafts`, `Correction Required`, `Daily Sheet Status`) and embed the real-time **Notifications** feed (clicking a notification opens the referenced returned/approved sheet).
- **Data & Storage Dependencies**: `/far_assets`, `/counting_records`, `/daily_batches`, `/notifications`.
- **Security & Validation**: Scoped strictly to `activeClientId`, `activeFiscalYearId`, and `request.auth.uid`.
- **Acceptance Tests**: `SESSION-01`, Required Test #19.
- **Implementation Phase**: **Phase 4**.

### Module 8: Auditor Workspace — Counting, Search, In-Workflow Manual Entry & Photo Evidence (`AssetListScreen.kt`, `CountingFormScreen.kt`, `PhotoStorageManager.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (`AssetListScreen.kt`, `CountingFormScreen.kt`, `ManualObservationFormScreen.kt`, and `PhotoStorageManager.kt` exist, but lack `(clientId, fiscalYearId)` scoping and local-only photo retention indicators).
- **Required Code/UI Changes**:
  - Scope `AssetListScreen` search and filters (`Department`, `Location`, `Asset Class`, `Count Status`) to Firebase `far_assets` for `(activeClientId, activeFiscalYearId)`, preserving leading zeros in Asset IDs.
  - When a search returns 0 matches, display the inline **Manual Entry (Not Found in Asset Register)** action directly inside `AssetListScreen` → `CountingFormScreen` (no separate Manual Entry tab), automatically injecting `Not Found in Asset Register` into the Excel `Remarks` field and preserving that marker across edits and resubmissions.
  - Prepopulate read-only `Fixed` fields from `farAsset.mappedValuesByFieldId`, render configured `Dropdown`, `Automatic`, and `Manual` fields, populate `Counted By` with the authenticated Auditor's **Full Name**, and attach standardized photos (`{nickname}_{assetId}[_N].jpg`) via `PhotoStorageManager` + `GoogleDriveService`.
  - Display honest save/upload badges (`Saved to Cloud`, `Saved Locally — Offline`, `Photo Local Only — Pending Drive Upload`).
- **Data & Storage Dependencies**: `/far_assets`, `/counting_records`, `/daily_batches`, `/asset_photos`, `OfflineSyncDatabase`, `filesDir/audit_photos`, Google Drive `Image Evidence/`.
- **Security & Validation**: Auditor can only create/update own records in `Draft` or `Returned` status for an assigned `(clientId, fiscalYearId)`.
- **Acceptance Tests**: `COUNT-01`, `COUNT-02`, `EVID-01`, `SUB-01`, Required Tests #6, #7, #8, #9, #10, #15, #18, #28, #29.
- **Implementation Phase**: **Phase 3 & Phase 4**.

### Module 9: Auditor Workspace — Activity, Submission History & Dual Correction Workflows (`DailyInputSheetsScreen.kt`, `SubmissionHistoryScreen.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (`DailyInputSheetsScreen.kt` and `SubmissionHistoryScreen.kt` exist as separate bottom tabs; need consolidation under the `Activity` tab and scoping by `(clientId, fiscalYearId)`).
- **Required Code/UI Changes**:
  - Combine **My Daily Sheets** and **Submission History** into a clean segmented view under the `Activity` tab.
  - Show distinct **Approval Status** (`Awaiting`, `Returned`, `Approved`, `Rejected`) and **Master Workbook Sync Status** (`Pending`, `Succeeded`, `Sync Failed`).
  - Support **Request Admin Review** on eligible pending sheets (preventing duplicate open requests).
  - Support both mandatory correction methods on `Returned` sheets while preserving earlier versions (`versionNumber + 1` under the same `batchId`):
    - **Method A — Direct Record Correction** in `CountingFormScreen`.
    - **Method B — Manual Excel Correction** (Download returned daily `.xlsx` → edit in Excel → re-upload & validate batch ID, date, columns, Asset IDs, required fields, and duplicates → resubmit complete batch).
- **Data & Storage Dependencies**: `/daily_batches`, `/counting_records`, `/submissions`, `ExcelWorkbookEngine`.
- **Security & Validation**: Auditors cannot read another Auditor's private drafts or approve their own submissions.
- **Acceptance Tests**: `REV-01`, Required Tests #2, #20, #21, #22, #23.
- **Implementation Phase**: **Phase 4**.

### Module 10: Auditor Workspace — Profile & Session Management (`ProfileScreen.kt`)
- **Existing Status**: `PARTIALLY IMPLEMENTED` (`ProfileScreen.kt` exists; `UserProfileDialog` in `MainActivity.kt` currently allows role self-editing which must be removed).
- **Required Code/UI Changes**:
  - Allow editing **Full Name** and **Nickname** (ensuring historical `Counted By` / `Reviewed By` snapshots on submitted records are never rewritten by later profile edits).
  - Display read-only Role, Firebase UID, and read-only Assigned Clients list with a **Switch Client** shortcut.
  - Provide Firebase Auth password reset/change and **End User Logout** (clearing Credential Manager state and cached client data).
- **Data & Storage Dependencies**: `/users/{uid}/user_profile/main`, `/role_assignments/{uid}`, `/client_assignments`, `FirebaseAuth`, `CredentialManager`.
- **Security & Validation**: `user_profile` updates cannot modify `role` or `uid`.
- **Acceptance Tests**: `SESSION-02`, Required Tests #14, #17.
- **Implementation Phase**: **Phase 2 & Phase 4**.

---

# V. Database Schema, Security Rules and Mandatory Trusted Backend

### V.1 Reconciled Firestore Path Structure
To preserve compatibility with the existing codebase and `firestore.rules` while fixing the multi-user workspace isolation bug:
- All shared organization collections live under `/users/{orgId}/...` where `orgId` is the canonical organization workspace ID (resolved from `/system_directory/org_config.orgWorkspaceId`, initialized to `DESIGNATED_OWNER_UID`).
- Personal user profiles remain at `/users/{uid}/user_profile/main`.
- Every business document under `/users/{orgId}/{collection}/{docId}` carries `clientId: String` and `fiscalYearId: String`, and every query in `AuditRepository.kt` includes `.whereEqualTo("clientId", activeClientId).whereEqualTo("fiscalYearId", activeFiscalYearId)`.

### V.2 Firestore Security Rules (`firestore.rules`) — Core Invariants
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    function isSignedIn() {
      return request.auth != null;
    }

    // Locked to the project owner's immutable Firebase UID
    function designatedOwnerUid() {
      return "REPLACE_WITH_DESIGNATED_OWNER_FIREBASE_UID";
    }

    function isNotDeleted(orgId) {
      return isSignedIn() &&
        !exists(/databases/$(database)/documents/users/$(orgId)/deleted_identities/$(request.auth.uid));
    }

    function isOwner(orgId) {
      return isNotDeleted(orgId) &&
        exists(/databases/$(database)/documents/users/$(orgId)/system_config/ownership) &&
        get(/databases/$(database)/documents/users/$(orgId)/system_config/ownership).data.ownerUid == request.auth.uid &&
        get(/databases/$(database)/documents/users/$(orgId)/role_assignments/$(request.auth.uid)).data.role == 'Owner' &&
        get(/databases/$(database)/documents/users/$(orgId)/role_assignments/$(request.auth.uid)).data.accountStatus == 'Active';
    }

    function isAdminOrOwner(orgId) {
      return isNotDeleted(orgId) &&
        exists(/databases/$(database)/documents/users/$(orgId)/role_assignments/$(request.auth.uid)) &&
        get(/databases/$(database)/documents/users/$(orgId)/role_assignments/$(request.auth.uid)).data.accountStatus == 'Active' &&
        (get(/databases/$(database)/documents/users/$(orgId)/role_assignments/$(request.auth.uid)).data.role == 'Owner' ||
         get(/databases/$(database)/documents/users/$(orgId)/role_assignments/$(request.auth.uid)).data.role == 'Admin');
    }

    function isAssignedAuditor(orgId, clientId, fiscalYearId) {
      let assignPath = /databases/$(database)/documents/users/$(orgId)/client_assignments/$(clientId + "_" + request.auth.uid);
      return isNotDeleted(orgId) &&
        exists(assignPath) &&
        get(assignPath).data.active == true &&
        (get(assignPath).data.fiscalYearIds.hasAny([fiscalYearId, "*"]));
    }
  }
}
```
- **One-Time Bootstrap Rule**: `/users/{orgId}/system_config/ownership` permits `allow create` **if and only if** `request.auth.uid == designatedOwnerUid()` and `!exists(.../system_config/ownership)`. `allow update, delete: if false;`.
- **Zero Self-Promotion Rule**: `/users/{orgId}/role_assignments/{targetUid}` permits `create`/`update` of `role == 'Admin'` **only** if `isOwner(orgId)` (or `isAdminOrOwner(orgId)` with `canManageAdmins == true` and `targetUid != ownerUid`). Ordinary Auditors can only initialize their own default `Auditor` role entry once with `role == 'Auditor'` and `accountStatus == 'Active'`.
- **Strict Scope Isolation Rule**: Reading `/far_assets`, `/schema_versions`, or `/column_mapping` requires `isAdminOrOwner(orgId) || isAssignedAuditor(orgId, resource.data.clientId, resource.data.fiscalYearId)`. Creating or updating `/counting_records` or `/daily_batches` as an Auditor requires `isAssignedAuditor(orgId, request.resource.data.clientId, request.resource.data.fiscalYearId)`, `request.resource.data.submitterUid == request.auth.uid`, and `request.resource.data.status in ['Draft', 'Awaiting']`.

### V.3 Mandatory Trusted Backend (`functions/` — Firebase Admin SDK)
> [!IMPORTANT]
> **The Trusted Backend is MANDATORY, NOT optional (Decision H).** Firestore Security Rules alone only authorize or reject client Firestore reads/writes; they **cannot** delete Firebase Authentication accounts (`admin.auth().deleteUser`), revoke OAuth/refresh tokens, set Firebase Auth Custom Claims, or execute durable background multi-batch jobs.

#### Mandatory Cloud Functions Endpoints (`/functions/src/index.ts`)
1. `bootstrapInitialOwner` (HTTPS Callable):
   - Verifies `context.auth.uid === process.env.DESIGNATED_OWNER_UID` and `context.auth.token.email_verified === true`.
   - Atomically creates `/system_config/ownership` and `/role_assignments/{ownerUid}` (`role: "Owner"`), sets Firebase Auth Custom Claims `{ role: "Owner", owner: true, active: true }`, and writes an immutable `OWNER_BOOTSTRAP` audit event.
2. `manageUserRoleAndPermissions` (HTTPS Callable):
   - Verifies caller is `Owner` (or `Admin` with `canManageAdmins` when managing `Admin`/`Auditor` roles). Blocks any modification to `ownerUid`.
   - Updates `/role_assignments/{targetUid}`, updates Firebase Auth Custom Claims (`admin.auth().setCustomUserClaims`), and revokes refresh tokens (`admin.auth().revokeRefreshTokens(targetUid)`) when a user is demoted or deactivated so the change takes effect immediately.
3. `executeClientDeletionJob` (HTTPS Callable + Firestore Trigger on `/deletion_jobs/{jobId}`):
   - Verifies caller is `Owner` or `Admin` with `canDeleteClients: true`, verifies fresh authentication (`auth_time < 5 minutes`), and validates `targetClientId`.
   - Executes paginated 250-doc batch deletes across all collections where `clientId == targetClientId`, updates per-collection checkpoints in `/deletion_jobs/{jobId}`, optionally trashes exclusive Google Drive folders when explicitly requested, verifies 0 residual documents, and writes a minimal `DeletionAuditEvent`.
4. `executeUserDeletionJob` (HTTPS Callable + Firestore Trigger on `/deletion_jobs/{jobId}`):
   - Verifies caller is authorized (`Owner` for `Admin`/`Auditor` targets; `Admin` with `canDeleteUsers` for `Auditor` targets; never `targetUid == ownerUid` or `targetUid == callerUid`).
   - Writes `/deleted_identities/{targetUid}` tombstone first, revokes refresh tokens, deletes unsubmitted drafts, pseudonymizes submitted/approved client records (`"Deleted User [#" + shortHash + "]"`), deletes `/user_profile/main`, `/client_assignments`, `/access_requests`, `/notifications`, and `/role_assignments/{targetUid}`, and calls **`await admin.auth().deleteUser(targetUid)`** (treating `auth/user-not-found` as idempotent success on retry).
5. `executeMasterWorkbookSyncJob` (HTTPS Callable + Firestore Trigger on `/master_sync_jobs/{jobId}`):
   - Executes server-coordinated in-place patching of the Google Drive Master Input `.xlsx` workbook using the per-client lease lock, Drive revision/MD5 check, and idempotency ledger described in Section VI.

---

# VI. Google Drive Master Workbook Synchronization (In-Place Patching, Concurrency & Crash Recovery)

### VI.1 Complete 12-Step Approval-to-Master-Workbook In-Place Update Workflow

```text
[1. Auditor searches Firebase `far_assets` (scoped by clientId + fiscalYearId)]
       │
       ▼
[2. App loads asset reference data & active 3-way `WorkbookSchemaVersion`]
       │
       ▼
[3. Auditor enters verification fields (`Found?`, `Physical Qty`, `Condition`, etc.) & photos]
       │
       ▼
[4. Saved/submitted to Firebase (`counting_records`, `daily_batches`) with `status = Awaiting`]
       │
       ▼
[5. Admin inspects sheet in `Review` & taps "Approve for Update to Master File"]
       │  ├── Commits `approvalStatus = APPROVED`, `reviewedBy = Admin Full Name`, `approvedAtIso`
       │  └── Creates durable `/master_sync_jobs/{jobId}` (`status = PENDING`, `submissionRevisionKey`)
       ▼
[6. Sync Engine acquires per-client lease on `/master_workbook_state/{clientId}_{fyId}`]
       │  └── Checks `appliedSubmissionLedger[submissionRevisionKey]` & Drive `appProperties`
       ▼
[7. Fetches Drive metadata (`headRevisionId`, `md5Checksum`) & downloads Master `.xlsx` binary]
       │
       ▼
[8. Opens `.xlsx` ZIP & parses `targetWorksheetName` XML]
       │  ├── Verifies `headerRowNumber`, `assetMatchingMasterColumnHeader`, & mapped destination headers
       │  ├── Locates row where `cellValue(matchingKeyCol) == approvedRecord.matchingKeyValue`
       │  └── HALT WITHOUT WRITING if:
       │        • Worksheet or mapped header is missing (`WORKSHEET_OR_HEADER_MISSING`)
       │        • 0 rows match `matchingKeyValue` (`ASSET_ROW_NOT_FOUND`)
       │        • >1 rows match `matchingKeyValue` (`DUPLICATE_ASSET_ROW_AMBIGUITY`)
       │        • Target cell contains formula `<f>` and overwrite is disabled (`FORMULA_CELL_PROTECTED`)
       ▼
[9. Patches ONLY approved mapped destination `<c>` cells in the matched `<row>`]
       │  (Copies all other ZIP entries, `xl/styles.xml`, `<mergeCells>`, `<cols>`, `<f>` formulas,
       │   cell style indices `s="..."`, and unmapped columns byte-for-byte; sets `fullCalcOnLoad="1"`)
       ▼
[10. Re-verifies remote Drive `md5Checksum == preDownloadMd5` (Optimistic Concurrency Check)]
       │  └── If external edit changed MD5 during patch: abort upload, re-fetch latest `.xlsx`, & re-apply
       ▼
[11. Uploads patched `.xlsx` to same `masterDriveFileId` via `PATCH /upload/drive/v3/files/{fileId}`]
       │  └── Sets Drive `appProperties = { lastAppliedSyncJobId: jobId, lastAppliedSubmissionKey: key }`
       ▼
[12. Updates `/master_workbook_state` (`appliedSubmissionLedger`, new Revision/MD5), releases lease,
     and marks `/master_sync_jobs/{jobId}` & `daily_batches.masterSyncStatus = SUCCEEDED`]
```

### VI.2 How `ExcelWorkbookEngine.applyApprovedMappingsToExistingWorkbookInPlace()` Preserves Workbook Fidelity
1. **Byte-Exact ZIP Stream Preservation**:
   - Iterates through every `ZipEntry` in the downloaded Master `.xlsx`.
   - Every entry **except** the target worksheet XML (`xl/worksheets/sheetK.xml`), `xl/sharedStrings.xml` (if shared strings are updated, or using inline/existing shared string indices), and `xl/workbook.xml` (`fullCalcOnLoad="1"`) is copied **byte-for-byte** via `inputStream.copyTo(zipOutputStream)`. This guarantees 100% preservation of `xl/styles.xml`, themes, number formats, fonts, borders, fills, other worksheets, pivot tables, charts, drawings, and macros.
2. **DOM-Level Target Row Patching**:
   - Parses only the target worksheet XML using standard `javax.xml.parsers.DocumentBuilderFactory` (namespace-aware, preserving whitespace and attribute order).
   - Preserves `<sheetPr>`, `<dimension>`, `<sheetViews>`, `<sheetFormatPr>`, `<cols>`, `<mergeCells>`, `<conditionalFormatting>`, `<dataValidations>`, `<hyperlinks>`, `<printOptions>`, `<pageMargins>`, `<pageSetup>`, `<headerFooter>`, and `<extLst>` without modification.
   - In the matched `<row r="N">`, locates or creates `<c r="{Col}{N}">` for each approved mapped destination column:
     - **Style Preservation**: Retains the existing `s="..."` style index attribute on `<c>` (or inherits the column's default style index from adjacent data rows if the cell was previously empty).
     - **Formula Protection**: Checks `cellElement.getElementsByTagName("f")`. If a formula `<f>` exists on a target cell and `allowFormulaOverwrite == false`, throws `MasterWorkbookPatchException(ErrorCode.FORMULA_CELL_PROTECTED, "Cell ${cellRef} contains formula '${formulaText}'")`, aborting the patch before any Drive upload occurs.
     - **Numeric vs. String Type Safety**: Writes numeric fields (`Physical Qty`, `Qty Diff`) as `<c r="..." s="..."><v>${numericVal}</v></c>` (without `t="inlineStr"` or `t="s"`) so dependent Excel `SUM`/`AVERAGE` formulas calculate properly, and writes text fields (`Found?`, `Condition`, `Counted By`, `Remarks`) with proper XML escaping.

### VI.3 Google Drive Authorization Strategy for Admin & Backend Sync
- **Interactive Admin / Device-Coordinated Sync**: Uses the Admin's authorized Google Drive credential (`Identity.getAuthorizationClient` with `https://www.googleapis.com/auth/drive.file` scope) verified during `Control Panel → Google Drive & Settings → Connect Drive`.
- **Server-Side Background Sync (`functions/`)**: During `Connect Drive`, the Admin can authorize an offline OAuth authorization code (`serverAuthCode`) exchanged by the trusted backend for an encrypted refresh token stored in **Google Cloud Secret Manager** (never in client-readable Firestore documents). If the refresh token is revoked or expired (`HTTP 401/403 invalid_grant`), the sync job transitions to `FAILED_ACTION_REQUIRED` (`DRIVE_REAUTHORIZATION_REQUIRED`), preserving the `APPROVED` state in Firebase until the Admin reconnects Drive and taps **Retry Master Sync**.

---

# VII. Migration and Compatibility Plan (Development vs. Blank Production Separation)

### VII.1 Strict Environment Separation Rules (Decision G)
1. **Production Environment (`!BuildConfig.DEBUG`)**:
   - Starts with **zero preloaded clients, zero fiscal years, zero Asset Registers, zero counting records, and zero sample workbooks**.
   - `DevelopmentMigrationManager` is completely disabled in production builds.
   - All startup auto-seeders (`importDefaultWorkbookIfEmpty()` and `seedInitial34DayPlanIfEmpty()`) are permanently deleted from the codebase.
2. **Development Environment (`BuildConfig.DEBUG`)**:
   - Preserves existing Walton test records in the development database by running an idempotent, provenance-checked migration (`DevelopmentMigrationManager.runDevWaltonMigrationIfNeeded()`).

### VII.2 Safe Development Migration Algorithm (`DevelopmentMigrationManager.kt`)
1. **Step 1 — Pre-Migration Backup Snapshot**:
   - Before modifying any unscoped document (`clientId == ""`), writes a complete JSON snapshot of the original document into `/users/{orgId}/migration_backups/${migrationRunId}_${collection}_${docId}` (`immutable: true`).
2. **Step 2 — Provenance Verification**:
   - Inspects each unscoped document to verify whether it belongs to the Walton FY 2026–27 development dataset (`walton_hitech_dev` / `2026_27`).
   - Documents matching Walton provenance receive `clientId = "walton_hitech_dev"`, `fiscalYearId = "2026_27"`, `migratedAtIso`, and `migrationRunId`.
   - Any unscoped document with conflicting or unrecognized provenance is **never guessed**; it is tagged `migrationStatus = "NEEDS_ADMIN_REVIEW"` and surfaced in an Admin quarantine review list.
3. **Step 3 — Post-Migration Verification & Rollback**:
   - Verifies that pre-migration and post-migration document counts match (`verifiedCount + quarantinedCount == preMigrationCount`).
   - Provides `rollbackDevMigration(migrationRunId)` in `Control Panel` (Debug builds only) to restore exact pre-migration documents from `migration_backups` if needed.

---

# VIII. Phased Implementation Roadmap

### Phase 1: Shared-Code Refactor, Obsolete Module Removal & Schema/Security Foundation
1. Move `validateGeneratedExcelStructure` and `ExcelStructureValidationReport` from `AuditReportExportEngine.kt` into `ExcelWorkbookEngine.kt`.
2. Delete the 8 obsolete files (`PlanningAndForecastEngine.kt`, `AuditReportExportEngine.kt`, `DashboardScreen.kt`, `MasterDataScreen.kt`, `ExceptionsAndFoundScreen.kt`, `AdminReviewRequestsTab.kt`, `DriveScreen.kt`, `UsersScreen.kt`) and remove obsolete models (`CountingPlanStage`, `ReviewRequestRecord`, `SyncConflictEntity`) and startup auto-seeders (`importDefaultWorkbookIfEmpty`, `seedInitial34DayPlanIfEmpty`).
3. Update `AuditModels.kt` and `DynamicSchemaEngine.kt` with multi-client/fiscal-year fields, 3-way mapping (`sourceRegisterHeader`, `destinationMasterHeader`), `MasterWorkbookSyncMetadata`, `MasterWorkbookSyncJob`, `SystemOwnershipConfig`, `DeletionJobRecord`, `DeletedIdentityTombstone`, and `DeletionAuditEvent`.
4. Update `firebase-blueprint.json`, `firestore.rules`, and create the mandatory trusted backend in `/functions/src/index.ts`.

### Phase 2: Owner Bootstrap, Auth Hardening, Client Selection Gate & Dev Migration
1. Update `AuthScreen.kt` with the `Admin` / `End User (Auditor)` Account Type selector and clean login layout.
2. Update `MainActivity.kt` and `AuditRepository.kt` to enforce server role verification, tombstone checks, role-mismatch blocking (`AUTH-02`), and one-time `DESIGNATED_OWNER_UID` Owner bootstrap (removing the self-promotion dialog and role chips).
3. Create `ClientSelectionScreen.kt` supporting Admin client creation/selection, Auditor assigned client selection, calm inline unassigned access requests, and **Switch Client**.
4. Implement debug-only `DevelopmentMigrationManager.kt` while ensuring production starts 100% blank.

### Phase 3: Firebase Asset Register Import, 3-Way Mapping Designer & In-Place Master Workbook Sync
1. Upgrade `ExcelWorkbookEngine.kt` with:
   - `inspectWorkbookStructure()` and `importClientAssetRegisterFromStream()` (arbitrary register support, duplicate/missing key validation, `rawRowByHeader`, bounded 250-doc Firestore batching).
   - `extractDistinctCategoricalOptions()` for automatic dropdown candidate detection.
   - `applyApprovedMappingsToExistingWorkbookInPlace()` for in-place OpenXML `.xlsx` patching (preserving worksheets, headers, styles `s="..."`, `<f>` formulas, `<mergeCells>`, and column order).
2. Implement `MasterWorkbookSyncJob` orchestration in `AuditRepository.kt`, `GoogleDriveService.kt`, and `/functions/src/index.ts` (per-client lease lock, Drive `md5Checksum`/`headRevisionId` conflict detection, Drive `appProperties` crash reconciliation, and separate Approval vs. Sync statuses).
3. Update `ResourcesScreen.kt` for the 3-tab Resources workflow (`Asset Register`, `Master Input Sheet`, `Input Form Designer`).

### Phase 4: 4-Tab Admin & Auditor Workspaces, Permanent Deletion Pipeline & End-to-End Verification
1. Update `AdminNavTab.kt` and wire the 4-tab Admin Workspace (`Home`, `Resources`, `Review`, `Control Panel`) and 4-tab Auditor Workspace (`Home`, `Counting`, `Activity`, `Profile`).
2. Implement the 3-action `AdminReviewQueueTab.kt` (`Review`, inline `Send Remarks`, `Approve for Update to Master File` + `Retry Master Sync`).
3. Implement `AdminControlPanelScreen.kt` with Owner-governed User/Role Management, Client Management & Assignment, Access Requests, Google Drive Settings, **Delete Client**, and **Delete User**.
4. Complete Auditor `Counting` (scoped Firebase search, in-workflow Manual Entry with auto `Not Found in Asset Register` remarks, local+Drive photo evidence with local-retention badges) and `Activity` (Daily Sheets + Method A & Method B corrections).
5. Execute unit tests, Robolectric tests, and Firestore emulator security rule tests.

---

# IX. Testing and Acceptance Criteria

> [!NOTE]
> **Honest Test Execution Status**: Because this turn is strictly a planning-document revision (no code, rules, or tests were modified or executed in this phase), all new acceptance tests below are marked **`PLANNED — NOT YET EXECUTED`**. Previously verified baseline unit tests in `ExampleUnitTest.kt` (29-column schema order, calculation formulas, photo naming collision rules) are noted as **`EXISTING BASELINE`**.

### IX.1 Security, Role Governance & Data-Isolation Test Matrix

| Test ID | Scenario | Expected Outcome | Status |
| :--- | :--- | :--- | :--- |
| **SEC-BOOT-01** | Designated Owner (`uid == DESIGNATED_OWNER_UID`) bootstraps `/system_config/ownership` on unbootstrapped database | Succeeds once; creates `/system_config/ownership` and `/role_assignments/{ownerUid}` with `role == 'Owner'`. | `PLANNED` |
| **SEC-BOOT-02** | Any other UID attempts to create `/system_config/ownership`, or repeat bootstrap is attempted after lock | Rejected with `PERMISSION_DENIED`. | `PLANNED` |
| **SEC-ROLE-01** | Ordinary user attempts to write `role: "Admin"` or `"Owner"` to `/role_assignments/{uid}` or `/user_profile/main` | Rejected with `PERMISSION_DENIED`; `user_profile.role` ignored by security rules. | `PLANNED` |
| **SEC-ROLE-02** | `Admin` attempts to demote, deactivate, or delete the `Owner` account | Rejected with `PERMISSION_DENIED` in both `firestore.rules` and trusted backend. | `PLANNED` |
| **SEC-ISO-01** | Newly registered Auditor (`0` client assignments) attempts to read `/far_assets`, `/counting_records`, or `/daily_batches` | Rejected with `PERMISSION_DENIED`; UI displays calm inline Request Access card. | `PLANNED` |
| **SEC-ISO-02** | Auditor assigned only to `(client_A, FY26_27)` queries or writes records for `(client_B, FY26_27)` or `(client_A, FY25_26)` | Rejected with `PERMISSION_DENIED`; zero cross-client or cross-year leakage. | `PLANNED` |
| **SEC-ISO-03** | Admin unassigns Auditor from `client_A`; Auditor immediately attempts to read `far_assets` or submit a count for `client_A` | Rejected immediately server-side with `PERMISSION_DENIED`. | `PLANNED` |
| **DEL-ISO-01** | Admin deletes `client_A` while `client_B` exists in the same workspace | 100% of `client_A` Firestore records and local cache rows are removed; 100% of `client_B` records, users, and Drive files remain untouched. | `PLANNED` |
| **DEL-ISO-02** | Admin deletes `auditor_1` who has both unsubmitted `Draft` sheets and `Approved` sheets in `client_A` | `auditor_1`'s Firebase Auth account, profile, role, assignments, and `Draft` sheets are deleted; `Approved` sheets in `client_A` remain intact and pseudonymized (`"Deleted User [#hash]"`). | `PLANNED` |
| **DEL-TOMB-03** | Deleted user (`exists(/deleted_identities/{uid})`) attempts any Firestore read/write using a cached token before expiry | Immediately rejected by `isNotDeleted(orgId)` in `firestore.rules`. | `PLANNED` |

### IX.2 Workbook-Format Preservation, Idempotency, Concurrency & Crash-Recovery Test Matrix

| Test ID | Scenario | Expected Outcome | Status |
| :--- | :--- | :--- | :--- |
| **SYNC-FMT-01** | Patch an existing multi-sheet `.xlsx` containing custom cell styles (`s="12"`), `<mergeCells>`, `<cols>`, and extra summary tabs | Only mapped destination cells in the matched asset row are updated; all other worksheets, column widths, merged cell ranges, and style attributes (`s="..."`) are 100% preserved byte-for-byte. | `PLANNED` |
| **SYNC-FML-02** | Target destination cell in matched row contains an Excel formula `<f>N14-R14</f>` and `allowFormulaOverwrite == false` | Patcher halts before writing, leaves the workbook untouched, and transitions the job to `FAILED_ACTION_REQUIRED` (`FORMULA_CELL_PROTECTED`). | `PLANNED` |
| **SYNC-ERR-03** | Approved asset's `matchingKeyValue` matches `0` rows or `>1` duplicate rows in the Master Input worksheet, or a mapped header was renamed | Patcher halts immediately without modifying the workbook and reports `ASSET_ROW_NOT_FOUND`, `DUPLICATE_ASSET_ROW_AMBIGUITY`, or `DESTINATION_HEADER_MISSING`. | `PLANNED` |
| **SYNC-IDEM-04** | Same approved submission (`${batchId}_v${versionNumber}`) is triggered or retried multiple times | Second execution detects `submissionRevisionKey` in `appliedSubmissionLedger`, performs zero duplicate writes, and never appends duplicate rows. | `PLANNED` |
| **SYNC-CONC-05** | Two Admins approve two different batches (`FA-1001` and `FA-1002`) for the same client simultaneously | Per-client lease lock serializes execution (`job_1` → `R2`, then `job_2` downloads `R2` → `R3`); both `FA-1001` and `FA-1002` updates are preserved in `R3`. | `PLANNED` |
| **SYNC-CONC-06** | External user edits the Master `.xlsx` in Google Drive while a sync job is computing its patch (`remoteMd5 != preDownloadMd5`) | Pre-upload MD5 check detects the revision conflict, discards the stale buffer, re-downloads the latest Drive `.xlsx`, and re-applies the patch cleanly. | `PLANNED` |
| **SYNC-REC-07** | Process crashes immediately after Drive `PATCH` succeeds (with `appProperties.lastAppliedSyncJobId = jobId` saved on Drive) but before Firestore updates | Recovery sweep reads Drive `appProperties`, confirms `lastAppliedSyncJobId == jobId`, skips re-patching, and reconciles Firestore `master_workbook_state` and `daily_batches` to `SUCCEEDED`. | `PLANNED` |
| **SYNC-SEP-08** | Google Drive API returns HTTP 503 / network timeout after Admin clicks Approve | `daily_batches.status` remains `Approved` while `masterSyncStatus` shows `FailedRetryable` with a retry button; approval is never lost or falsely shown as `Published`. | `PLANNED` |

---

# X. Deployment, Configuration, Backup and Recovery

### X.1 Firebase Project, Billing Prerequisites & Secrets Configuration
1. **Firebase Project & Named Firestore Database**:
   - Project ID: `spherical-booth-366013` (configured in `app/google-services.json` and `firebase-applet-config.xml`).
   - Named Firestore Database ID: `ai-studio-20090969-10c1-4df6-983b-563f1387fa3b` (bound in `firebase.json` as the single database entry).
2. **Mandatory Trusted Backend (`functions/`) Prerequisites**:
   - **Blaze Plan Requirement**: Deploying Firebase Cloud Functions (`/functions`) requires the Firebase/GCP project to be on the **Blaze (pay-as-you-go)** billing plan.
   - **Required Backend Environment Variables / Secrets**:
     - `DESIGNATED_OWNER_UID`: The immutable Firebase Auth UID of `mislamabed@gmail.com`.
     - `FIRESTORE_DATABASE_ID`: `ai-studio-20090969-10c1-4df6-983b-563f1387fa3b`.
     - `GOOGLE_DRIVE_CLIENT_SECRET` (stored in Google Cloud Secret Manager if server-side offline Drive OAuth token refresh is enabled).
3. **Google Drive OAuth Configuration**:
   - Requires the Android OAuth 2.0 Client ID (matching the app's package name `com.aistudio.fixedassetcounter.walton` and signing SHA-1/SHA-256 certificate fingerprints) and Web Client ID (`R.string.default_web_client_id`) with the Google Drive API enabled and `https://www.googleapis.com/auth/drive.file` scope permitted.
4. **Deployment Sequence**:
   - Step 1: Validate `firestore.rules` against local emulators (`node --test firestore.test.js` and `gradle :app:testDebugUnitTest --tests '*Rule*'`).
   - Step 2: Deploy Firestore Security Rules via `deploy_firebase`.
   - Step 3: Deploy Mandatory Trusted Backend Cloud Functions via `firebase deploy --only functions`.
   - Step 4: Compile and verify Android APK via `compile_applet`.

### X.2 Backup, Retention & Disaster Recovery Procedures
1. **Firestore Point-in-Time Recovery (PITR) & Pre-Migration Backups**:
   - Enable Firestore PITR / scheduled exports on the production database prior to onboarding production clients.
   - In development, `DevelopmentMigrationManager` writes immutable pre-migration document snapshots to `/users/{orgId}/migration_backups/` before modifying any legacy Walton document.
2. **Google Drive Master Workbook Revision History**:
   - Because AssetFlow updates the existing Google Drive `.xlsx` file in place via `PATCH /upload/drive/v3/files/{masterDriveFileId}`, Google Drive natively retains previous file revisions (`revisions.list` / `revisions.get`). Furthermore, `/master_workbook_state` records every `lastKnownDriveRevisionId` and `lastKnownDriveMd5Checksum` alongside the `appliedSubmissionLedger`, allowing an Admin to inspect or restore a prior Drive revision if an external user corrupts the spreadsheet.
3. **Interrupted Job Recovery**:
   - Both `/master_sync_jobs/{jobId}` and `/deletion_jobs/{jobId}` persist step-level checkpoints in Firestore. If the app or backend restarts mid-operation, the recovery coordinator resumes from the last uncompleted checkpoint without duplicating completed steps.

---

# XI. Outstanding Decisions and Implementation Readiness Checklist

### XI.1 Prerequisites & Decisions to Confirm Before or During Phase 1
1. **`DESIGNATED_OWNER_UID` String Value**:
   - Confirm the exact Firebase Auth `UID` string for `mislamabed@gmail.com` to lock into `firestore.rules` and `/functions`. *(During implementation, we will also display a one-tap "Copy My Firebase UID" chip on the Profile/Setup bar when signed in as `mislamabed@gmail.com` so you can verify it effortlessly).*
2. **Default Owner Delegation Flags for Secondary Admins**:
   - By default in this plan, only the `Owner` has `canManageAdmins = true`, `canDeleteClients = true`, and `canDeleteUsers = true` initially, and the `Owner` can toggle those flags per `Admin` in `Control Panel → User & Role Management`.
3. **Composite Key Disambiguation Default on Duplicate Asset IDs**:
   - When a client's Asset Register contains duplicate `Asset ID` rows, the importer prompts the Admin to either use `Asset ID + Sub-Number` as a composite matching key or fix the duplicate rows in the source Excel file before import.

### XI.2 Implementation Readiness Checklist
- [x] **Original Complete Application Plan Preserved**: All 17 sections + Definition of Done + Tables 1–20 of the Functional & UX Specification (v1.0) and all 18 sections of the Complete Architecture & Feature Plan are preserved in full in Section I.
- [x] **Finalized Data Storage Architecture (Decisions A–D)**: Firebase Firestore for Asset Register (`far_assets`) and Auditor inputs (`counting_records`, `daily_batches`); Google Drive primary binary store for Master Input `.xlsx` with metadata-only in Firebase; device-local + Google Drive for Image Evidence with **zero Firebase Storage**.
- [x] **In-Place Master Workbook Sync (Decision C & Section VI)**: 12-step workflow patching only mapped destination cells while preserving worksheets, headers, styles, formulas, and merged cells, with lease locking, MD5 conflict detection, and Drive `appProperties` crash recovery.
- [x] **Owner/Admin/Auditor Security & Mandatory Trusted Backend (Decisions E, F, H)**: One-time `DESIGNATED_OWNER_UID` bootstrap, zero self-promotion, calm unassigned Auditor access requests, target-scoped permanent Client/User deletion, and mandatory Firebase Admin SDK backend.
- [x] **Strict Development vs. Production Separation (Decision G)**: Provenance-checked Walton dev migration with backup/rollback vs. 100% blank production startup.

---

# XII. Validation Summary

### 1. Confirmation of Full Preservation of the Original Application Plan
- **Confirmed**: The entire **Original AssetFlow Application Plan**—comprising **Part I-A** (*AssetFlow Detailed Functional Planning & UX Specification v1.0*, Sections 1–17, Definition of Done, and all 20 specification tables) and **Part I-B** (*Original Complete 18-Section Application & Architecture Plan*, including product vision, 4-tab Admin and 4-tab Auditor navigation, all module and screen specifications, 29-column dynamic schema, manual entry rules, automatic dropdown detection, Full Name vs. Nickname attribution rules, Owner-controlled Admin RBAC, permanent Client and User deletion workflows, and roadmap)—has been preserved in full as Section I of this unified document.

### 2. Exact List of Implementation Sections Added or Updated
- **Section I (Part I-A & Part I-B)**: Restored the complete original functional specification (Sections 1–17 + Tables 1–20) and all 18 original architectural plan sections, updating Section 8/9/12/18 references to remove Firebase Storage binaries/rules (Decision D) and to designate the Firebase Admin SDK backend as **Mandatory** rather than optional (Decision H).
- **Section II (Verified Existing Implementation and Gap Analysis)**: Added line-by-line verification of the 34 existing Kotlin source files, identifying the root `/users/{workspaceUserId}` path mismatch, the scratch-workbook overwrite bug in `syncMasterWorkbookToDrive`, the fixed-column parser in `ExcelWorkbookEngine`, and the shared validation helper in `AuditReportExportEngine`.
- **Section III (Updated Technical Architecture and Data-Storage Responsibilities)**: Added the authoritative Data-Storage Responsibility Matrix and technical designs for Decisions A, B, C, D, E, F, G, H, and I (including 250-doc bounded batching for large Asset Registers and chunked snapshots for large daily sheets).
- **Section IV (Implementation Design for Each Original Module)**: Added full module-by-module implementation designs with explicit traceability (existing status, required changes, dependencies, security, acceptance tests, and phase).
- **Section V (Database Schema, Security Rules and Mandatory Trusted Backend)**: Reconciled the `/users/{orgId}/...` Firestore schema, hardened `firestore.rules`, and specified the 5 mandatory Firebase Admin SDK Cloud Functions endpoints (`bootstrapInitialOwner`, `manageUserRoleAndPermissions`, `executeClientDeletionJob`, `executeUserDeletionJob`, `executeMasterWorkbookSyncJob`).
- **Section VI (Google Drive Master Workbook Synchronization)**: Specified the 12-step Approval-to-Master-Workbook in-place OpenXML `.xlsx` patching engine (`applyApprovedMappingsToExistingWorkbookInPlace`), formula cell protection, per-client lease locking, Drive MD5 conflict detection, and Drive `appProperties` crash reconciliation.
- **Section VII (Migration and Compatibility Plan)**: Detailed the debug-only `DevelopmentMigrationManager` (with backup, provenance check, quarantine, and rollback) vs. 100% blank production initialization.
- **Sections VIII, IX, X, XI**: Added the dependency-ordered 4-phase implementation roadmap, realistic security/isolation/workbook-preservation/concurrency/recovery test matrices, deployment/billing/OAuth prerequisites, and readiness checklists.

### 3. Module-by-Module Traceability Table (Original Plan Features → Implementation Tasks)

| Original Plan Module / Feature | Original Spec Reference | Verified Current Code Status | Implementation Task & Target Files | Target Phase | Acceptance Tests |
| :--- | :--- | :--- | :--- | :---: | :--- |
| **Welcome, Login, Account Type Cards & Role Mismatch Check** | Part I-A §4, Table 5; Part I-B §4.1, §6 | `PARTIAL` (`AuthScreen.kt` lacks role cards & mismatch gate) | Add `Admin`/`Auditor` role cards to `AuthScreen.kt`; enforce server role & tombstone check in `MainActivity.kt` & `AuditRepository.kt`. | Phase 2 | `AUTH-01`, `AUTH-02`, `AUTH-03` |
| **Owner Bootstrap & Admin Role Governance** | Part I-A §2.1, §13; Part I-B §1, §7 | `CRITICAL GAP` (Insecure self-bootstrap in `MainActivity.kt` & `firestore.rules`) | Lock one-time `DESIGNATED_OWNER_UID` bootstrap in `firestore.rules` & `/functions`; remove self-promotion UI; enforce Owner protection. | Phase 1 & 2 | `SEC-BOOT-01`, `SEC-BOOT-02`, `SEC-ROLE-01`, `SEC-ROLE-02` |
| **Client & Fiscal Year Selection + Calm Access Requests** | Part I-A §5, Tables 6–7; Part I-B §4.1, §6 | `MISSING` | Create `ClientSelectionScreen.kt` with Admin Create Client wizard, Auditor assigned selector, calm inline Request Access card, and `Switch Client`. | Phase 2 | `CLIENT-01`, `CLIENT-02`, `CLIENT-03`, `SESSION-01` |
| **Admin Tab 1: Home Dashboard & Activity Log** | Part I-A §6.1, Table 8; Part I-B §4.2, §10 | `PARTIAL` (`AdminDashboardScreen.kt` unscoped) | Scope KPI cards and Recent Activity Log by `(clientId, fiscalYearId)`; add active scope badge and `Switch Client`. | Phase 4 | `LOG-01`, `SESSION-01` |
| **Admin Tab 2: Resources — Asset Register Import to Firebase** | Part I-A §6.2, Table 9; Part I-B §5, §10, §12 | `PARTIAL` (`ExcelWorkbookEngine.kt` hardcoded to 29-col template) | Implement `inspectWorkbookStructure` & `importClientAssetRegisterFromStream` into Firestore `far_assets` (250-doc bounded batches, duplicate check, `rawRowByHeader`). | Phase 3 | `RES-01`, Required Tests #6, #27 |
| **Admin Tab 2: Resources — Master Input Sheet Setup** | Part I-A §6.2, Table 9; Part I-B §10, §12 | `PARTIAL` | Store metadata only in `/master_workbook_state/{clientId}_{fyId}` linked to Google Drive `masterDriveFileId`; add **Verify & Reconcile Master Sheet**. | Phase 3 | `DRIVE-01`, `DRIVE-02` |
| **Admin Tab 2: Resources — 3-Way Field Mapping & Dropdown Auto-Detection** | Part I-A §6.3, Table 10; Part I-B §5, §10 | `PARTIAL` (`DynamicSchemaEngine.kt` lacks 3-way column split) | Add `sourceRegisterHeader` & `destinationMasterHeader` to `SchemaFieldConfig`; implement `extractDistinctCategoricalOptions` and option manager in `ResourcesScreen.kt`. | Phase 3 | `MAP-01`, Required Tests #11, #12, #13 |
| **Admin Tab 3: Review Queue, Inline Remarks & In-Place Master Workbook Sync** | Part I-A §6.4, Table 13; Part I-B §10, §12; Section VI | `CRITICAL GAP` (`syncMasterWorkbookToDrive` overwrites `.xlsx` from scratch) | Update `AdminReviewQueueTab.kt` with 3 primary actions; implement `applyApprovedMappingsToExistingWorkbookInPlace()` in `ExcelWorkbookEngine.kt` and `MasterWorkbookSyncJob` saga. | Phase 3 & 4 | `REV-01`, `REV-02`, `SYNC-FMT-01` through `SYNC-SEP-08` |
| **Admin Tab 4: Control Panel (Users, Assignments, Requests, Drive, Logout)** | Part I-A §7, Table 11; Part I-B §7, §10 | `PARTIAL` (Split across `DriveScreen`, `UsersScreen`, `AdminControlPanelScreen`) | Consolidate all 5 subsections into `AdminControlPanelScreen.kt`; delete standalone `DriveScreen.kt` and `UsersScreen.kt`. | Phase 4 | `CLIENT-02`, `CLIENT-03`, `LOG-01`, `SESSION-02` |
| **Admin Control Panel: Permanent Client & User Deletion** | Part I-B §8, §9; Decision F | `MISSING` | Add **Delete Client** & **Delete User** UI with impact preview + typed confirmation; implement `executeClientDeletionJob` & `executeUserDeletionJob` (`admin.auth().deleteUser`) in `/functions` & `AuditRepository.kt`. | Phase 1 & 4 | `DEL-ISO-01`, `DEL-ISO-02`, `DEL-TOMB-03` |
| **Auditor Tab 1: Home Dashboard & Notifications** | Part I-A §8.1; Part I-B §4.3, §11 | `PARTIAL` (`UserDashboardScreen.kt` & `NotificationsScreen.kt` separate) | Scope Auditor dashboard metrics to `(clientId, fiscalYearId)` and embed interactive Notifications feed in `UserDashboardScreen.kt`. | Phase 4 | `SESSION-01`, Required Test #19 |
| **Auditor Tab 2: Counting, Scoped Search, In-Workflow Manual Entry & Photo Evidence** | Part I-A §8.2, §8.3, §9.2, Table 12; Part I-B §11, §12; Decision D | `PARTIAL` (Unscoped; needs local-retention photo badges) | Scope `AssetListScreen.kt` & `CountingFormScreen.kt` to Firebase `far_assets`; enforce inline Manual Entry with `Not Found in Asset Register` remark; keep local+Drive photo pipeline with zero Firebase Storage. | Phase 3 & 4 | `COUNT-01`, `COUNT-02`, `EVID-01`, `SUB-01`, Required Tests #7–#10, #15, #28–#29 |
| **Auditor Tab 3: Activity (My Daily Sheets & Method A / Method B Corrections)** | Part I-A §8.4, §9.1; Part I-B §11 | `PARTIAL` (`DailyInputSheetsScreen` & `SubmissionHistoryScreen` separate) | Combine under `Activity` tab; support versioned snapshots (with chunking >150 rows), **Request Admin Review**, Method A direct edit, and Method B Excel download/re-upload. | Phase 4 | Required Tests #18, #20, #21, #22, #23 |
| **Auditor Tab 4: Profile (Full Name, Nickname, Read-Only Scope & Logout)** | Part I-A §8.5; Part I-B §11 | `PARTIAL` (`ProfileScreen.kt`) | Separate editable Full Name (`Counted By` / `Reviewed By`) and Nickname (photo filenames), read-only Role/Clients, password reset, and Logout. | Phase 2 & 4 | `SESSION-02`, Required Tests #14, #17 |
| **Development vs. Production Separation & Obsolete Module Removal** | Part I-B §14, §15; Decision G | `CRITICAL GAP` (Auto-seeders run on startup; 8 legacy files present) | Delete 8 obsolete files & startup seeders; implement debug-only `DevelopmentMigrationManager.kt` with backup, provenance verification, and rollback. | Phase 1 & 2 | `ENV-CLEAN-01` |

### 4. Unresolved Decisions or Prerequisites Required Before Coding Starts
1. **Confirm `DESIGNATED_OWNER_UID` (Optional Before Phase 1 — Required Before Production Rules Lock)**: Provide the Firebase Authentication `UID` for `mislamabed@gmail.com` when ready, or use the in-app UID copy helper during Phase 2 setup.
2. **Firebase Blaze Plan for Production Cloud Functions Deployment**: Required when deploying `/functions` (`executeUserDeletionJob`, `executeClientDeletionJob`, `executeMasterWorkbookSyncJob`, `bootstrapInitialOwner`, `manageUserRoleAndPermissions`) to the live Firebase project.
3. **Explicit Approval to Begin Implementation**: Per your operating instructions, **zero application code, Firebase configurations, Firestore rules, Google Drive files, or deployment settings have been modified in this turn**. Please review and approve this complete plan when you are ready for implementation to begin.
