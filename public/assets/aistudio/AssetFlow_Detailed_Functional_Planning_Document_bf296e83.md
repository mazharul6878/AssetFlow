ASSETFLOW

Detailed Functional Planning & UX Specification

Fixed Asset Verification System

Welcome → Login → Client Selection → Admin/Auditor Workspaces → Asset Counting → Review & Approval → Google Drive

Version 1.0 | 29 October 2026

A standalone reference for product owners, UI/UX designers, developers, testers and administrators.


## Document purpose and how to use it

This document describes how each AssetFlow screen, feature, button, icon and workflow is expected to behave, when it is available, whose identity is attached to the action, where data is scoped, and what happens on success or failure. It records the previously agreed requirements and identifies implementation decisions that must not be silently guessed.


## 1. Product principles

Role determines which modules a user may access; client assignment determines which client data that user may access. These are separate permissions.

Admin navigation: Home → Resources → Review → Control Panel. Control Panel includes User & Role Management, Client Assignment, Access Requests, Google Drive & Settings and Admin Logout.

Auditor navigation: Home → Counting → Activity → Profile. Auditor logout exists in Profile.

Client Selection and Fiscal Year Selection happen after authentication and before workspace entry. Switch Client returns there without logging out.

All authorization must be enforced by backend/Firebase security rules. Hiding a button is not security.

Auditor work is stored as draft/submitted data before Admin approval. Approved and Drive-published are separate statuses.

Admin approval updates the existing configured Master Input workbook; it must not create a new master workbook on every approval.

New features must not remove existing functionality. Role assignment and client assignment both remain available.

Never show success until the server or external provider confirms it; provide visible failure and retry states.


## 2. User identity, roles and ownership


### 2.1 User ID rules

Firebase Auth generates a unique UID. Use it as actorUid, auditorUid, createdByUid, updatedByUid and reviewerUid as appropriate.

Email and display name are profile attributes, not primary keys. Do not identify ownership by display name alone.

Never store plaintext passwords or expose Google access/refresh tokens to the app UI.

Role is read from a trusted server-controlled profile/custom claim. A client-supplied role value cannot grant privileges.

Changing a user's role or disabling an account must take effect on future server requests; historical records keep the original actor UID.


## 3. Complete application journey


## 4. Welcome, Login and Registration


### 4.1 Registration fields and rules

Fields: full name, email, password, confirm password, requested role if enabled, and terms/privacy acceptance.

Validate required fields, email format, password policy and matching passwords before account creation.

Recommended safe default: new account is pending or Auditor until an authorized Admin provisions/approves it; self-selected Admin must never grant Admin access.

Google first-time sign-in follows the same provisioning policy; Google identity alone does not grant a role or client access.

After registration, clearly show whether email verification or Admin approval is still required.

Confirm before production: public self-registration policy, email verification requirement and whether new Auditors need Admin approval.


## 5. Client Selection, Fiscal Year and Access Requests


### 5.1 Admin Create Client wizard

Enter client name/code and validate duplicates.

Create client record and configure fiscal year dates/status.

Upload or later attach Asset Register and Master Input Sheet.

Connect the client's Drive and save actual folder/file IDs.

Assign Auditors through Control Panel → Client Assignment.

Show setup checklist; never label Drive connected until OAuth and a test operation succeed.


### 5.2 Access request lifecycle

Request stores requestId, requesterUid, clientId, optional fiscalYearId, reason, requestedAt and status.

Admin reviews in Control Panel → Access Requests. Approve must create/activate assignment; reject must not.

Prevent duplicate pending requests for the same UID/client/scope. Notify requester after decision.

Notifications are informational; assignment records are the authorization source of truth.


## 6. Admin workspace


### 6.1 Home


### 6.2 Resources


### 6.3 Field Mapping Designer

Map three separate concepts: source register header, form label, and destination master column; their names may differ.

Allow add/edit/remove/reorder field, required flag, help text, dropdown options, automatic rule, include/exclude and preview.

Do not publish if required mapping is missing, destination columns conflict or automatic rule is invalid.

Configuration is scoped by client/fiscal year and versioned. Each count record stores configVersion.

Changes must not silently rewrite old approved records.


### 6.4 Review

List sheets grouped by Auditor/date, filtered by client/year/status.

Each row shows Auditor, work date, asset count, submission time, review status and Drive publishing status.

Admin reviews the complete daily sheet; individual-asset approval is not a mandatory separate workflow.

Approve & Publish is enabled only after validations pass and the sheet is eligible.

Return for Correction requires a reason and creates a new editable version for the owning Auditor; preserve the previous submission.

Final Reject, if supported, is distinct from Return for Correction.

Approval and Drive publishing are separate: Approved + Publish Failed is a valid state. Retry must not duplicate rows/files.


## 7. Control Panel


### 7.1 User/role management behavior

Creating/inviting a user links the profile to Firebase UID; never store plaintext password.

Changing role requires Admin permission and confirmation; save old/new role in audit log.

Deactivation blocks future access but preserves historical records and actor IDs.

Show success only after server confirms profile update.


### 7.2 Client assignment behavior

Save creates/activates assignment records with assignedByUid and timestamp.

Unassign revokes future access server-side; do not delete historical work.

Warn if drafts or pending sheets exist before unassignment; policy must decide whether these become read-only or remain accessible in limited form.

Show clients assigned per Auditor and Auditors assigned per client.


### 7.3 Google Drive settings

Connect Drive uses real OAuth with minimum required scopes; never ask users to paste access tokens.

Persist actual root folder ID, Image Evidence folder ID, Daily Input Sheets folder ID and Master Input workbook ID.

Provide Test Connection, Reconnect, Disconnect and Sync Health.

Disconnect warns about consequences and must not delete remote files by default.

Protect refresh tokens server-side; never expose them in client-readable documents or logs.


## 8. Auditor workspace


### 8.1 Home

Dashboard shows assigned client/year, workload or assigned assets if configured, counted today, drafts/submissions and status summaries.

Today's Assignment may show department/location/tasks only if those fields are configured.

Notifications show due dates, returned sheets, Admin messages and publishing outcomes; selecting one opens the relevant record.

Switch Client returns to Client Selection and clears old client data from UI cache before loading new scope.


### 8.2 Counting


### 8.3 Input form

Fixed fields are prefilled and read-only; dropdown values/options and automatic values come from active config version; manual fields validate type/range/length.

Show field-level errors and preserve other valid input when validation fails.

Each draft belongs to auditorUid, clientId, fiscalYearId, sheetId, asset/verification ID and configVersion.

Auditor can edit own Draft and eligible Returned for Correction records only; cannot edit another Auditor's records by changing a request.

Images may be replaced/removed while record is editable; log evidence changes and follow retention policy.

Submitted version is frozen. A returned sheet creates a correction version while retaining the submitted version.


### 8.4 Activity

Show current Auditor's daily sheets by date, asset count, last updated time and status.

Open a sheet to view permitted assets/evidence; download only through secure export.

Do not expose another Auditor's private drafts. Admin reviews submitted sheets in Review.


### 8.5 Profile

Show name/email and editable permitted profile fields.

Role, UID and client assignments are read-only for Auditor.

Password change/reset uses Firebase Auth, never a profile text field.

Auditor Logout lives here; end session and clear protected local context.


## 9. Daily sheets, evidence and Drive workflow


### 9.1 Daily sheet lifecycle

Create/reuse a draft according to the configured one-sheet-per-Auditor/client/fiscal-year/date policy.

Every verification references sheetId and is attributed to the signed-in Auditor UID.

Autosave may be used, but Saved/Saving/Offline/Failed status must be honest.

Submission validates required fields, duplicates and evidence requirements, then freezes a submitted version.

Admin review/approval references the exact submitted version, not mutable client-side state.


### 9.2 Evidence

Store evidenceId, client/year/sheet/verification IDs, uploadedByUid, capturedAt, MIME, file size, location and upload status.

Recommended filename: nickname_assetID_1.jpg. Sanitize names and ensure uniqueness with a suffix or evidence ID.

Image Evidence folder contains images only; workbooks are stored in their own folders.

Failed image upload stays visibly Failed/Pending and can be retried; never label it uploaded.

Replacement/deletion follows edit permissions and retention policy; preserve audit reference to old evidence where required.


### 9.3 Drive folder structure

AssetFlow / [Client Name] /
├── Image Evidence/  → nickname_assetID_1.jpg, nickname_assetID_2.jpg, ...
├── Master Input File/  → Client_Master_Input.xlsx (persistent workbook)
└── Daily Input Sheets/ → [Auditor Name]/[YYYY-MM-DD]/Daily_Input.xlsx

Recommended authority split: Firebase holds operational drafts, permissions, statuses and metadata; Drive holds approved output files and persistent master workbook. Make the current authoritative copy clear to users.


## 10. Status model and failure handling


## 11. Button and icon dictionary


### 11.1 Interaction states for every control

Define default, focus/hover, pressed, disabled, loading, success and failure states as relevant.

Destructive actions require confirmation showing the target name.

Icons without text need accessible labels/tooltips; status must not rely on color alone.

Disable repeated taps while requests run and use backend idempotency for mutations.

After a successful mutation, refresh from authoritative server state.


## 12. Recommended Firebase data model

users/{uid}
clients/{clientId}
clients/{clientId}/fiscalYears/{fiscalYearId}
assignments/{assignmentId}
accessRequests/{requestId}
clientScopes/{clientId}_{fiscalYearId}/registerVersions/{versionId}
clientScopes/{clientId}_{fiscalYearId}/formConfigs/{configVersion}
dailySheets/{sheetId}
dailySheets/{sheetId}/verifications/{verificationId}
approvals/{approvalId}
driveConfigs/{scopeId}
publishJobs/{jobId}
auditEvents/{eventId}


## 13. Security rules that must hold

All private reads/writes require authenticated UID.

Resolve role from trusted server data, never from a client-supplied role field.

Only Admin may create clients, manage users/roles, assign clients, decide access requests, upload/configure resources, approve/publish or manage Drive.

Auditor may access assigned client/year only and may edit only own Draft/Returned sheets by default.

Every data/storage access must validate clientId and fiscalYearId scope, not merely screen navigation.

Unassignment, role change and deactivation must revoke future requests server-side.

Only trusted backend workflow may set Approved/Published; do not trust client-sent status values.

Use least-privilege OAuth scopes; protect tokens and secrets; validate workbook/image uploads.

Log denied attempts without revealing protected client data; use secure/expiring download access.

Define retention, backup, export and deletion policy before production.


## 14. Audit trail

Use server-generated timestamps and immutable event IDs. Keep logs useful but avoid storing passwords, tokens, full image content or unnecessary sensitive asset details.


## 15. Acceptance tests


## 16. Recommended implementation order

Confirm registration policy and daily-sheet uniqueness rule.

Implement Firebase Authentication, user profile provisioning and trusted role enforcement.

Implement Client/Fiscal Year selection, assignment validation and Access Request lifecycle.

Implement client setup and full Control Panel: User & Role Management, Client Assignment, Access Requests, Drive & Settings, Admin Logout.

Implement workbook upload/parse/versioning for Asset Register and Master Input Sheet.

Implement Field Mapping Designer, preview, validation and configuration versioning.

Implement Auditor Home, Counting, draft persistence, evidence, Activity and Profile.

Implement submission state machine and Admin Review/approval.

Implement real Drive OAuth, folder mapping, daily exports, evidence upload and idempotent master workbook update.

Implement notifications, audit trail, retry/reconciliation, security rules and status UI.

Test with at least two clients and two Auditors; include role mismatch, offline mode, token expiry and partial publish failure.

Complete backup/restore and retention policy before production launch.


## 17. Decisions to confirm before production


## Definition of done

Every screen, button, icon and field has a defined action, permission and state.

Every protected request checks UID, role, client/year scope, ownership and record status.

Every workflow has loading, success, failure and retry behavior.

All old features remain available when new ones are added.

Approval, Drive upload and master workbook update are separately tracked and auditable.

Two-client/two-Auditor tests prove that client data does not leak across assignments.


### Table 1


| Item | Definition |

| --- | --- |

| Product | AssetFlow — Fixed Asset Verification System |

| Primary roles | Admin; End User (Auditor) |

| Identity | Firebase Authentication UID is the immutable user identifier. |

| Core scope | Every client record is scoped by clientId; year-specific data also uses fiscalYearId. |

| Important boundary | This is a functional specification, not proof that Firebase, OAuth or Drive integration is already implemented. |


### Table 2


| Capability | Admin | End User (Auditor) |

| --- | --- | --- |

| Create/configure clients | Yes | No |

| Upload Asset Register/Master Input Sheet | Yes | No |

| Configure form mapping | Yes | No |

| Create users / assign Admin or Auditor role | Yes | No |

| Assign/unassign clients to Auditors | Yes | No |

| Request access to unassigned client | Not applicable | Yes |

| Count assets and add evidence | Review/inspect as authorized | Yes, assigned scope only |

| Approve/publish daily sheets | Yes | No |

| Configure Drive | Yes | No |

| Logout location | Control Panel | Profile |


### Table 3


| Record | Identity/scope fields |

| --- | --- |

| User | uid, email, displayName, role, active, createdAt, updatedAt |

| Client | clientId, clientName, status, createdByUid, createdAt |

| Fiscal year | fiscalYearId, clientId, label, startDate, endDate, status |

| Assignment | assignmentId, clientId, auditorUid, assignedByUid, assignedAt, active |

| Daily sheet | sheetId, clientId, fiscalYearId, auditorUid, workDate, status, version |

| Verification | verificationId, sheetId, assetId/manualAssetId, enteredByUid, updatedByUid |

| Approval | approvalId, sheetId, reviewedByUid, decision, approvedVersion, approvedAt |

| Drive config | clientId/scopeId, connectedByUid, rootFolderId, masterWorkbookId |

| Audit event | eventId, actorUid, action, targetType, targetId, clientId, fiscalYearId, timestamp, result |


### Table 4


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


### Table 5


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


### Table 6


| Control | Admin | Auditor |

| --- | --- | --- |

| Client dropdown | Active clients Admin can manage | Only active assigned clients |

| Fiscal Year dropdown | Configured years for selected client | Only permitted years for assigned client |

| Continue | Validate scope and open Admin Home | Validate assignment server-side and open Auditor Home |

| Create Client | Visible; opens client setup wizard | Hidden and blocked server-side |

| Switch Client | Return to selector without logout | Return to selector without logout |

| No clients | Show setup guidance | Show no-assignment message; do not load client data |

| Access Denied | Normally not expected for authorized scope | Explain not assigned; offer Request Access when allowed |


### Table 7


| State | Meaning | Permission |

| --- | --- | --- |

| Pending | Auditor requested access | No access granted |

| Approved | Admin approved and assignment created | Access granted to approved client/scope |

| Rejected | Admin rejected request | No access granted; optional reason shown |

| Cancelled (optional) | Requester withdrew request | No access granted |


### Table 8


| Element | Behavior |

| --- | --- |

| Dashboard cards | Show scoped counts: active clients/users, submitted sheets, pending reviews, approved sheets and publishing failures. |

| Recent Activity Log | Show actor, action, target and timestamp; detail view is read-only and permission-checked. |

| Quick Access Links | Navigate to existing modules; do not create duplicate workflows or separate data sources. |

| Refresh icon | Reload metrics and show last refresh time/error. |

| Client/year context | Make current scope visible; organization-wide metrics must be labelled explicitly. |


### Table 9


| Feature | Behavior |

| --- | --- |

| Asset Register Upload/Manage | Validate workbook type/size; parse sheet names, headers and sample rows; preview; save source version and parse result. |

| Master Input Sheet Upload/Manage | Identify target worksheet/header row and store persistent workbook ID for this client/scope. Confirm before replacement. |

| Input Form Designer | Map source Asset Register header → Auditor-facing label/input → Master Input Sheet destination column. |

| Save Draft | Save configuration draft with editor UID/time; not yet active for new forms. |

| Publish Configuration | Validate mappings/rules, create version and activate it. |

| Preview Form | Render the actual Auditor form using current/draft configuration. |

| Upload error | Keep prior active version; show cause and retry. |


### Table 10


| Field nature | Expected behavior | Validation |

| --- | --- | --- |

| Fixed / Read-only | Prefill from Asset Register; Auditor cannot edit | Must match selected client/year and asset row |

| Dropdown | Auditor selects Admin-defined option | Required and option membership validation |

| Automatic | System calculates using configured rule | Validate rule; record rule/config version |

| Manual | Auditor enters value | Validate type, length, range and required status |


### Table 11


| Subsection | Controls and behavior | Boundary |

| --- | --- | --- |

| User & Role Management | Add/invite user, search/filter, activate/deactivate, edit permitted profile fields, assign Admin/Auditor role, save with confirmation. | Role controls modules; never changes client assignment implicitly. |

| Client Assignment | Choose Auditor, assign/unassign clients and optionally fiscal years, view assignment lists, save changes. | Client access is independent of role. |

| Access Requests | View pending requests, approve/reject with optional reason, review history and notify requester. | Pending/rejected request never grants access. |

| Google Drive & Settings | Connect/reconnect/disconnect, select/create root folder, configure folders, test connection, show sync health. | A saved email or folder URL is not a working OAuth connection. |

| Admin Logout | End session and return to Login. | Only in Admin Control Panel. |


### Table 12


| Element | Action and expected behavior |

| --- | --- |

| Search / magnifier | Search selected client's register by asset ID, tag, description or configured searchable fields only. |

| Filter / funnel | Filter by available fields such as department, location, asset class or count status; include Clear Filters. |

| Asset result card | Show key read-only register data and count status; tap opens asset detail/form. |

| Asset detail | Show register values, allowed history, draft values and evidence summary. |

| Start Count / Edit Count | Open form using active configuration version; existing draft opens in edit mode. |

| Not Found / Manual Asset | If enabled, create separate exception/manual record; never fabricate a register asset ID. |

| Save Draft | Persist partial data; keep status Draft and show saved/saving/failed state. |

| Add Photo / Camera | Request permission when invoked; capture/select, preview and attach to correct verification. |

| Submit Daily Sheet | Validate fields, duplicates and evidence rules; confirm, then create submitted version. |


### Table 13


| Operation | Trigger | Required result |

| --- | --- | --- |

| Connect/create folders | Client setup or Drive settings | Save actual remote IDs and verify access. |

| Upload evidence | Capture/upload or configured sync step | Save remote file ID and status under correct client. |

| Upload daily sheet | Approved publishing workflow | Place under correct Auditor/date path; no duplicates on retry. |

| Update master workbook | After Admin approval | Update existing workbook by stable key and mapped destination columns. |

| Retry/reconcile | Partial failure or timeout | Check completed operation IDs before retry; avoid duplicate effects. |


### Table 14


| Object | Statuses | Rules |

| --- | --- | --- |

| User | Pending, Active, Disabled | Only Active users with valid role can enter. |

| Client | Setup Incomplete, Active, Archived | Archived clients are not selectable for new work unless explicitly permitted. |

| Drive connection | Not Connected, Connected, Needs Reauthorization, Error | Connected means test succeeded. |

| Daily sheet | Draft, Submitted, Pending Review, Returned for Correction, Rejected, Approved | Review status is separate from publishing status. |

| Publish job | Queued, Publishing, Published, Publish Failed | Track per-step results and retry safely. |

| Access request | Pending, Approved, Rejected, Cancelled | Only approved request creates/activates assignment. |

| File upload | Queued, Uploading, Uploaded, Failed | Persist retry state and remote IDs. |


### Table 15


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


### Table 16


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

| Trash/delete | Delete/remove | Confirm target; apply retention/soft-delete policy. |

| Check/Approve | Approve/publish | Validate and confirm; record approver UID/time. |

| X/Reject/Return | Reject or return | Require reason; distinguish final rejection from correction. |

| Refresh | Reload/retry | Do not duplicate side effects. |

| Bell | Notifications | Open details; unread state updates server-side. |

| Eye | Show/hide password | Visual masking only; never log credentials. |

| Back arrow | Return to previous screen | Warn if unsaved edits may be lost. |

| Switch Client | Change active scope | Return to selector and clear old scope cache. |

| Logout | End session | Clear local session/context; never delete server records. |


### Table 17


| Collection | Minimum required fields |

| --- | --- |

| dailySheets | sheetId, clientId, fiscalYearId, auditorUid, workDate, status, configVersion, version, timestamps |

| verifications | verificationId, sheetId, clientId, fiscalYearId, assetId/manualAssetId, values, evidenceIds, createdByUid, updatedByUid |

| publishJobs | jobId, sheetId, approvedVersion, masterFileId, state, stepResults, attempts, idempotencyKey, lastError |

| auditEvents | eventId, actorUid, actorRoleAtTime, clientId, fiscalYearId, action, target, result, timestamp, correlationId |


### Table 18


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


### Table 19


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


### Table 20


| Decision | Recommended default |

| --- | --- |

| Public self-registration | New account pending; no self-granted Admin; require email verification. |

| Fiscal-year scope | Client assignment plus year-level restriction where required. |

| Daily sheet uniqueness | One per Auditor + client + fiscal year + work date. |

| Returned vs rejected | Returned = editable correction; Rejected = final refusal. |

| Data authority | Firebase for operational state/metadata; Drive for approved output and persistent master workbook. |

| Offline support | If implemented, label local-only changes clearly; never say cloud-saved before sync. |

| Evidence retention | Preserve audit references to replaced/deleted evidence according to policy. |

| Admin count entry | Default Admin reviews; direct count entry requires separate explicit authorization. |
