package com.example.data.remote

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.functions
import kotlinx.coroutines.tasks.await

data class OwnerBootstrapResult(
  val status: String,
  val ownerUid: String,
  val orgId: String
)

data class RoleManagementResult(
  val status: String,
  val targetUid: String,
  val role: String,
  val accountStatus: String
)

data class ClientDeletionResult(
  val status: String,
  val targetClientId: String,
  val deletedCounts: Map<String, Int>
)

data class UserDeletionResult(
  val status: String,
  val targetUid: String,
  val pseudonymLabel: String
)

/**
 * Type-safe interface to the AssetFlow backend Cloud Functions (functions/src/index.ts).
 * Enforces server-side authorization checks for owner bootstrapping, role governance,
 * and permanent client/user deletions.
 */
open class FirebaseFunctionsService(
  private val functions: FirebaseFunctions? = try {
    Firebase.functions
  } catch (e: Exception) {
    runCatching { Log.w(TAG, "Firebase Functions unavailable: ${e.message}") }
    null
  }
) {
  companion object {
    private const val TAG = "FirebaseFunctionsService"
  }

  open suspend fun bootstrapInitialOwner(orgId: String): Result<OwnerBootstrapResult> = runCatching {
    val fn = functions ?: throw IllegalStateException("Firebase Functions SDK is not initialized")
    val data = mapOf(
      "orgId" to orgId,
      "targetOrgId" to orgId
    )
    val task = fn.getHttpsCallable("bootstrapInitialOwner").call(data).await()
    @Suppress("UNCHECKED_CAST")
    val map = task.getData() as? Map<String, Any?> ?: emptyMap()
    OwnerBootstrapResult(
      status = map["status"]?.toString() ?: "OK",
      ownerUid = map["ownerUid"]?.toString().orEmpty(),
      orgId = map["orgId"]?.toString() ?: orgId
    )
  }

  open suspend fun manageUserRoleAndPermissions(
    orgId: String,
    targetUid: String,
    targetEmail: String,
    targetDisplayName: String,
    newRole: String,
    accountStatus: String = "Active",
    hasReviewAuthority: Boolean = false,
    canPublish: Boolean = false,
    canSyncDrive: Boolean = false
  ): Result<RoleManagementResult> = runCatching {
    val fn = functions ?: throw IllegalStateException("Firebase Functions SDK is not initialized")
    val data = mapOf(
      "orgId" to orgId,
      "targetUid" to targetUid,
      "targetEmail" to targetEmail,
      "targetDisplayName" to targetDisplayName,
      "newRole" to newRole,
      "accountStatus" to accountStatus,
      "hasReviewAuthority" to hasReviewAuthority,
      "canPublish" to canPublish,
      "canSyncDrive" to canSyncDrive
    )
    val task = fn.getHttpsCallable("manageUserRoleAndPermissions").call(data).await()
    @Suppress("UNCHECKED_CAST")
    val map = task.getData() as? Map<String, Any?> ?: emptyMap()
    RoleManagementResult(
      status = map["status"]?.toString() ?: "OK",
      targetUid = map["targetUid"]?.toString() ?: targetUid,
      role = map["role"]?.toString() ?: newRole,
      accountStatus = map["accountStatus"]?.toString() ?: accountStatus
    )
  }

  open suspend fun executeClientDeletionJob(
    orgId: String,
    targetClientId: String,
    confirmationText: String,
    deleteDriveArtifactsOptIn: Boolean = false
  ): Result<ClientDeletionResult> = runCatching {
    val fn = functions ?: throw IllegalStateException("Firebase Functions SDK is not initialized")
    val data = mapOf(
      "orgId" to orgId,
      "targetClientId" to targetClientId,
      "confirmationText" to confirmationText,
      "deleteDriveArtifactsOptIn" to deleteDriveArtifactsOptIn
    )
    val task = fn.getHttpsCallable("executeClientDeletionJob").call(data).await()
    @Suppress("UNCHECKED_CAST")
    val map = task.getData() as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val countsMap = (map["deletedCounts"] as? Map<String, Number>)?.mapValues { it.value.toInt() } ?: emptyMap()
    ClientDeletionResult(
      status = map["status"]?.toString() ?: "COMPLETED",
      targetClientId = map["targetClientId"]?.toString() ?: targetClientId,
      deletedCounts = countsMap
    )
  }

  open suspend fun executeUserDeletionJob(
    orgId: String,
    targetUid: String
  ): Result<UserDeletionResult> = runCatching {
    val fn = functions ?: throw IllegalStateException("Firebase Functions SDK is not initialized")
    val data = mapOf(
      "orgId" to orgId,
      "targetUid" to targetUid
    )
    val task = fn.getHttpsCallable("executeUserDeletionJob").call(data).await()
    @Suppress("UNCHECKED_CAST")
    val map = task.getData() as? Map<String, Any?> ?: emptyMap()
    UserDeletionResult(
      status = map["status"]?.toString() ?: "COMPLETED",
      targetUid = map["targetUid"]?.toString() ?: targetUid,
      pseudonymLabel = map["pseudonymLabel"]?.toString().orEmpty()
    )
  }
}
