const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT || "demo-assetflow";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_AUDITOR_UID = "charlie_789";
const DANA_PUBLISHER_UID = "dana_999";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validFarAsset(uid, now) {
  return {
    sl: 1,
    assetId: "FA-1001",
    sourceRow: 5,
    assetDescription: "Dell PowerEdge R750 Server",
    assetClass: "IT Equipment",
    capitalizedOn: "2024-01-15",
    costTk: 850000.0,
    nbvTk: 620000.0,
    farQty: 2,
    farQtyPresent: true,
    method: "Straight Line",
    sheetOrigin: "Count Sheet",
    importBatchId: "batch_1",
    userId: uid,
    createdAt: now,
    updatedAt: now,
  };
}

function validCountingRecord(workspaceUid, counterUid, now) {
  return {
    assetKey: "FA-1001_row_5",
    assetId: "FA-1001",
    sourceRow: 5,
    found: "Yes",
    countDate: "2026-10-28",
    countedBy: "M. Islam",
    countedByUserId: counterUid,
    buildingSite: "HQ Building",
    buildingSiteOther: "",
    floor: "3rd Floor",
    floorOther: "",
    roomLocation: "Server Room 302",
    roomLocationOther: "",
    department: "IT",
    departmentOther: "",
    userCustodian: "R. Karim",
    tagStatus: "Tag OK",
    tagNumberAcn: "004512A",
    brandModel: "Dell PowerEdge R750",
    physicalQtyText: "2",
    condition: "Good",
    inUse: "Yes",
    photoRef: "local://photo1.jpg",
    remarks: "Verified in rack B",
    qtyDiffText: "0",
    result: "Verified",
    countingStatus: "Counted",
    applicableExceptions: "",
    reviewedBy: "Lead Reviewer",
    reviewedByUserId: workspaceUid,
    reviewerRequiredFlag: false,
    reviewedAt: "2026-10-28T05:00:00Z",
    reviewStatus: "Reviewed",
    clientExplanation: "",
    isDraft: false,
    auditTrailSummary: "Created 2026-10-28",
    userId: workspaceUid,
    createdAt: now,
    updatedAt: now,
  };
}

function validUserProfile(uid, now) {
  return {
    displayName: "M. Islam",
    email: "mislam@example.com",
    role: "Auditor",
    userId: uid,
    createdAt: now,
    updatedAt: now,
  };
}

function validRoleAssignment(workspaceUid, memberUid, role, now) {
  const auditLogId = `role_${memberUid}_${now.getTime()}`;
  return {
    memberUid: memberUid,
    email: `${memberUid}@example.com`,
    displayName: `User ${memberUid}`,
    role: role,
    hasReviewAuthority: role === "Reviewer",
    canPublish: false,
    canSyncDrive: false,
    auditLogId,
    assignedByUid: workspaceUid,
    assignedByName: "Alice Admin",
    lastActiveAtIso: "2026-10-28T05:00:00Z",
    userId: workspaceUid,
    createdAt: now,
    updatedAt: now,
  };
}

function validActivityLog(workspaceUid, actorUid, actionType, targetId, now, extra = {}) {
  return {
    actionType,
    targetId,
    targetOwnerUid: "",
    summary: `${actionType} ${targetId}`,
    actorName: `Actor ${actorUid}`,
    actorUserId: actorUid,
    actorRole: "Admin",
    timestampIso: now.toISOString(),
    userId: workspaceUid,
    createdAt: now,
    updatedAt: now,
    ...extra,
  };
}

async function writeRoleAssignment(adminDb, workspaceUid, memberUid, role, now, permissions = {}) {
  const assignment = {
    ...validRoleAssignment(workspaceUid, memberUid, role, now),
    ...permissions,
  };
  const audit = validActivityLog(workspaceUid, workspaceUid, "ROLE_ASSIGNMENT", memberUid, now);
  const batch = adminDb.batch();
  batch.set(adminDb.doc(`users/${workspaceUid}/role_assignments/${memberUid}`), assignment);
  batch.set(adminDb.doc(`users/${workspaceUid}/activity_logs/${assignment.auditLogId}`), audit);
  await batch.commit();
  return assignment;
}

function validDailyBatch(workspaceUid, submitterUid, now, overrides = {}) {
  return {
    batchId: "BATCH_ALICE_2026-10-28",
    userId: workspaceUid,
    submitterUid,
    submitterFullName: "Charlie Auditor",
    submitterNickname: "charlie",
    countingDate: "2026-10-28",
    versionNumber: 1,
    recordCount: 1,
    foundCount: 1,
    notFoundCount: 0,
    notAccessibleCount: 0,
    manualEntryCount: 0,
    status: "Submitted / Pending Review",
    recordsJson: JSON.stringify({ submitterUid, records: [{ assetKey: "FA-1001_row_5", updatedAtMillis: now.getTime() }] }),
    adminRemarks: "",
    remarksByUid: "",
    remarksByName: "",
    remarksAtIso: "",
    reviewedByUid: "",
    reviewedByName: "",
    reviewedAtIso: "",
    publishedByUid: "",
    publishedByName: "",
    publishedAtIso: "",
    publishedMasterFileId: "",
    publishedMasterVersion: 0,
    publicationAuditLogId: "",
    historyVersionsJson: "[]",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validProjectSettings(workspaceUid, now, masterWorkbookVersion = 1) {
  return {
    projectName: "AssetFlow Security Test",
    connectedDriveEmail: "",
    rootDriveFolderId: "root_folder",
    photosDriveFolderId: "photos_folder",
    reportsDriveFolderId: "reports_folder",
    backupsDriveFolderId: "backups_folder",
    masterWorkbookDriveFileId: "master_file",
    masterWorkbookFileName: "Master_Input_Sheet.xlsx",
    masterWorkbookVersion,
    lastMasterUpdateAtIso: "",
    lastMasterUpdateStatus: "Ready",
    lastMasterUpdateError: "",
    driveConnectionStatus: "Not Connected",
    projectStartDate: "2026-11-01",
    originalPlannedCompletionDate: "2026-12-10",
    approvedCompletionDate: "2026-12-10",
    workingDaysPerWeek: 6,
    nonWorkingDatesCsv: "",
    planRevisionHistory: "",
    userId: workspaceUid,
    createdAt: now,
    updatedAt: now,
  };
}

function validCountingPlanStage(workspaceUid, now) {
  return {
    stageOrder: 1,
    stageName: "AUC & Asset Clearing",
    dayRangeLabel: "Days 1-5",
    startDate: "2026-11-01",
    endDate: "2026-11-06",
    allocatedDays: 5,
    originalAllocatedDays: 5,
    targetRecordCount: 25,
    assignedTeamMembers: "Field Team A",
    dependsOnStageId: "",
    assetClassesIncluded: "AUC & Asset Clearing",
    revisionCount: 0,
    revisionNotes: "Initial 34-day baseline",
    updatedBy: "Alice Admin",
    updatedByUserId: workspaceUid,
    userId: workspaceUid,
    createdAt: now,
    updatedAt: now,
  };
}

test("Unauthenticated user: cannot read or list far_assets", async () => {
  const unauthedDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthedDb.doc(`users/${ALICE_UID}/far_assets/FA-1001_row_5`).get());
});

test("Authenticated user: can create and read own UserProfile, FARAsset, CountingRecord, and CountingPlanStage", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/user_profile/main`).set(validUserProfile(ALICE_UID, now))
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/far_assets/FA-1001_row_5`).set(validFarAsset(ALICE_UID, now))
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/counting_records/FA-1001_row_5`).set(validCountingRecord(ALICE_UID, ALICE_UID, now))
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/counting_plan_stages/stage_01`).set(validCountingPlanStage(ALICE_UID, now))
  );
});

test("Cross-user isolation: Unauthorized Bob cannot read or modify Alice's CountingRecord or UserProfile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/user_profile/main`).set(validUserProfile(ALICE_UID, now))
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/counting_records/FA-1001_row_5`).set(validCountingRecord(ALICE_UID, ALICE_UID, now))
  );
  await assertFails(bobDb.doc(`users/${ALICE_UID}/user_profile/main`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/counting_records/FA-1001_row_5`).get());
});

test("Admin RBAC & Team Collaboration: Authorized Auditor Charlie can count assets concurrently but CANNOT modify Admin plan stages or escalate role", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_AUDITOR_UID).firestore();
  const now = new Date();

  // Admin Alice registers Charlie as an Auditor in role_assignments
  await writeRoleAssignment(aliceDb, ALICE_UID, CHARLIE_AUDITOR_UID, "Auditor", now);

  // Charlie (Auditor) CAN write a counting record concurrently to Alice's project
  await assertSucceeds(
    charlieDb
      .doc(`users/${ALICE_UID}/counting_records/FA-1002_row_6`)
      .set({
        ...validCountingRecord(ALICE_UID, CHARLIE_AUDITOR_UID, now),
        assetKey: "FA-1002_row_6",
        assetId: "FA-1002",
        sourceRow: 6,
        reviewStatus: "Pending Review",
        reviewedBy: "",
        reviewedByUserId: "",
        reviewedAt: "",
      })
  );

  // Charlie (Auditor) CANNOT create or modify Admin counting_plan_stages
  await assertFails(
    charlieDb
      .doc(`users/${ALICE_UID}/counting_plan_stages/stage_01`)
      .set(validCountingPlanStage(ALICE_UID, now))
  );

  // Charlie (Auditor) CANNOT self-escalate his role_assignment to Admin
  await assertFails(
    charlieDb
      .doc(`users/${ALICE_UID}/role_assignments/${CHARLIE_AUDITOR_UID}`)
      .set(validRoleAssignment(ALICE_UID, CHARLIE_AUDITOR_UID, "Admin", now))
  );
});

test("Self-promotion and self-granted review/publication permissions are denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const now = new Date();
  await writeRoleAssignment(aliceDb, ALICE_UID, BOB_UID, "Auditor", now);
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/role_assignments/${BOB_UID}`).set({
      ...validRoleAssignment(ALICE_UID, BOB_UID, "Admin", now),
      hasReviewAuthority: true,
      canPublish: true,
      canSyncDrive: true,
    })
  );
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/user_profile/main`).set({
      ...validUserProfile(ALICE_UID, now),
      role: "Admin",
      hasReviewAuthority: true,
      canPublish: true,
    })
  );
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/role_assignments/${CHARLIE_AUDITOR_UID}`).set(
      validRoleAssignment(ALICE_UID, CHARLIE_AUDITOR_UID, "Reviewer", now)
    )
  );
});

test("Reviewer can send remarks but cannot publish or change batch authority fields", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const reviewerDb = testEnv.authenticatedContext(CHARLIE_AUDITOR_UID).firestore();
  const now = new Date();
  await writeRoleAssignment(aliceDb, ALICE_UID, CHARLIE_AUDITOR_UID, "Reviewer", now, {
    hasReviewAuthority: true,
    canPublish: false,
    canSyncDrive: false,
  });
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().doc(`users/${ALICE_UID}/daily_batches/BATCH_ALICE_2026-10-28`)
      .set(validDailyBatch(ALICE_UID, BOB_UID, now));
  });
  const batchRef = reviewerDb.doc(`users/${ALICE_UID}/daily_batches/BATCH_ALICE_2026-10-28`);
  await assertSucceeds(batchRef.update({
    status: "Changes Requested",
    remarksByUid: CHARLIE_AUDITOR_UID,
    remarksByName: "Charlie Reviewer",
    remarksAtIso: now.toISOString(),
    adminRemarks: "Please correct the tag number.",
    updatedAt: now,
  }));
  await assertFails(batchRef.update({
    status: "Published",
    publishedByUid: CHARLIE_AUDITOR_UID,
    publishedByName: "Charlie Reviewer",
    publishedAtIso: now.toISOString(),
    publishedMasterFileId: "master_file",
    publishedMasterVersion: 2,
    publicationAuditLogId: "forged_publication",
    updatedAt: now,
  }));
});

test("Authorized publication is audited and duplicate publication is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const publisherDb = testEnv.authenticatedContext(DANA_PUBLISHER_UID).firestore();
  const now = new Date();
  await writeRoleAssignment(aliceDb, ALICE_UID, DANA_PUBLISHER_UID, "Reviewer", now, {
    hasReviewAuthority: true,
    canPublish: true,
    canSyncDrive: true,
  });
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.doc(`users/${ALICE_UID}/project_settings/main`).set(validProjectSettings(ALICE_UID, now, 2));
    await db.doc(`users/${ALICE_UID}/daily_batches/BATCH_ALICE_2026-10-28`).set(
      validDailyBatch(ALICE_UID, BOB_UID, now, {
        status: "Under Review",
        reviewedByUid: DANA_PUBLISHER_UID,
        reviewedByName: "Dana Publisher",
        reviewedAtIso: now.toISOString(),
      })
    );
  });

  const batchId = "BATCH_ALICE_2026-10-28";
  const auditLogId = "publish_batch_dana_1";
  const audit = validActivityLog(ALICE_UID, DANA_PUBLISHER_UID, "PUBLISH_DAILY_BATCH", batchId, now);
  const writeBatch = publisherDb.batch();
  writeBatch.update(publisherDb.doc(`users/${ALICE_UID}/daily_batches/${batchId}`), {
    status: "Published",
    publishedByUid: DANA_PUBLISHER_UID,
    publishedByName: "Dana Publisher",
    publishedAtIso: now.toISOString(),
    publishedMasterFileId: "master_file",
    publishedMasterVersion: 2,
    publicationAuditLogId: auditLogId,
    updatedAt: now,
  });
  writeBatch.set(publisherDb.doc(`users/${ALICE_UID}/activity_logs/${auditLogId}`), audit);
  await assertSucceeds(writeBatch.commit());

  await assertFails(publisherDb.doc(`users/${ALICE_UID}/daily_batches/${batchId}`).update({
    publishedMasterVersion: 3,
    publishedAtIso: new Date(now.getTime() + 1000).toISOString(),
    updatedAt: new Date(now.getTime() + 1000),
  }));
});

test("Shadow update / ghost field injection and identity spoofing are rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/counting_records/FA-1001_row_5`).set({
      ...validCountingRecord(ALICE_UID, ALICE_UID, now),
      isAdmin: true,
    })
  );
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/counting_records/FA-1001_row_5`).set({
      ...validCountingRecord(BOB_UID, ALICE_UID, now),
    })
  );
});
