package com.example.data.network

import org.json.JSONObject

data class PhotoEvidenceMetadataDto(
    val photoId: String,
    val cycleId: String,
    val assetKey: String,
    val assetId: String,
    val auditRecordId: String? = null,
    val photoCategory: String? = "GENERAL",
    val caption: String? = null,
    val mimeType: String = "image/jpeg",
    val fileExtension: String = "jpg",
    val fileSize: Long = 0L,
    val sha256: String? = null,
    val driveFileId: String,
    val driveWebViewLink: String? = null,
    val driveRootFolderId: String,
    val driveDateFolderId: String? = null,
    val localFilename: String? = null,
    val capturedAt: String? = null,
    val uploadedAt: String? = null
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("photoId", photoId)
            put("cycleId", cycleId)
            put("assetKey", assetKey)
            put("assetId", assetId)
            auditRecordId?.let { put("auditRecordId", it) }
            photoCategory?.let { put("photoCategory", it) }
            caption?.let { put("caption", it) }
            put("mimeType", mimeType)
            put("fileExtension", fileExtension)
            put("fileSize", fileSize)
            sha256?.let { put("sha256", it) }
            put("driveFileId", driveFileId)
            driveWebViewLink?.let { put("driveWebViewLink", it) }
            put("driveRootFolderId", driveRootFolderId)
            driveDateFolderId?.let { put("driveDateFolderId", it) }
            localFilename?.let { put("localFilename", it) }
            capturedAt?.let { put("capturedAt", it) }
            uploadedAt?.let { put("uploadedAt", it) }
        }
    }
}

data class PhotoEvidenceResultDto(
    val message: String,
    val isIdempotentReplay: Boolean,
    val photoId: String
) {
    companion object {
        fun fromJson(json: JSONObject): PhotoEvidenceResultDto {
            val photoObj = json.optJSONObject("photo")
            val pId = photoObj?.optString("photoId").orEmpty().ifBlank {
                json.optString("photoId")
            }
            return PhotoEvidenceResultDto(
                message = json.optString("message", "Success"),
                isIdempotentReplay = json.optBoolean("isIdempotentReplay", false),
                photoId = pId
            )
        }
    }
}
