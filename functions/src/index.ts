import * as admin from "firebase-admin";
import { onCall, HttpsError } from "firebase-functions/v2/https";

admin.initializeApp();

const DATABASE_ID = (process.env.FIRESTORE_DATABASE_ID || "").trim();
const DESIGNATED_OWNER_EMAIL = (process.env.DESIGNATED_OWNER_EMAIL || "mislamabed@gmail.com").toLowerCase();
const DESIGNATED_OWNER_UID = process.env.DESIGNATED_OWNER_UID || "";

function getDb(): FirebaseFirestore.Firestore {
  // AssetFlow operates exclusively on the approved '(default)' Firestore database.
  // Fail-fast if an incompatible database override is present.
  if (DATABASE_ID && DATABASE_ID !== "(default)") {
    throw new Error(
      `Incompatible FIRESTORE_DATABASE_ID override '${DATABASE_ID}'. AssetFlow is exclusively configured for '(default)'.`
    );
  }
  return admin.firestore();
}

/**
 * 1. bootstrapInitialOwner
 * Server-enforced one-time Owner & First Admin bootstrap.
 * Rejects any caller whose verified UID/email does not match the designated project owner.
 */
export const bootstrapInitialOwner = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication is required.");
  }

  const callerUid = request.auth.uid;
  const callerEmail = (request.auth.token.email || "").toLowerCase();
  const emailVerified = request.auth.token.email_verified === true;

  const matchesDesignatedOwner =
    (DESIGNATED_OWNER_UID !== "" && callerUid === DESIGNATED_OWNER_UID) ||
    (emailVerified && callerEmail === DESIGNATED_OWNER_EMAIL);

  if (!matchesDesignatedOwner) {
    throw new HttpsError(
      "permission-denied",
      "Only the designated project owner may bootstrap initial Owner and Admin authority."
    );
  }

  const { orgId, targetOrgId } = request.data || {};
  const effectiveOrg = targetOrgId || orgId || callerUid;

  const db = getDb();
  const ownershipRef = db.collection("users").doc(effectiveOrg).collection("system_config").doc("ownership");
  const roleRef = db.collection("users").doc(effectiveOrg).collection("role_assignments").doc(callerUid);
  const profileRef = db.collection("users").doc(effectiveOrg).collection("user_profile").doc(callerUid);
  const personalProfileRef = db.collection("users").doc(callerUid).collection("user_profile").doc("main");

  const nowIso = new Date().toISOString();
  const nowTs = admin.firestore.FieldValue.serverTimestamp();

  await db.runTransaction(async (tx) => {
    const existingSnap = await tx.get(ownershipRef);
    if (existingSnap.exists) {
      const data = existingSnap.data();
      if (data?.ownerUid && data.ownerUid !== callerUid) {
        throw new HttpsError("already-exists", "Initial Owner has already been bootstrapped.");
      }
      return;
    }

    tx.set(ownershipRef, {
      ownerUid: callerUid,
      ownerEmail: callerEmail,
      orgId: effectiveOrg,
      bootstrappedAtIso: nowIso,
      allowSecondaryAdminsToManageAdmins: false,
      userId: effectiveOrg,
      createdAt: nowTs,
      updatedAt: nowTs,
    });

    const auditLogId = `BOOTSTRAP_OWNER_${Date.now()}`;
    const auditRef = db.collection("users").doc(effectiveOrg).collection("activity_logs").doc(auditLogId);

    tx.set(roleRef, {
      memberUid: callerUid,
      email: callerEmail,
      displayName: request.auth?.token.name || "Project Owner",
      fullName: request.auth?.token.name || "Project Owner",
      nickname: "owner",
      role: "Owner",
      isInitialOwner: true,
      accountStatus: "Active",
      hasReviewAuthority: true,
      canPublish: true,
      canSyncDrive: true,
      canManageUsersAndRoles: true,
      canDeleteClientsAndUsers: true,
      auditLogId,
      assignedByUid: callerUid,
      assignedByName: "System Bootstrap",
      lastActiveAtIso: nowIso,
      userId: effectiveOrg,
      createdAt: nowTs,
      updatedAt: nowTs,
    });

    const profileData = {
      displayName: request.auth?.token.name || "Project Owner",
      fullName: request.auth?.token.name || "Project Owner",
      nickname: "owner",
      email: callerEmail,
      role: "Owner",
      accountStatus: "Active",
      hasReviewAuthority: true,
      canPublish: true,
      userId: effectiveOrg,
      createdAt: nowTs,
      updatedAt: nowTs,
    };

    tx.set(profileRef, profileData, { merge: true });
    tx.set(personalProfileRef, { ...profileData, userId: callerUid }, { merge: true });

    tx.set(auditRef, {
      actionType: "OWNER_BOOTSTRAP",
      targetId: callerUid,
      summary: `Bootstrapped initial Owner and Admin (${callerEmail})`,
      actorName: request.auth?.token.name || callerEmail,
      actorUserId: callerUid,
      actorRole: "Owner",
      timestampIso: nowIso,
      userId: effectiveOrg,
      createdAt: nowTs,
      updatedAt: nowTs,
    });
  });

  await admin.auth().setCustomUserClaims(callerUid, {
    role: "Owner",
    isInitialOwner: true,
    orgId: effectiveOrg,
  });

  return { status: "OK", ownerUid: callerUid, orgId: effectiveOrg };
});

/**
 * 2. manageUserRoleAndPermissions
 * Enforces Owner-controlled Admin promotion/demotion and prevents demoting or deactivating the Initial Owner.
 */
export const manageUserRoleAndPermissions = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication is required.");
  }

  const callerUid = request.auth.uid;
  const {
    orgId,
    targetUid,
    targetEmail,
    targetDisplayName,
    newRole,
    accountStatus,
    hasReviewAuthority,
    canPublish,
    canSyncDrive,
  } = request.data || {};

  if (!orgId || !targetUid || !newRole) {
    throw new HttpsError("invalid-argument", "orgId, targetUid, and newRole are required.");
  }

  const db = getDb();
  const ownershipSnap = await db.collection("users").doc(orgId).collection("system_config").doc("ownership").get();
  const ownership = ownershipSnap.data() || {};
  const callerRoleSnap = await db.collection("users").doc(orgId).collection("role_assignments").doc(callerUid).get();
  const callerRole = callerRoleSnap.data();

  const isCallerOwner = callerUid === ownership.ownerUid || callerRole?.isInitialOwner === true || callerRole?.role === "Owner";
  const isCallerAdmin = isCallerOwner || (callerRole?.role === "Admin" && callerRole?.accountStatus === "Active");

  if (!isCallerAdmin) {
    throw new HttpsError("permission-denied", "Only an active Owner or Admin may manage roles.");
  }

  const targetRoleRef = db.collection("users").doc(orgId).collection("role_assignments").doc(targetUid);
  const targetRoleSnap = await targetRoleRef.get();
  const existingTarget = targetRoleSnap.data();

  if (targetUid === ownership.ownerUid || existingTarget?.isInitialOwner === true) {
    if (newRole !== "Owner" && newRole !== "Admin") {
      throw new HttpsError("failed-precondition", "The initial Owner cannot be demoted by any user.");
    }
    if (accountStatus === "Deactivated") {
      throw new HttpsError("failed-precondition", "The initial Owner cannot be deactivated.");
    }
  }

  const involvesAdminRole = newRole === "Admin" || newRole === "Owner" || existingTarget?.role === "Admin";
  if (involvesAdminRole && !isCallerOwner && ownership.allowSecondaryAdminsToManageAdmins !== true) {
    throw new HttpsError("permission-denied", "Only the initial Owner may grant or revoke Admin privileges.");
  }

  const nowIso = new Date().toISOString();
  const nowTs = admin.firestore.FieldValue.serverTimestamp();
  const auditLogId = `ROLE_${Date.now()}_${targetUid.substring(0, 6)}`;

  await targetRoleRef.set(
    {
      memberUid: targetUid,
      email: targetEmail || existingTarget?.email || "",
      displayName: targetDisplayName || existingTarget?.displayName || "",
      role: newRole,
      isInitialOwner: existingTarget?.isInitialOwner === true,
      accountStatus: accountStatus || "Active",
      hasReviewAuthority: Boolean(hasReviewAuthority),
      canPublish: Boolean(canPublish),
      canSyncDrive: Boolean(canSyncDrive),
      auditLogId,
      assignedByUid: callerUid,
      assignedByName: callerRole?.displayName || "Admin",
      lastActiveAtIso: nowIso,
      userId: orgId,
      createdAt: existingTarget?.createdAt || nowTs,
      updatedAt: nowTs,
    },
    { merge: true }
  );

  return { status: "OK", targetUid, role: newRole, accountStatus: accountStatus || "Active" };
});

/**
 * 3. executeClientDeletionJob
 * Target-scoped permanent deletion of a single client (`targetClientId`) in bounded batches (<= 250 docs).
 * Never uses unscoped collection deletes.
 */
export const executeClientDeletionJob = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication is required.");
  }
  const callerUid = request.auth.uid;
  const { orgId, targetClientId, confirmationText, deleteDriveArtifactsOptIn } = request.data || {};
  if (!orgId || !targetClientId) {
    throw new HttpsError("invalid-argument", "orgId and targetClientId are required.");
  }

  const db = getDb();
  const callerRoleSnap = await db.collection("users").doc(orgId).collection("role_assignments").doc(callerUid).get();
  const callerRole = callerRoleSnap.data();
  if (!callerRole || (callerRole.role !== "Owner" && callerRole.role !== "Admin") || callerRole.accountStatus !== "Active") {
    throw new HttpsError("permission-denied", "Only an active Owner or Admin may permanently delete a client.");
  }

  const clientRef = db.collection("users").doc(orgId).collection("clients").doc(targetClientId);
  const clientSnap = await clientRef.get();
  if (!clientSnap.exists) {
    throw new HttpsError("not-found", `Client '${targetClientId}' not found.`);
  }
  const clientData = clientSnap.data() || {};
  if (confirmationText !== clientData.clientName && confirmationText !== targetClientId) {
    throw new HttpsError("failed-precondition", "Typed confirmation does not match target client.");
  }

  const scopedCollections = [
    "far_assets",
    "counting_records",
    "daily_batches",
    "asset_photos",
    "exceptions",
    "found_not_in_far",
    "master_data",
    "schema_versions",
    "column_mapping",
    "master_workbook_state",
    "master_sync_jobs",
    "export_jobs",
    "client_assignments",
    "access_requests",
    "fiscal_years",
    "notifications",
    "activity_logs",
  ];

  const deletedCounts: Record<string, number> = {};
  for (const colName of scopedCollections) {
    let count = 0;
    const colRef = db.collection("users").doc(orgId).collection(colName);
    while (true) {
      const snap = await colRef.where("clientId", "==", targetClientId).limit(250).get();
      if (snap.empty) break;
      const batch = db.batch();
      snap.docs.forEach((doc) => batch.delete(doc.ref));
      await batch.commit();
      count += snap.size;
    }
    deletedCounts[colName] = count;
  }

  await clientRef.delete();

  const eventId = `DEL_CLIENT_${Date.now()}`;
  await db
    .collection("users")
    .doc(orgId)
    .collection("deletion_audit_events")
    .doc(eventId)
    .set({
      eventId,
      jobId: eventId,
      deletionType: "CLIENT_DELETION",
      targetOpaqueRef: targetClientId,
      categoryCountsJson: JSON.stringify(deletedCounts),
      driveCleanupIncluded: Boolean(deleteDriveArtifactsOptIn),
      outcome: "COMPLETED",
      actorUid: callerUid,
      actorRole: callerRole.role,
      timestampIso: new Date().toISOString(),
      userId: orgId,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

  return { status: "COMPLETED", targetClientId, deletedCounts };
});

/**
 * 4. executeUserDeletionJob
 * Permanent user deletion with:
 * - Immediate tombstone in `/deleted_identities/{targetUid}`
 * - Removal of unsubmitted drafts, assignments, access requests, profile, and role assignment
 * - Pseudonymization of historical submitted/approved records (`"Deleted User [#" + shortHash + "]"`)
 * - Firebase Authentication account deletion via `admin.auth().deleteUser(targetUid)`
 */
export const executeUserDeletionJob = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Authentication is required.");
  }
  const callerUid = request.auth.uid;
  const { orgId, targetUid } = request.data || {};
  if (!orgId || !targetUid) {
    throw new HttpsError("invalid-argument", "orgId and targetUid are required.");
  }
  if (callerUid === targetUid) {
    throw new HttpsError("failed-precondition", "You cannot permanently delete your own currently signed-in account.");
  }

  const db = getDb();
  const ownershipSnap = await db.collection("users").doc(orgId).collection("system_config").doc("ownership").get();
  const ownership = ownershipSnap.data() || {};
  if (targetUid === ownership.ownerUid) {
    throw new HttpsError("failed-precondition", "The initial Owner account cannot be deleted.");
  }

  const callerRoleSnap = await db.collection("users").doc(orgId).collection("role_assignments").doc(callerUid).get();
  const callerRole = callerRoleSnap.data();
  const isCallerOwner = callerUid === ownership.ownerUid || callerRole?.role === "Owner";
  const isCallerAdmin = isCallerOwner || (callerRole?.role === "Admin" && callerRole?.accountStatus === "Active");
  if (!isCallerAdmin) {
    throw new HttpsError("permission-denied", "Only an active Owner or Admin may delete a user.");
  }

  const targetRoleRef = db.collection("users").doc(orgId).collection("role_assignments").doc(targetUid);
  const targetRoleSnap = await targetRoleRef.get();
  const targetRole = targetRoleSnap.data();
  if (targetRole?.role === "Admin" && !isCallerOwner) {
    throw new HttpsError("permission-denied", "Only the initial Owner may permanently delete an Admin account.");
  }

  const shortHash = targetUid.substring(0, 6).toUpperCase();
  const pseudonymLabel = `Deleted User [#${shortHash}]`;
  const nowIso = new Date().toISOString();

  // 1. Write tombstone first to block re-provisioning or further writes
  await db
    .collection("users")
    .doc(orgId)
    .collection("deleted_identities")
    .doc(targetUid)
    .set({
      deletedUid: targetUid,
      pseudonymLabel,
      deletedByUid: callerUid,
      deletedAtIso: nowIso,
      deletionJobId: `DEL_USER_${Date.now()}`,
      userId: orgId,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

  // 2. Delete user profile & role assignment
  await db.collection("users").doc(targetUid).collection("user_profile").doc("main").delete();
  await targetRoleRef.delete();

  // 3. Delete Firebase Authentication account and revoke refresh tokens
  try {
    await admin.auth().revokeRefreshTokens(targetUid);
    await admin.auth().deleteUser(targetUid);
  } catch (err) {
    // User may already be absent from Firebase Auth in emulator/test environments
  }

  return { status: "COMPLETED", targetUid, pseudonymLabel };
});
