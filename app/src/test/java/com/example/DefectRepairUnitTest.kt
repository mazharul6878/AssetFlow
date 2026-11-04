package com.example

import com.example.data.drive.GoogleDriveService
import com.example.data.model.UserRole
import com.example.data.remote.ClientDeletionResult
import com.example.data.remote.FirebaseFunctionsService
import com.example.data.remote.OwnerBootstrapResult
import com.example.data.remote.RoleManagementResult
import com.example.data.remote.UserDeletionResult
import com.example.data.repository.AuditRepository
import com.example.data.repository.FirestoreCollections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests verifying the four controlled defect repairs:
 * 1. Firestore canonical collection naming alignment
 * 2. AUTH-02 role mismatch enforcement and fail-closed admin bootstrap
 * 3. Cloud Functions data contracts and service scaffolding
 * 4. Google Drive OAuth access token state and connection test
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefectRepairUnitTest {

  @Test
  fun phase1_canonicalFirestoreCollections_matchSecurityRulesAndFunctions() {
    assertEquals("counting_records", FirestoreCollections.COUNTING_RECORDS)
    assertEquals("user_profile", FirestoreCollections.USER_PROFILE)
    assertEquals("activity_logs", FirestoreCollections.ACTIVITY_LOGS)
    assertEquals("found_not_in_far", FirestoreCollections.FOUND_NOT_IN_FAR)
    assertEquals("schema_versions", FirestoreCollections.SCHEMA_VERSIONS)
    assertEquals("clients", FirestoreCollections.CLIENTS)
    assertEquals("fiscal_years", FirestoreCollections.FISCAL_YEARS)
    assertEquals("role_assignments", FirestoreCollections.ROLE_ASSIGNMENTS)
    assertEquals("daily_batches", FirestoreCollections.DAILY_BATCHES)
  }

  @Test
  fun phase2_bootstrapAuthenticatedMember_rejectsSelfSelectedAdminWithAuth02() = runBlocking {
    val repo = AuditRepository(db = null)

    // Non-designated owner attempting to self-select Admin role without server provisioning
    val result = repo.bootstrapAuthenticatedMember(
      uid = "auditor_user_456",
      email = "regular.auditor@test.com",
      displayName = "Regular Auditor",
      preferredNickname = "auditor",
      requestedRole = UserRole.ADMIN
    )

    assertTrue("Self-selected admin must fail closed", result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue("Exception must be SecurityException", ex is SecurityException)
    assertTrue(
      "Error must contain AUTH-02 code",
      ex?.message?.contains("AUTH-02") == true
    )
    assertTrue(
      "Error must explain role mismatch",
      ex?.message?.contains("Account is not provisioned as an Administrator") == true
    )
  }

  @Test
  fun phase2_bootstrapAuthenticatedMember_unprovisionedAuditor_failsClosed() = runBlocking {
    val repo = AuditRepository(db = null)

    val result = repo.bootstrapAuthenticatedMember(
      uid = "auditor_user_789",
      email = "auditor789@test.com",
      displayName = "Unprovisioned Auditor",
      preferredNickname = "unprovisioned",
      requestedRole = UserRole.AUDITOR
    )

    assertTrue("Unprovisioned auditor must fail closed", result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue("Exception must be SecurityException", ex is SecurityException)
    assertTrue(
      "Error must inform user account is not provisioned",
      ex?.message?.contains("Account is not provisioned for this workspace") == true
    )
  }

  @Test
  fun phase2_bootstrapAuthenticatedMember_firstDesignatedOwner_bootstrapCallableExecutesBeforeRoleLookup() = runBlocking {
    var callableExecutionCount = 0
    val mockFunctions = object : FirebaseFunctionsService() {
      override suspend fun bootstrapInitialOwner(orgId: String): Result<OwnerBootstrapResult> {
        callableExecutionCount++
        return Result.success(OwnerBootstrapResult("OK", "owner_uid_first", orgId))
      }
    }
    val repo = AuditRepository(db = null, functionsService = mockFunctions)

    val result = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid_first",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "First System Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )

    assertTrue("Designated owner bootstrap must succeed", result.isSuccess)
    assertEquals("Bootstrap Cloud Function must be called exactly once before role completion", 1, callableExecutionCount)
    val profile = result.getOrThrow()
    assertEquals(UserRole.OWNER, profile.role)
    assertTrue("Owner must have review authority", profile.hasReviewAuthority)
    assertTrue("Owner must have publish authority", profile.canPublish)
  }

  @Test
  fun phase2_bootstrapAuthenticatedMember_existingDesignatedOwner_repeatedLogin_doesNotFail() = runBlocking {
    var callableExecutionCount = 0
    val mockFunctions = object : FirebaseFunctionsService() {
      override suspend fun bootstrapInitialOwner(orgId: String): Result<OwnerBootstrapResult> {
        callableExecutionCount++
        // Cloud Function is idempotent: returns status OK on repeated invocation by legitimate owner
        return Result.success(OwnerBootstrapResult("OK", "owner_uid_existing", orgId))
      }
    }
    val repo = AuditRepository(db = null, functionsService = mockFunctions)

    // First login
    val result1 = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid_existing",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Existing System Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )
    assertTrue("First login must succeed", result1.isSuccess)

    // Repeated login
    val result2 = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid_existing",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Existing System Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )
    assertTrue("Repeated login by legitimate owner must not fail", result2.isSuccess)
    assertEquals(2, callableExecutionCount)
    assertEquals(UserRole.OWNER, result2.getOrThrow().role)
  }

  @Test
  fun phase2_bootstrapAuthenticatedMember_workspaceOwnedByAnotherUid_accessIsRejected() = runBlocking {
    // Case 1: Backend returns already-exists error because another UID already owns the workspace
    val mockFailingFunctions = object : FirebaseFunctionsService() {
      override suspend fun bootstrapInitialOwner(orgId: String): Result<OwnerBootstrapResult> {
        return Result.failure(IllegalStateException("already-exists: Initial Owner has already been bootstrapped."))
      }
    }
    val repo1 = AuditRepository(db = null, functionsService = mockFailingFunctions)
    val result1 = repo1.bootstrapAuthenticatedMember(
      uid = "intruder_uid",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Intruder User",
      preferredNickname = "intruder",
      requestedRole = UserRole.ADMIN
    )
    assertTrue("Access must be rejected when workspace is already owned", result1.isFailure)
    assertTrue("Exception must be SecurityException", result1.exceptionOrNull() is SecurityException)
    assertTrue(
      result1.exceptionOrNull()?.message?.contains("Another user account has already been registered") == true
    )

    // Case 2: Backend returns a mismatched owner UID
    val mockMismatchedFunctions = object : FirebaseFunctionsService() {
      override suspend fun bootstrapInitialOwner(orgId: String): Result<OwnerBootstrapResult> {
        return Result.success(OwnerBootstrapResult("OK", "actual_owner_uid_different", orgId))
      }
    }
    val repo2 = AuditRepository(db = null, functionsService = mockMismatchedFunctions)
    val result2 = repo2.bootstrapAuthenticatedMember(
      uid = "intruder_uid",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Intruder User",
      preferredNickname = "intruder",
      requestedRole = UserRole.ADMIN
    )
    assertTrue("Access must be rejected when returned ownerUid differs from authenticated user", result2.isFailure)
    assertTrue("Exception must be SecurityException", result2.exceptionOrNull() is SecurityException)
    assertTrue(
      result2.exceptionOrNull()?.message?.contains("Workspace ownership conflict") == true
    )
  }

  @Test
  fun phase2_bootstrapAuthenticatedMember_bootstrapCallableUnavailable_accessFailsClosed() = runBlocking {
    val repo = AuditRepository(db = null)

    val result = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid_001",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "System Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )

    assertTrue("Bootstrap without functioning backend must fail closed", result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue("Exception must be SecurityException", ex is SecurityException)
    assertTrue(
      "Error must explain Cloudflare Worker backend is unavailable",
      ex?.message?.contains("Unable to connect to Cloudflare Worker backend") == true ||
        ex?.message?.contains("Cloudflare Worker") == true
    )
    assertFalse(
      "Obsolete Cloud Function error message must never be emitted",
      ex?.message?.contains("trusted Cloud Function") == true
    )
  }

  @Test
  fun phase2_bootstrapAuthenticatedMember_invalidOrMismatchedBootstrapResponse_accessIsRejected() = runBlocking {
    // Case 1: Status is not "OK"
    val mockErrorStatus = object : FirebaseFunctionsService() {
      override suspend fun bootstrapInitialOwner(orgId: String): Result<OwnerBootstrapResult> {
        return Result.success(OwnerBootstrapResult("ERROR", "owner_uid_001", orgId))
      }
    }
    val repo1 = AuditRepository(db = null, functionsService = mockErrorStatus)
    val res1 = repo1.bootstrapAuthenticatedMember(
      uid = "owner_uid_001",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )
    assertTrue("Status other than OK must be rejected", res1.isFailure)
    assertTrue(res1.exceptionOrNull() is SecurityException)
    assertTrue(res1.exceptionOrNull()?.message?.contains("Unexpected response status 'ERROR'") == true)

    // Case 2: OrgId mismatch
    val mockMismatchedOrg = object : FirebaseFunctionsService() {
      override suspend fun bootstrapInitialOwner(orgId: String): Result<OwnerBootstrapResult> {
        return Result.success(OwnerBootstrapResult("OK", "owner_uid_001", "unexpected_foreign_org"))
      }
    }
    val repo2 = AuditRepository(db = null, functionsService = mockMismatchedOrg)
    val res2 = repo2.bootstrapAuthenticatedMember(
      uid = "owner_uid_001",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )
    assertTrue("Mismatched orgId must be rejected", res2.isFailure)
    assertTrue(res2.exceptionOrNull() is SecurityException)
    assertTrue(res2.exceptionOrNull()?.message?.contains("Workspace configuration mismatch") == true)
  }

  @Test
  fun phase3_cloudFunctionsDataModels_constructAndSerializeCorrectly() {
    val ownerResult = OwnerBootstrapResult(
      status = "OK",
      ownerUid = "owner_uid_123",
      orgId = "walton_org_main"
    )
    assertEquals("OK", ownerResult.status)
    assertEquals("owner_uid_123", ownerResult.ownerUid)

    val roleResult = RoleManagementResult(
      status = "OK",
      targetUid = "user_456",
      role = UserRole.REVIEWER,
      accountStatus = "Active"
    )
    assertEquals(UserRole.REVIEWER, roleResult.role)

    val clientDelResult = ClientDeletionResult(
      status = "COMPLETED",
      targetClientId = "client_abc",
      deletedCounts = mapOf("counting_records" to 42, "far_assets" to 100)
    )
    assertEquals(42, clientDelResult.deletedCounts["counting_records"])

    val userDelResult = UserDeletionResult(
      status = "COMPLETED",
      targetUid = "user_789",
      pseudonymLabel = "Deleted User [#A1B2]"
    )
    assertEquals("Deleted User [#A1B2]", userDelResult.pseudonymLabel)
  }

  @Test
  fun phase4_googleDriveService_tokenHandoffAndTestConnection() = runBlocking {
    val service = GoogleDriveService()
    assertFalse("Initially not configured", service.isConfigured)

    // Verify token handoff via setCredentials
    service.setCredentials("mock_access_token_xyz", "admin@domain.com")
    assertTrue("Configured after setting token", service.isConfigured)
    assertEquals("mock_access_token_xyz", service.accessToken)
    assertEquals("admin@domain.com", service.connectedAccountEmail)

    // Clear credentials
    service.clearCredentials()
    assertFalse("Not configured after clearCredentials", service.isConfigured)
    assertEquals("", service.accessToken)
    assertEquals("", service.connectedAccountEmail)

    // testDriveConnection should fail when unconfigured
    val testRes = service.testDriveConnection()
    assertTrue("Test must fail when unconfigured", testRes.isFailure)
  }

  @Test
  fun phase2_4_bootstrapAuthenticatedMember_workerApiClient_bootstrapsOwnerDirectly() = runBlocking {
    var workerBootstrapCalled = false
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun bootstrapInitialOwner(
        fullName: String?,
        nickname: String?,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.SystemBootstrapResultDto> {
        workerBootstrapCalled = true
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.SystemBootstrapResultDto(
            status = "OK",
            role = "Owner",
            uid = "owner_uid_worker",
            email = AuditRepository.DESIGNATED_OWNER_EMAIL,
            message = "Bootstrapped"
          )
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid_worker",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Cloudflare Worker Owner",
      preferredNickname = "worker_owner",
      requestedRole = UserRole.ADMIN
    )

    assertTrue("Worker bootstrap must succeed", result.isSuccess)
    assertTrue("Worker bootstrap API must be called", workerBootstrapCalled)
    val profile = result.getOrThrow()
    assertEquals(UserRole.OWNER, profile.role)
    assertTrue(profile.hasReviewAuthority)
    assertTrue(profile.canPublish)
  }

  @Test
  fun phase2_4_bootstrapAuthenticatedMember_workerApiClient_conflict409_throwsSecurityException() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun bootstrapInitialOwner(
        fullName: String?,
        nickname: String?,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.SystemBootstrapResultDto> {
        return com.example.data.network.WorkerApiResult.Error(
          message = "System bootstrap has already been completed by the designated System Owner.",
          statusCode = 409
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "intruder_uid",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Intruder",
      preferredNickname = "intruder",
      requestedRole = UserRole.ADMIN
    )

    assertTrue(result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue(ex is SecurityException)
    assertTrue(ex?.message?.contains("Another user account has already been registered") == true)
  }

  @Test
  fun phase2_4_bootstrapAuthenticatedMember_workerApiClient_unauthorized403_throwsSecurityException() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun bootstrapInitialOwner(
        fullName: String?,
        nickname: String?,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.SystemBootstrapResultDto> {
        return com.example.data.network.WorkerApiResult.Error(
          message = "AUTH-02: Role Mismatch: Account is not authorized to bootstrap as System Owner.",
          statusCode = 403
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "unauthorized_uid",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Unauthorized User",
      preferredNickname = "unauthorized",
      requestedRole = UserRole.ADMIN
    )

    assertTrue(result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue(ex is SecurityException)
    assertTrue(ex?.message?.contains("AUTH-02") == true)
  }

  @Test
  fun phase2_4_bootstrapAuthenticatedMember_workerBootstrapConfigMissing500_surfacesWorkerConfigError() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun bootstrapInitialOwner(
        fullName: String?,
        nickname: String?,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.SystemBootstrapResultDto> {
        return com.example.data.network.WorkerApiResult.Error(
          message = "System bootstrap failed (HTTP 500): {\"error\":\"BOOTSTRAP_CONFIGURATION_MISSING\",\"message\":\"System Owner bootstrap configuration (INITIAL_OWNER_UID) is not set on the server.\"}",
          statusCode = 500
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )

    assertTrue(result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue(ex is SecurityException)
    assertTrue("Must surface server config missing", ex?.message?.contains("Backend bootstrap configuration missing on server (INITIAL_OWNER_UID)") == true)
    assertFalse("Must not surface obsolete Cloud Function error", ex?.message?.contains("trusted Cloud Function") == true)
  }

  @Test
  fun phase2_4_bootstrapAuthenticatedMember_workerAuthTokenInvalid401_surfacesAuthTokenError() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun bootstrapInitialOwner(
        fullName: String?,
        nickname: String?,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.SystemBootstrapResultDto> {
        return com.example.data.network.WorkerApiResult.Error(
          message = "System bootstrap failed (HTTP 401): {\"error\":\"AUTH_TOKEN_INVALID\",\"message\":\"Firebase ID token verification failed\"}",
          statusCode = 401
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )

    assertTrue(result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue(ex is SecurityException)
    assertTrue("Must surface auth token invalid", ex?.message?.contains("Authentication token invalid or expired") == true)
    assertFalse("Must not surface obsolete Cloud Function error", ex?.message?.contains("trusted Cloud Function") == true)
  }

  @Test
  fun phase2_4_bootstrapAuthenticatedMember_workerServiceUnavailable503_surfacesWorkerUnavailable() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun bootstrapInitialOwner(
        fullName: String?,
        nickname: String?,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.SystemBootstrapResultDto> {
        return com.example.data.network.WorkerApiResult.Error(
          message = "Network error during system bootstrap: Failed to connect to production worker",
          statusCode = 503
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )

    assertTrue(result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue(ex is SecurityException)
    assertTrue("Must surface Worker connection failure", ex?.message?.contains("Unable to connect to Cloudflare Worker backend") == true)
    assertFalse("Must not surface obsolete Cloud Function error", ex?.message?.contains("trusted Cloud Function") == true)
  }

  @Test
  fun phase2_4_5_successfulWorkerBootstrap_followedByWorkerSessionResolution() = runBlocking {
    var sessionCallCount = 0
    var bootstrapCallCount = 0

    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun getUserSession(
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.UserSessionResultDto> {
        sessionCallCount++
        return if (sessionCallCount == 1) {
          // First check before bootstrap: unprovisioned
          com.example.data.network.WorkerApiResult.Success(
            com.example.data.network.UserSessionResultDto(
              status = "UNPROVISIONED",
              user = null,
              memberships = emptyList(),
              message = "User account is not provisioned"
            )
          )
        } else {
          // Post-bootstrap check: provisioned as Owner
          com.example.data.network.WorkerApiResult.Success(
            com.example.data.network.UserSessionResultDto(
              status = "OK",
              user = com.example.data.network.UserSessionProfileDto(
                uid = "owner_uid_first",
                email = AuditRepository.DESIGNATED_OWNER_EMAIL,
                displayName = "System Owner",
                fullName = "System Owner",
                nickname = "owner",
                globalRole = "Owner",
                accountStatus = "Active"
              ),
              memberships = emptyList(),
              message = "Session resolved"
            )
          )
        }
      }

      override suspend fun bootstrapInitialOwner(
        fullName: String?,
        nickname: String?,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.SystemBootstrapResultDto> {
        bootstrapCallCount++
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.SystemBootstrapResultDto(
            status = "OK",
            role = "Owner",
            uid = "owner_uid_first",
            email = AuditRepository.DESIGNATED_OWNER_EMAIL,
            message = "Bootstrapped"
          )
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid_first",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "System Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )

    assertTrue("Worker bootstrap followed by session resolution must succeed", result.isSuccess)
    assertEquals("POST /api/v1/system/bootstrap must be called exactly once", 1, bootstrapCallCount)
    assertEquals("GET /api/v1/system/session must be called twice (initial + post-bootstrap)", 2, sessionCallCount)
    val profile = result.getOrThrow()
    assertEquals(UserRole.OWNER, profile.role)
    assertTrue("Owner has review authority", profile.hasReviewAuthority)
    assertTrue("Owner has publish authority", profile.canPublish)
  }

  @Test
  fun phase2_4_5_existingSystem_normalSessionResolution_skipsBootstrap() = runBlocking {
    var sessionCallCount = 0
    var bootstrapCallCount = 0

    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun getUserSession(
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.UserSessionResultDto> {
        sessionCallCount++
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.UserSessionResultDto(
            status = "OK",
            user = com.example.data.network.UserSessionProfileDto(
              uid = "owner_uid_existing",
              email = AuditRepository.DESIGNATED_OWNER_EMAIL,
              displayName = "Existing Owner",
              fullName = "Existing Owner",
              nickname = "owner",
              globalRole = "Owner",
              accountStatus = "Active"
            ),
            memberships = emptyList(),
            message = "Session resolved"
          )
        )
      }

      override suspend fun bootstrapInitialOwner(
        fullName: String?,
        nickname: String?,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.SystemBootstrapResultDto> {
        bootstrapCallCount++
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.SystemBootstrapResultDto(
            status = "OK",
            role = "Owner",
            uid = "owner_uid_existing",
            email = AuditRepository.DESIGNATED_OWNER_EMAIL,
            message = "Bootstrapped"
          )
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "owner_uid_existing",
      email = AuditRepository.DESIGNATED_OWNER_EMAIL,
      displayName = "Existing Owner",
      preferredNickname = "owner",
      requestedRole = UserRole.ADMIN
    )

    assertTrue("Existing Owner login must succeed", result.isSuccess)
    assertEquals("GET /api/v1/system/session must be called once", 1, sessionCallCount)
    assertEquals("POST /api/v1/system/bootstrap must NOT be called for already initialized system", 0, bootstrapCallCount)
    val profile = result.getOrThrow()
    assertEquals(UserRole.OWNER, profile.role)
  }

  @Test
  fun phase2_4_5_workerSession401_surfacesAuthTokenError() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun getUserSession(
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.UserSessionResultDto> {
        return com.example.data.network.WorkerApiResult.Error(
          message = "Failed to resolve user session (HTTP 401): {\"error\":\"AUTH_TOKEN_INVALID\"}",
          statusCode = 401
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "user_uid",
      email = "auditor@assetflow.local",
      displayName = "Auditor",
      preferredNickname = "auditor",
      requestedRole = UserRole.AUDITOR
    )

    assertTrue(result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue(ex is SecurityException)
    assertTrue("Must explain auth token expired/invalid", ex?.message?.contains("Authentication token invalid or expired") == true)
  }

  @Test
  fun phase2_4_5_workerSession403_surfacesAuth02Error() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun getUserSession(
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.UserSessionResultDto> {
        return com.example.data.network.WorkerApiResult.Error(
          message = "Failed to resolve user session (HTTP 403): {\"error\":\"FORBIDDEN\"}",
          statusCode = 403
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "user_uid",
      email = "auditor@assetflow.local",
      displayName = "Auditor",
      preferredNickname = "auditor",
      requestedRole = UserRole.AUDITOR
    )

    assertTrue(result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue(ex is SecurityException)
    assertTrue("Must explain AUTH-02 workspace mismatch", ex?.message?.contains("AUTH-02") == true)
  }

  @Test
  fun phase2_4_5_ordinaryAuditorLogin_succeedsForAuditorPortal() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun getUserSession(
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.UserSessionResultDto> {
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.UserSessionResultDto(
            status = "OK",
            user = com.example.data.network.UserSessionProfileDto(
              uid = "auditor_uid_01",
              email = "auditor@assetflow.local",
              displayName = "Field Auditor",
              fullName = "Field Auditor",
              nickname = "auditor",
              globalRole = "Auditor",
              accountStatus = "Active"
            ),
            memberships = emptyList(),
            message = "Session resolved"
          )
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "auditor_uid_01",
      email = "auditor@assetflow.local",
      displayName = "Field Auditor",
      preferredNickname = "auditor",
      requestedRole = UserRole.AUDITOR
    )

    assertTrue("Auditor login must succeed for Auditor portal", result.isSuccess)
    val profile = result.getOrThrow()
    assertEquals("Auditor", profile.role)
    assertFalse("Auditor does not have review authority", profile.hasReviewAuthority)
    assertFalse("Auditor does not have publish authority", profile.canPublish)
  }

  @Test
  fun phase2_4_5_ordinaryAuditorLogin_rejectedForAdminPortal() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun getUserSession(
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.UserSessionResultDto> {
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.UserSessionResultDto(
            status = "OK",
            user = com.example.data.network.UserSessionProfileDto(
              uid = "auditor_uid_01",
              email = "auditor@assetflow.local",
              displayName = "Field Auditor",
              fullName = "Field Auditor",
              nickname = "auditor",
              globalRole = "Auditor",
              accountStatus = "Active"
            ),
            memberships = emptyList(),
            message = "Session resolved"
          )
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.bootstrapAuthenticatedMember(
      uid = "auditor_uid_01",
      email = "auditor@assetflow.local",
      displayName = "Field Auditor",
      preferredNickname = "auditor",
      requestedRole = UserRole.ADMIN
    )

    assertTrue("Auditor requesting Admin portal must fail", result.isFailure)
    val ex = result.exceptionOrNull()
    assertTrue(ex is SecurityException)
    assertTrue("Must explain AUTH-02 role mismatch", ex?.message?.contains("AUTH-02: Role Mismatch: Account is not provisioned as an Administrator") == true)
  }

  @Test
  fun phase1_workerApiClient_createClient_preservesAllFieldsAndUpdatesCache() = runBlocking {
    var capturedRequest: com.example.data.network.CreateClientRequestDto? = null
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun createClient(
        request: com.example.data.network.CreateClientRequestDto,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.CreateClientResponseDto> {
        capturedRequest = request
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.CreateClientResponseDto(
            message = "Client created successfully",
            clientId = request.clientId ?: "client_acme_123",
            client = com.example.data.network.ClientItemDto(
              clientId = request.clientId ?: "client_acme_123",
              clientName = request.clientName,
              clientCode = request.clientCode,
              industry = request.industry ?: "",
              contactPerson = request.contactPerson ?: "",
              contactEmail = request.contactEmail ?: "",
              status = "Active",
              activeFiscalYearId = request.fiscalYear ?: "2026_27",
              uniqueAssetKeyHeader = request.uniqueAssetKeyHeader ?: "Asset ID",
              driveRootFolderId = request.driveRootFolderId ?: "",
              createdByUid = "owner_uid_999"
            ),
            fiscalYear = com.example.data.network.FiscalYearItemDto(
              clientId = request.clientId ?: "client_acme_123",
              fiscalYearId = request.fiscalYear ?: "2026_27",
              label = request.fiscalYearLabel ?: "2026–27",
              isClosed = false
            )
          )
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.createOrUpdateClient(
      clientName = "Acme Holdings",
      clientCode = "ACME",
      industry = "Manufacturing",
      contactPerson = "Jane Smith",
      contactEmail = "jane@acme.com",
      initialFiscalYearLabel = "2026–27",
      uniqueAssetKeyHeader = "Asset Serial No",
      provisionDriveFolders = false
    )

    assertTrue("Client creation must succeed", result.isSuccess)
    val client = result.getOrThrow()
    assertEquals("Acme Holdings", client.clientName)
    assertEquals("ACME", client.clientCode)
    assertEquals("Manufacturing", client.industry)
    assertEquals("Jane Smith", client.contactPerson)
    assertEquals("jane@acme.com", client.contactEmail)
    assertEquals("Asset Serial No", client.uniqueAssetKeyHeader)

    // Verify all fields forwarded to Worker request
    assertEquals("Acme Holdings", capturedRequest?.clientName)
    assertEquals("ACME", capturedRequest?.clientCode)
    assertEquals("Manufacturing", capturedRequest?.industry)
    assertEquals("Jane Smith", capturedRequest?.contactPerson)
    assertEquals("jane@acme.com", capturedRequest?.contactEmail)
    assertEquals("Asset Serial No", capturedRequest?.uniqueAssetKeyHeader)

    // Verify local reactive cache updated
    assertEquals(1, repo.cachedClients.value.size)
    assertEquals("Acme Holdings", repo.cachedClients.value[0].clientName)
    assertEquals(1, repo.cachedFiscalYears.value.size)
    assertEquals("2026–27", repo.cachedFiscalYears.value[0].label)
  }

  @Test
  fun phase1_createClient_recoverableDriveFailure_doesNotBlockClientCreation() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun createClient(
        request: com.example.data.network.CreateClientRequestDto,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.CreateClientResponseDto> {
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.CreateClientResponseDto(
            message = "Client created successfully",
            clientId = "client_drive_test",
            client = com.example.data.network.ClientItemDto(
              clientId = "client_drive_test",
              clientName = request.clientName,
              clientCode = request.clientCode,
              industry = "Logistics",
              contactPerson = "John Doe",
              contactEmail = "john@drive.test",
              status = "Active",
              activeFiscalYearId = "2026_27",
              uniqueAssetKeyHeader = "Tag ID",
              driveRootFolderId = "",
              createdByUid = "owner_uid_999"
            ),
            fiscalYear = null
          )
        )
      }
    }

    // Drive service not configured / failing
    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.createOrUpdateClient(
      clientName = "Drive Test Corp",
      clientCode = "DTC",
      industry = "Logistics",
      contactPerson = "John Doe",
      contactEmail = "john@drive.test",
      provisionDriveFolders = true // requested but drive service is not configured
    )

    assertTrue("Client creation must succeed even if Drive is not configured", result.isSuccess)
    val client = result.getOrThrow()
    assertEquals("Drive Test Corp", client.clientName)
    assertEquals("client_drive_test", client.clientId)
  }

  @Test
  fun phase1_addFiscalYearForClient_updatesCachedFiscalYears() = runBlocking {
    val mockWorkerClient = object : com.example.data.network.WorkerApiClient() {
      override suspend fun createFiscalYear(
        clientId: String,
        fiscalYearId: String,
        label: String,
        bearerTokenOverride: String?
      ): com.example.data.network.WorkerApiResult<com.example.data.network.FiscalYearItemDto> {
        return com.example.data.network.WorkerApiResult.Success(
          com.example.data.network.FiscalYearItemDto(
            clientId = clientId,
            fiscalYearId = fiscalYearId,
            label = label,
            isClosed = false
          )
        )
      }
    }

    val repo = AuditRepository(db = null, workerApiClient = mockWorkerClient)
    val result = repo.addFiscalYearForClient(
      clientId = "client_apex",
      yearLabel = "2027–28"
    )

    assertTrue("Fiscal year creation must succeed", result.isSuccess)
    val fy = result.getOrThrow()
    assertEquals("client_apex", fy.clientId)
    assertEquals("2027–28", fy.label)
    assertEquals(1, repo.cachedFiscalYears.value.size)
    assertEquals("2027–28", repo.cachedFiscalYears.value[0].label)
  }
}
