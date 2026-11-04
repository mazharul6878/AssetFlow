# Security Specification (`security_spec.md`)

## 1. Data Invariants
- All collections (`far_assets`, `counting_records`, `master_data`, `exceptions`, `found_not_in_far`) are strictly scoped under `/users/{userId}/...`.
- Only the authenticated owner (`request.auth != null && request.auth.uid == userId`) can read, list, create, update, or delete documents in their own `/users/{userId}/...` tree.
- Every document write (`create` and `update`) must validate `incoming().userId == request.auth.uid`, enforce exact key allowlists (`hasAll` and `hasOnly`), validate string bounds, validate timestamps (`createdAt <= request.time`, `updatedAt <= request.time`), and preserve `createdAt` and `userId` immutability on `update`.
- `FARAsset` records represent immutable imported Fixed Asset Register rows; once created in a batch, their core FAR fields (`assetId`, `sourceRow`, `costTk`, `nbvTk`, `farQty`) cannot be tampered with by counting updates because physical counts are stored in `counting_records`.

## 2. The "Dirty Dozen" Payloads
1. **Unauthenticated Read**: `unauthDb.collection("users/alice/far_assets").get()` -> Rejected (`request.auth != null`).
2. **Cross-User Read**: `bobDb.collection("users/alice/counting_records").doc("asset_1").get()` -> Rejected (`request.auth.uid == userId`).
3. **Identity Spoofing on Create**: `aliceDb` writing `userId: "bob"` into `/users/alice/counting_records/asset_1` -> Rejected (`incoming().userId == request.auth.uid`).
4. **Shadow Update / Ghost Field Injection**: Adding `isAdmin: true` or `verifiedOverride: true` to `counting_records` -> Rejected (`keys().hasOnly(...)` and `affectedKeys().hasOnly(...)`).
5. **Immutable Field Tampering on Update**: Modifying `createdAt`, `userId`, `assetKey`, `assetId`, or `sourceRow` on an existing `CountingRecord` -> Rejected (`incoming().createdAt == existing().createdAt`).
6. **Future Timestamp Spoofing**: Writing a client timestamp in the future (`request.time + 1 day`) -> Rejected (`incoming().createdAt <= request.time`).
7. **Value Poisoning (Type Mismatch)**: Writing a boolean or string into `sourceRow` or `farQty` -> Rejected (`is int` check in `isValidFARAsset`).
8. **Resource Exhaustion (1MB String)**: Writing a 50,000-char string into `remarks` or `assetDescription` -> Rejected (`.size() <= MAX` bounds on all strings).
9. **Path ID Poisoning**: Using invalid characters or >128 chars in `{assetKey}` -> Rejected (`isValidId(assetKey)`).
10. **Missing Required Fields on Create**: Creating a `CountingRecord` without `result` or `countDate` -> Rejected (`keys().hasAll(...)`).
11. **Cross-User Delete**: `bobDb` attempting to delete `/users/alice/exceptions/ex_1` -> Rejected (`request.auth.uid == userId`).
12. **Blanket Root Collection Access**: Querying `/far_assets` or `/counting_records` at root -> Rejected by global default-deny catch-all.
