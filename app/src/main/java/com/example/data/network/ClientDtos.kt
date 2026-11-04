package com.example.data.network

import org.json.JSONArray
import org.json.JSONObject

data class CreateClientRequestDto(
    val clientId: String? = null,
    val clientName: String,
    val clientCode: String,
    val fiscalYear: String? = null,
    val fiscalYearLabel: String? = null,
    val industry: String? = null,
    val uniqueAssetKeyHeader: String? = null,
    val contactPerson: String? = null,
    val contactEmail: String? = null,
    val driveRootFolderId: String? = null,
    val provisionDriveFolders: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            if (!clientId.isNullOrBlank()) put("clientId", clientId)
            put("clientName", clientName)
            put("clientCode", clientCode)
            if (!fiscalYear.isNullOrBlank()) put("fiscalYear", fiscalYear)
            if (!fiscalYearLabel.isNullOrBlank()) put("fiscalYearLabel", fiscalYearLabel)
            if (!industry.isNullOrBlank()) put("industry", industry)
            if (!uniqueAssetKeyHeader.isNullOrBlank()) put("uniqueAssetKeyHeader", uniqueAssetKeyHeader)
            if (!contactPerson.isNullOrBlank()) put("contactPerson", contactPerson)
            if (!contactEmail.isNullOrBlank()) put("contactEmail", contactEmail)
            if (!driveRootFolderId.isNullOrBlank()) put("driveRootFolderId", driveRootFolderId)
            put("provisionDriveFolders", provisionDriveFolders)
        }
    }
}

data class ClientItemDto(
    val clientId: String,
    val clientName: String,
    val clientCode: String,
    val industry: String,
    val contactPerson: String,
    val contactEmail: String,
    val status: String,
    val setupStatus: String = "COMPLETED",
    val activeFiscalYearId: String,
    val uniqueAssetKeyHeader: String,
    val driveRootFolderId: String,
    val driveProvisioningStatus: String = "NOT_REQUESTED",
    val driveProvisioningError: String? = null,
    val createdByUid: String
) {
    companion object {
        fun fromJson(json: JSONObject): ClientItemDto {
            return ClientItemDto(
                clientId = json.optString("client_id", json.optString("clientId", "")),
                clientName = json.optString("client_name", json.optString("clientName", "")),
                clientCode = json.optString("client_code", json.optString("clientCode", "")),
                industry = json.optString("industry", ""),
                contactPerson = json.optString("contact_person", json.optString("contactPerson", "")),
                contactEmail = json.optString("contact_email", json.optString("contactEmail", "")),
                status = json.optString("status", "Active"),
                setupStatus = json.optString("setup_status", json.optString("setupStatus", "COMPLETED")),
                activeFiscalYearId = json.optString("active_fiscal_year_id", json.optString("activeFiscalYearId", "2026_27")),
                uniqueAssetKeyHeader = json.optString("unique_asset_key_header", json.optString("uniqueAssetKeyHeader", "Asset ID")),
                driveRootFolderId = json.optString("drive_root_folder_id", json.optString("driveRootFolderId", "")),
                driveProvisioningStatus = json.optString("drive_provisioning_status", json.optString("driveProvisioningStatus", "NOT_REQUESTED")),
                driveProvisioningError = json.optString("drive_provisioning_error", json.optString("driveProvisioningError", "")).ifBlank { null },
                createdByUid = json.optString("created_by_uid", json.optString("createdByUid", ""))
            )
        }
    }
}

data class CreateClientResponseDto(
    val message: String,
    val clientId: String,
    val client: ClientItemDto?,
    val fiscalYear: FiscalYearItemDto?
) {
    companion object {
        fun fromJson(json: JSONObject): CreateClientResponseDto {
            val clientObj = json.optJSONObject("client")
            val fyObj = json.optJSONObject("fiscalYear")
            return CreateClientResponseDto(
                message = json.optString("message", ""),
                clientId = json.optString("clientId", ""),
                client = clientObj?.let { ClientItemDto.fromJson(it) },
                fiscalYear = fyObj?.let { FiscalYearItemDto.fromJson(it) }
            )
        }
    }
}

data class ListClientsResponseDto(
    val clients: List<ClientItemDto>
) {
    companion object {
        fun fromJson(json: JSONObject): ListClientsResponseDto {
            val arr = json.optJSONArray("clients") ?: JSONArray()
            val list = mutableListOf<ClientItemDto>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(ClientItemDto.fromJson(obj))
            }
            return ListClientsResponseDto(list)
        }
    }
}

data class FiscalYearItemDto(
    val clientId: String,
    val fiscalYearId: String,
    val label: String,
    val isClosed: Boolean,
    val activeSchemaVersionId: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): FiscalYearItemDto {
            return FiscalYearItemDto(
                clientId = json.optString("client_id", json.optString("clientId", "")),
                fiscalYearId = json.optString("fiscal_year_id", json.optString("fiscalYearId", "")),
                label = json.optString("label", ""),
                isClosed = json.optBoolean("is_closed", json.optBoolean("isClosed", false)),
                activeSchemaVersionId = json.optString("active_schema_version_id", json.optString("activeSchemaVersionId", "")).ifBlank { null }
            )
        }
    }
}

data class ListFiscalYearsResponseDto(
    val fiscalYears: List<FiscalYearItemDto>
) {
    companion object {
        fun fromJson(json: JSONObject): ListFiscalYearsResponseDto {
            val arr = json.optJSONArray("fiscalYears") ?: JSONArray()
            val list = mutableListOf<FiscalYearItemDto>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(FiscalYearItemDto.fromJson(obj))
            }
            return ListFiscalYearsResponseDto(list)
        }
    }
}

data class UserItemDto(
    val uid: String,
    val email: String,
    val displayName: String,
    val fullName: String,
    val nickname: String,
    val globalRole: String,
    val accountStatus: String,
    val createdAt: String,
    val updatedAt: String
) {
    companion object {
        fun fromJson(json: JSONObject): UserItemDto {
            return UserItemDto(
                uid = json.optString("uid", ""),
                email = json.optString("email", ""),
                displayName = json.optString("display_name", ""),
                fullName = json.optString("full_name", ""),
                nickname = json.optString("nickname", ""),
                globalRole = json.optString("global_role", "Auditor"),
                accountStatus = json.optString("account_status", "Active"),
                createdAt = json.optString("created_at", ""),
                updatedAt = json.optString("updated_at", "")
            )
        }
    }
}

data class ClientMembershipItemDto(
    val membershipId: String,
    val clientId: String,
    val userUid: String,
    val userEmail: String,
    val userName: String,
    val role: String,
    val accountStatus: String,
    val hasReviewAuthority: Boolean,
    val canPublish: Boolean,
    val canSyncDrive: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): ClientMembershipItemDto {
            return ClientMembershipItemDto(
                membershipId = json.optString("membership_id", ""),
                clientId = json.optString("client_id", ""),
                userUid = json.optString("user_uid", ""),
                userEmail = json.optString("user_email", ""),
                userName = json.optString("user_name", ""),
                role = json.optString("role", "Auditor"),
                accountStatus = json.optString("account_status", "Active"),
                hasReviewAuthority = json.optInt("has_review_authority", 0) == 1,
                canPublish = json.optInt("can_publish", 0) == 1,
                canSyncDrive = json.optInt("can_sync_drive", 0) == 1
            )
        }
    }
}

data class AssignUserRequestDto(
    val userUid: String,
    val role: String = "Auditor",
    val hasReviewAuthority: Boolean = false,
    val canPublish: Boolean = false,
    val canSyncDrive: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("userUid", userUid)
            put("role", role)
            put("hasReviewAuthority", hasReviewAuthority)
            put("canPublish", canPublish)
            put("canSyncDrive", canSyncDrive)
        }
    }
}

data class AccessRequestItemDto(
    val requestId: String,
    val clientId: String,
    val fiscalYearId: String,
    val requesterUid: String,
    val requesterEmail: String,
    val reason: String,
    val status: String,
    val requestedAt: String,
    val decidedAt: String? = null,
    val decidedByUid: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): AccessRequestItemDto {
            return AccessRequestItemDto(
                requestId = json.optString("request_id", ""),
                clientId = json.optString("client_id", ""),
                fiscalYearId = json.optString("fiscal_year_id", ""),
                requesterUid = json.optString("requester_uid", ""),
                requesterEmail = json.optString("requester_email", ""),
                reason = json.optString("reason", ""),
                status = json.optString("status", "Pending"),
                requestedAt = json.optString("requested_at", ""),
                decidedAt = json.optString("decided_at", "").ifBlank { null },
                decidedByUid = json.optString("decided_by_uid", "").ifBlank { null }
            )
        }
    }
}

data class FarAssetItemDto(
    val assetKey: String,
    val assetId: String,
    val sourceRow: Long = 0L,
    val sourceWorksheet: String = "Count Sheet",
    val sourceWorksheetIndex: Long = 0L,
    val importBatchId: String = "",
    val rawRowJson: String = "{}",
    val searchText: String = "",
    val importedByUid: String = "",
    val importedAt: String = "",
    val isActive: Boolean = true
) {
    companion object {
        fun fromJson(json: JSONObject): FarAssetItemDto {
            return FarAssetItemDto(
                assetKey = json.optString("asset_key", json.optString("assetKey", "")),
                assetId = json.optString("asset_id", json.optString("assetId", "")),
                sourceRow = json.optLong("source_row", json.optLong("sourceRow", 0L)),
                sourceWorksheet = json.optString("source_worksheet", json.optString("sourceWorksheet", "Count Sheet")),
                sourceWorksheetIndex = json.optLong("source_worksheet_index", json.optLong("sourceWorksheetIndex", 0L)),
                importBatchId = json.optString("import_batch_id", json.optString("importBatchId", "")),
                rawRowJson = json.optString("raw_row_json", json.optString("rawRowJson", "{}")),
                searchText = json.optString("search_text", json.optString("searchText", "")),
                importedByUid = json.optString("imported_by_uid", json.optString("importedByUid", "")),
                importedAt = json.optString("imported_at", json.optString("importedAt", "")),
                isActive = if (json.has("is_active")) json.optInt("is_active", 1) == 1 else json.optBoolean("isActive", true)
            )
        }
    }
}

data class FarSearchResultDto(
    val assets: List<FarAssetItemDto>,
    val nextCursor: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): FarSearchResultDto {
            val arr = json.optJSONArray("assets") ?: JSONArray()
            val list = mutableListOf<FarAssetItemDto>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(FarAssetItemDto.fromJson(obj))
            }
            val cursor = json.optString("nextCursor", "").ifBlank {
                json.optString("next_cursor", "").ifBlank { null }
            }
            return FarSearchResultDto(assets = list, nextCursor = cursor)
        }
    }
}

data class DailySheetItemDto(
    val batchId: String,
    val clientId: String,
    val fiscalYearId: String,
    val auditorUid: String,
    val auditorName: String,
    val batchDate: String,
    val batchStatus: String,
    val totalItems: Int,
    val createdAt: String,
    val updatedAt: String,
    val submissionId: String? = null,
    val submissionStatus: String? = null,
    val submittedAt: String? = null,
    val reviewedByUid: String? = null,
    val reviewedAt: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): DailySheetItemDto {
            return DailySheetItemDto(
                batchId = json.optString("batch_id", json.optString("batchId", "")),
                clientId = json.optString("client_id", json.optString("clientId", "")),
                fiscalYearId = json.optString("fiscal_year_id", json.optString("fiscalYearId", "")),
                auditorUid = json.optString("auditor_uid", json.optString("auditorUid", "")),
                auditorName = json.optString("auditor_name", json.optString("auditorName", "")),
                batchDate = json.optString("batch_date", json.optString("batchDate", "")),
                batchStatus = json.optString("batch_status", json.optString("status", "OPEN")),
                totalItems = json.optInt("total_items", json.optInt("totalItems", 0)),
                createdAt = json.optString("created_at", json.optString("createdAt", "")),
                updatedAt = json.optString("updated_at", json.optString("updatedAt", "")),
                submissionId = json.optString("submission_id", json.optString("submissionId", "")).ifBlank { null },
                submissionStatus = json.optString("submission_status", json.optString("status", "")).ifBlank { null },
                submittedAt = json.optString("submitted_at", json.optString("submittedAt", "")).ifBlank { null },
                reviewedByUid = json.optString("reviewed_by_uid", json.optString("reviewedByUid", "")).ifBlank { null },
                reviewedAt = json.optString("reviewed_at", json.optString("reviewedAt", "")).ifBlank { null }
            )
        }
    }
}

data class DailySheetRecordDto(
    val recordId: String,
    val clientId: String,
    val fiscalYearId: String,
    val assetKey: String,
    val schemaVersionId: String,
    val auditorUid: String,
    val auditorName: String,
    val recordStatus: String,
    val batchId: String?,
    val countDate: String,
    val valuesJson: String,
    val photoCount: Int = 0,
    val hasPhotoEvidence: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject): DailySheetRecordDto {
            return DailySheetRecordDto(
                recordId = json.optString("record_id", json.optString("recordId", "")),
                clientId = json.optString("client_id", json.optString("clientId", "")),
                fiscalYearId = json.optString("fiscal_year_id", json.optString("fiscalYearId", "")),
                assetKey = json.optString("asset_key", json.optString("assetKey", "")),
                schemaVersionId = json.optString("schema_version_id", json.optString("schemaVersionId", "")),
                auditorUid = json.optString("auditor_uid", json.optString("auditorUid", "")),
                auditorName = json.optString("auditor_name_snapshot", json.optString("auditorName", "")),
                recordStatus = json.optString("record_status", json.optString("status", "DRAFT")),
                batchId = json.optString("batch_id", json.optString("batchId", "")).ifBlank { null },
                countDate = json.optString("count_date", json.optString("countDate", "")),
                valuesJson = json.optString("values_json", json.optString("valuesJson", "{}")),
                photoCount = json.optInt("photo_count", 0),
                hasPhotoEvidence = json.optInt("has_photo_evidence", 0) == 1 || json.optBoolean("hasPhotoEvidence", false)
            )
        }
    }
}

data class DailySheetDetailDto(
    val batch: DailySheetItemDto,
    val records: List<DailySheetRecordDto>,
    val submission: JSONObject? = null
) {
    companion object {
        fun fromJson(json: JSONObject): DailySheetDetailDto {
            val batchObj = json.optJSONObject("batch") ?: JSONObject()
            val batch = DailySheetItemDto.fromJson(batchObj)
            val recordsArr = json.optJSONArray("records") ?: JSONArray()
            val recordList = mutableListOf<DailySheetRecordDto>()
            for (i in 0 until recordsArr.length()) {
                val rObj = recordsArr.optJSONObject(i) ?: continue
                recordList.add(DailySheetRecordDto.fromJson(rObj))
            }
            return DailySheetDetailDto(
                batch = batch,
                records = recordList,
                submission = json.optJSONObject("submission")
            )
        }
    }
}

data class MasterWorkbookStateDto(
    val stateId: String,
    val clientId: String,
    val fiscalYearId: String,
    val syncState: String,
    val lastSyncedAt: String? = null,
    val fileChecksum: String? = null,
    val masterDriveFileId: String? = null,
    val leaseOwnerUid: String? = null,
    val leaseOwnerJobId: String? = null,
    val leaseExpiresAtMs: Long = 0L,
    val appliedSubmissionsJson: String = "{}"
) {
    companion object {
        fun fromJson(json: JSONObject): MasterWorkbookStateDto {
            return MasterWorkbookStateDto(
                stateId = json.optString("state_id", ""),
                clientId = json.optString("client_id", ""),
                fiscalYearId = json.optString("fiscal_year_id", ""),
                syncState = json.optString("sync_state", "NOT_SYNCED"),
                lastSyncedAt = json.optString("last_synced_at", "").ifBlank { null },
                fileChecksum = json.optString("file_checksum", "").ifBlank { null },
                masterDriveFileId = json.optString("master_drive_file_id", "").ifBlank { null },
                leaseOwnerUid = json.optString("lease_owner_uid", "").ifBlank { null },
                leaseOwnerJobId = json.optString("lease_owner_job_id", "").ifBlank { null },
                leaseExpiresAtMs = json.optLong("lease_expires_at_ms", 0L),
                appliedSubmissionsJson = json.optString("applied_submissions_json", "{}")
            )
        }
    }
}

data class SubmitFoundNotInFarRequestDto(
    val tempId: String,
    val fiscalYearId: String,
    val description: String,
    val rawValuesJson: String,
    val countDate: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("tempId", tempId)
            put("fiscalYearId", fiscalYearId)
            put("description", description)
            put("rawValuesJson", rawValuesJson)
            put("countDate", countDate)
        }
    }
}

data class FoundNotInFarItemDto(
    val tempId: String,
    val clientId: String,
    val fiscalYearId: String,
    val description: String,
    val rawValuesJson: String,
    val auditorUid: String,
    val auditorName: String,
    val countDate: String,
    val createdAt: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): FoundNotInFarItemDto {
            return FoundNotInFarItemDto(
                tempId = json.optString("temp_id", json.optString("tempId", "")),
                clientId = json.optString("client_id", json.optString("clientId", "")),
                fiscalYearId = json.optString("fiscal_year_id", json.optString("fiscalYearId", "")),
                description = json.optString("description", ""),
                rawValuesJson = json.optString("raw_values_json", json.optString("rawValuesJson", "{}")),
                auditorUid = json.optString("auditor_uid", json.optString("auditorUid", "")),
                auditorName = json.optString("auditor_name", json.optString("auditorName", "")),
                countDate = json.optString("count_date", json.optString("countDate", "")),
                createdAt = json.optString("created_at", "").ifBlank { null }
            )
        }
    }
}
