package com.example.data.drive

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class DriveUploadException(message: String, cause: Throwable? = null) : Exception(message, cause)
class DriveAuthException(message: String, val statusCode: Int = 401) : DriveUploadException(message)
class DrivePermissionException(message: String, val statusCode: Int = 403) : DriveUploadException(message)
class DriveTransientException(message: String, val statusCode: Int, val retryAfterSeconds: Long? = null) : DriveUploadException(message)
class DriveSessionExpiredException(message: String) : DriveUploadException(message)
class DriveLocalFileException(message: String) : DriveUploadException(message)
class DriveDuplicateEvidenceException(message: String, val duplicateFileIds: List<String> = emptyList()) : DriveUploadException(message)

data class ResumableUploadParams(
    val photoId: String,
    val localFile: File,
    val standardizedFileName: String,
    val mimeType: String,
    val parentFolderId: String,
    val appProperties: Map<String, String> = emptyMap(),
    val chunkSize: Int = GoogleDriveResumableUploader.DEFAULT_CHUNK_SIZE
)

data class ResumableUploadResult(
    val fileId: String,
    val fileName: String,
    val webViewLink: String,
    val mimeType: String,
    val md5Checksum: String = "",
    val isIdempotentReplay: Boolean = false
)

/**
 * Robust Google Drive API v3 Resumable Upload engine (uploadType=resumable).
 * Streams chunked binary data directly from local storage without buffering the full image in RAM.
 * Handles 308 Resume Incomplete, status query recovery, 401 token refresh once, error classification,
 * and idempotency checks via Drive appProperties.
 */
open class GoogleDriveResumableUploader(
    private val sessionStore: ResumableSessionStore,
    private val tokenProvider: suspend () -> String?,
    private val uploadEndpointBase: String = "https://www.googleapis.com",
    private val apiEndpointBase: String = "https://www.googleapis.com"
) {

    companion object {
        const val DEFAULT_CHUNK_SIZE = 256 * 1024 // 256 KB minimum Google Drive chunk
        const val MAX_TRANSIENT_RETRIES = 3
    }

    open suspend fun uploadPhoto(params: ResumableUploadParams): ResumableUploadResult = withContext(Dispatchers.IO) {
        val file = params.localFile
        if (!file.exists() || file.length() <= 0L) {
            throw DriveLocalFileException("Local photo file '${file.absolutePath}' does not exist or is empty.")
        }

        var token = tokenProvider()
        if (token.isNullOrBlank()) {
            throw DriveAuthException("No active Google Drive OAuth access token available.")
        }

        // 1. Idempotency Check: Query Google Drive to see if this photoId was already uploaded
        val existing = findExistingDriveFileByPhotoId(params.photoId, params.parentFolderId, token)
        if (existing != null) {
            sessionStore.clearSession(params.photoId)
            return@withContext existing.copy(isIdempotentReplay = true)
        }

        val totalBytes = file.length()
        var session = sessionStore.getSession(params.photoId)
        var sessionUri = session?.sessionUri
        var resumeByteOffset: Long = 0L

        // 2. Query Status of Existing Session (if any)
        if (!sessionUri.isNullOrBlank()) {
            try {
                val statusResult = queryUploadStatus(sessionUri, totalBytes, token)
                when (statusResult) {
                    is StatusCheckResult.Complete -> {
                        sessionStore.clearSession(params.photoId)
                        return@withContext statusResult.result
                    }
                    is StatusCheckResult.ResumeFrom -> {
                        resumeByteOffset = statusResult.nextByte
                    }
                }
            } catch (e: DriveSessionExpiredException) {
                // Lost-response check: verify if the file was actually saved by Drive before starting over
                val recovered = findExistingDriveFileByPhotoId(params.photoId, params.parentFolderId, token)
                if (recovered != null) {
                    sessionStore.clearSession(params.photoId)
                    return@withContext recovered.copy(isIdempotentReplay = true)
                }
                // Session expired (404), clear and start fresh
                sessionStore.clearSession(params.photoId)
                sessionUri = null
                resumeByteOffset = 0L
            } catch (e: DriveAuthException) {
                // Refresh token and retry status check
                token = refreshTokenOrThrow()
                val statusResult = queryUploadStatus(sessionUri, totalBytes, token)
                when (statusResult) {
                    is StatusCheckResult.Complete -> {
                        sessionStore.clearSession(params.photoId)
                        return@withContext statusResult.result
                    }
                    is StatusCheckResult.ResumeFrom -> {
                        resumeByteOffset = statusResult.nextByte
                    }
                }
            }
        }

        // 3. Initiate New Resumable Session if needed
        if (sessionUri.isNullOrBlank()) {
            sessionUri = try {
                initiateResumableSession(params, totalBytes, token)
            } catch (e: DriveAuthException) {
                token = refreshTokenOrThrow()
                initiateResumableSession(params, totalBytes, token)
            }
            sessionStore.saveSession(params.photoId, sessionUri, totalBytes)
            resumeByteOffset = 0L
        }

        // 4. Stream Chunks to Session URI
        var currentOffset = resumeByteOffset
        var completedResult: ResumableUploadResult? = null

        while (currentOffset < totalBytes) {
            val chunkLength = minOf(params.chunkSize.toLong(), totalBytes - currentOffset).toInt()
            val chunkEnd = currentOffset + chunkLength - 1

            val chunkResult = uploadChunkWithRetry(
                sessionUri = sessionUri,
                file = file,
                startByte = currentOffset,
                endByte = chunkEnd,
                totalBytes = totalBytes,
                token = token
            )

            when (chunkResult) {
                is ChunkUploadResult.ResumeIncomplete -> {
                    currentOffset = chunkResult.nextByte
                }
                is ChunkUploadResult.Complete -> {
                    completedResult = chunkResult.result
                    break
                }
            }
        }

        if (completedResult == null) {
            // Final check query if finished all bytes
            val finalCheck = try {
                queryUploadStatus(sessionUri, totalBytes, token)
            } catch (_: Exception) {
                null
            }
            if (finalCheck is StatusCheckResult.Complete) {
                completedResult = finalCheck.result
            } else {
                // Lost-response recovery: Query Google Drive to see if file actually committed
                val recovered = findExistingDriveFileByPhotoId(params.photoId, params.parentFolderId, token)
                if (recovered != null) {
                    completedResult = recovered.copy(isIdempotentReplay = true)
                } else {
                    throw DriveTransientException("Upload completed streaming all bytes but file confirmation was not received.", 500)
                }
            }
        }

        sessionStore.clearSession(params.photoId)
        return@withContext completedResult
    }

    private suspend fun refreshTokenOrThrow(): String {
        val fresh = tokenProvider()
        if (fresh.isNullOrBlank()) {
            throw DriveAuthException("OAuth token refresh failed: no access token returned.")
        }
        return fresh
    }

    /**
     * Checks if a file with this photoId is already present on Google Drive.
     */
    private fun findExistingDriveFileByPhotoId(
        photoId: String,
        parentFolderId: String,
        token: String
    ): ResumableUploadResult? {
        if (photoId.isBlank()) return null
        val escapedKey = photoId.replace("'", "\\'")
        val query = "trashed = false and (appProperties has { key='assetflowPhotoId' and value='$escapedKey' } or appProperties has { key='photoId' and value='$escapedKey' })"
        val encodedQ = URLEncoder.encode(query, "UTF-8")
        val endpoint = "$apiEndpointBase/drive/v3/files?q=$encodedQ&fields=files(id,name,webViewLink,mimeType,md5Checksum)"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        val code = conn.responseCode
        if (code in 200..299) {
            val body = readStream(conn.inputStream)
            val json = JSONObject(body)
            val files = json.optJSONArray("files")
            if (files != null && files.length() > 0) {
                if (files.length() > 1) {
                    val duplicateIds = mutableListOf<String>()
                    for (i in 0 until files.length()) {
                        duplicateIds.add(files.getJSONObject(i).optString("id"))
                    }
                    throw DriveDuplicateEvidenceException(
                        "Duplicate evidence files detected on Google Drive for photoId '$photoId': $duplicateIds",
                        duplicateIds
                    )
                }
                val f = files.getJSONObject(0)
                val id = f.getString("id")
                val name = f.optString("name", "")
                val link = f.optString("webViewLink", "https://drive.google.com/file/d/$id/view")
                val mime = f.optString("mimeType", "image/jpeg")
                val md5 = f.optString("md5Checksum", "")
                return ResumableUploadResult(
                    fileId = id,
                    fileName = name,
                    webViewLink = link,
                    mimeType = mime,
                    md5Checksum = md5,
                    isIdempotentReplay = true
                )
            }
        }
        return null
    }

    /**
     * Initiates a Resumable Upload Session by sending file metadata to Drive.
     */
    private fun initiateResumableSession(
        params: ResumableUploadParams,
        totalBytes: Long,
        token: String
    ): String {
        val endpoint = "$uploadEndpointBase/upload/drive/v3/files?uploadType=resumable"
        val metadata = JSONObject().apply {
            put("name", params.standardizedFileName)
            if (params.parentFolderId.isNotBlank()) {
                put("parents", JSONArray().put(params.parentFolderId))
            }
            val props = JSONObject()
            // Canonical AssetFlow appProperties
            props.put("assetflowPhotoId", params.photoId)
            props.put("photoId", params.photoId) // Legacy backwards compatibility
            params.appProperties.forEach { (k, v) ->
                props.put(k, v)
                if (k == "clientId") props.put("assetflowClientId", v)
                if (k == "cycleId") props.put("assetflowCycleId", v)
                if (k == "assetId") props.put("assetflowAssetId", v)
            }
            put("appProperties", props)
        }
        val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", params.mimeType)
            setRequestProperty("X-Upload-Content-Length", totalBytes.toString())
            connectTimeout = 20_000
            readTimeout = 20_000
        }

        conn.outputStream.use { it.write(metadataBytes) }

        val code = conn.responseCode
        if (code in 200..299) {
            val location = conn.getHeaderField("Location")
            if (location.isNullOrBlank()) {
                throw DriveTransientException("Google Drive did not return a Location header for resumable session.", code)
            }
            return location
        } else {
            handleHttpError(code, readStream(conn.errorStream))
        }
    }

    private sealed class StatusCheckResult {
        data class ResumeFrom(val nextByte: Long) : StatusCheckResult()
        data class Complete(val result: ResumableUploadResult) : StatusCheckResult()
    }

    /**
     * Queries upload progress by sending Content-Range: bytes * / totalBytes
     */
    private fun queryUploadStatus(sessionUri: String, totalBytes: Long, token: String): StatusCheckResult {
        val conn = (URL(sessionUri).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Content-Range", "bytes */$totalBytes")
            setRequestProperty("Content-Length", "0")
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        val code = conn.responseCode
        return when (code) {
            308 -> {
                val rangeHeader = conn.getHeaderField("Range")
                val lastByte = parseLastByteFromRangeHeader(rangeHeader)
                StatusCheckResult.ResumeFrom(lastByte + 1L)
            }
            200, 201 -> {
                val body = readStream(conn.inputStream)
                StatusCheckResult.Complete(parseDriveFileJson(body))
            }
            404 -> throw DriveSessionExpiredException("Upload session has expired (404).")
            401 -> throw DriveAuthException("Unauthorized status query on upload session.")
            else -> handleHttpError(code, readStream(conn.errorStream))
        }
    }

    private sealed class ChunkUploadResult {
        data class ResumeIncomplete(val nextByte: Long) : ChunkUploadResult()
        data class Complete(val result: ResumableUploadResult) : ChunkUploadResult()
    }

    /**
     * Streams a single chunk with transient retry backoff.
     */
    private suspend fun uploadChunkWithRetry(
        sessionUri: String,
        file: File,
        startByte: Long,
        endByte: Long,
        totalBytes: Long,
        token: String
    ): ChunkUploadResult {
        var attempts = 0
        var currentToken = token

        while (true) {
            try {
                return executeChunkPut(sessionUri, file, startByte, endByte, totalBytes, currentToken)
            } catch (e: DriveAuthException) {
                attempts++
                if (attempts > 1) throw e
                currentToken = refreshTokenOrThrow()
            } catch (e: DriveTransientException) {
                attempts++
                if (attempts >= MAX_TRANSIENT_RETRIES) throw e
                val backoffMs = (e.retryAfterSeconds?.times(1000) ?: (1000L * (1 shl attempts))) + (Math.random() * 500).toLong()
                delay(backoffMs)
            }
        }
    }

    private fun executeChunkPut(
        sessionUri: String,
        file: File,
        startByte: Long,
        endByte: Long,
        totalBytes: Long,
        token: String
    ): ChunkUploadResult {
        val chunkLength = (endByte - startByte + 1).toInt()

        val conn = (URL(sessionUri).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            setFixedLengthStreamingMode(chunkLength)
            setRequestProperty("Content-Length", chunkLength.toString())
            setRequestProperty("Content-Range", "bytes $startByte-$endByte/$totalBytes")
            connectTimeout = 30_000
            readTimeout = 30_000
        }

        // Stream chunk from file without full memory buffering
        FileInputStream(file).use { inStream ->
            var skipped = 0L
            while (skipped < startByte) {
                val s = inStream.skip(startByte - skipped)
                if (s <= 0) break
                skipped += s
            }

            val buffer = ByteArray(minOf(chunkLength, 32 * 1024))
            var remaining = chunkLength
            val out = conn.outputStream
            while (remaining > 0) {
                val read = inStream.read(buffer, 0, minOf(remaining, buffer.size))
                if (read == -1) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            out.flush()
        }

        val code = conn.responseCode
        return when (code) {
            308 -> {
                val rangeHeader = conn.getHeaderField("Range")
                val lastByte = parseLastByteFromRangeHeader(rangeHeader)
                ChunkUploadResult.ResumeIncomplete(maxOf(lastByte + 1L, endByte + 1L))
            }
            200, 201 -> {
                val body = readStream(conn.inputStream)
                ChunkUploadResult.Complete(parseDriveFileJson(body))
            }
            404 -> throw DriveSessionExpiredException("Upload session expired while sending chunk.")
            401 -> throw DriveAuthException("OAuth access token expired during chunk upload.")
            else -> handleHttpError(code, readStream(conn.errorStream))
        }
    }

    private fun parseLastByteFromRangeHeader(rangeHeader: String?): Long {
        if (rangeHeader.isNullOrBlank()) return -1L
        // Format: bytes=0-262143
        val clean = rangeHeader.trim().removePrefix("bytes=").trim()
        val parts = clean.split("-")
        return if (parts.size >= 2) {
            parts[1].trim().toLongOrNull() ?: -1L
        } else {
            -1L
        }
    }

    private fun parseDriveFileJson(body: String): ResumableUploadResult {
        val json = JSONObject(body)
        val id = json.getString("id")
        val name = json.optString("name", "")
        val link = json.optString("webViewLink", "https://drive.google.com/file/d/$id/view")
        val mime = json.optString("mimeType", "image/jpeg")
        val md5 = json.optString("md5Checksum", "")
        return ResumableUploadResult(
            fileId = id,
            fileName = name,
            webViewLink = link,
            mimeType = mime,
            md5Checksum = md5
        )
    }

    private fun handleHttpError(code: Int, errorBody: String): Nothing {
        val isRateLimit = errorBody.contains("rateLimitExceeded", ignoreCase = true) ||
                errorBody.contains("userRateLimitExceeded", ignoreCase = true)

        when {
            code == 401 -> throw DriveAuthException("Google Drive authentication failed ($code): $errorBody", code)
            code == 403 && isRateLimit -> throw DriveTransientException("Google Drive rate limit exceeded (403): $errorBody", 403)
            code == 403 -> throw DrivePermissionException("Google Drive permission denied (403): $errorBody", 403)
            code == 404 -> throw DriveSessionExpiredException("Google Drive session not found (404): $errorBody")
            code == 429 -> throw DriveTransientException("Google Drive rate limited (429): $errorBody", 429)
            code in 500..599 -> throw DriveTransientException("Google Drive server error ($code): $errorBody", code)
            else -> throw DriveTransientException("Google Drive HTTP error ($code): $errorBody", code)
        }
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }
}
