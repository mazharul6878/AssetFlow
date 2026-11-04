# Firebase Auth User Deletion Service Contract

## 1. Objective
Enable authorized System Owners and Admins to permanently delete user accounts from Firebase Authentication directly from Cloudflare Worker edge compute, eliminating the need for Firebase Cloud Functions.

## 2. API Endpoint Specification
- **Identity Platform Admin Endpoint:**
  `POST https://identitytoolkit.googleapis.com/v1/projects/{projectId}/accounts:delete`
- **Alternative REST Endpoint:**
  `POST https://identitytoolkit.googleapis.com/v1/accounts:delete`
- **Request Headers:**
  - `Authorization: Bearer <Google_OAuth_Access_Token>`
  - `Content-Type: application/json`
- **Request Body:**
```json
{
  "localId": "<Target_Firebase_UID>"
}
```

## 3. Required GCP IAM Permissions and OAuth Scope
- **IAM Permission:** `firebaseauth.users.delete`
- **GCP Predefined Roles:**
  - `roles/firebaseauth.admin` (Firebase Authentication Admin)
  - OR `roles/identityplatform.admin` (Identity Platform Admin)
- **OAuth 2.0 Scope:**
  `https://www.googleapis.com/auth/identitytoolkit`

## 4. Edge Service Account Token Minting
The Cloudflare Worker mints a short-lived (1-hour) Google OAuth access token using native Web Crypto (`crypto.subtle` / `jose`):
1. Service Account JSON key is stored securely in **Cloudflare Worker Secrets** (`GCP_SERVICE_ACCOUNT_JSON`), NEVER in the Android APK.
2. The Worker signs an RS256 assertion JWT:
   - `iss`: `<client_email>`
   - `scope`: `https://www.googleapis.com/auth/identitytoolkit`
   - `aud`: `https://oauth2.googleapis.com/token`
3. Exchanges the assertion with `https://oauth2.googleapis.com/token`.
4. Uses the returned `access_token` to invoke `accounts:delete`.

## 5. Security Guardrails
- **Caller Authorization:** The invoking user must have `globalRole = 'Owner'` or an Admin with `canDeleteClientsAndUsers = true` in `CONTROL_DB`.
- **Tombstone Recording:** Prior to calling the delete API, the target UID hash and email are appended to `deleted_identities` in `CONTROL_DB` to prevent re-registration exploits.
- **Client Membership Revocation:** All active memberships for the user are immediately set to `account_status = 'Revoked'`.

## 6. Official References
- Google Identity Platform REST API: https://cloud.google.com/identity-platform/docs/reference/rest/v1/projects.accounts/delete
- Firebase Auth IAM Permissions: https://cloud.google.com/iam/docs/roles-permissions/firebaseauth
