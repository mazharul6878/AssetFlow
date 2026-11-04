package com.example.data.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleDriveResumableUploadTest {

    private lateinit var server: HttpServer
    private var serverBaseUrl: String = ""
    private lateinit var context: Context
    private lateinit var sessionStore: ResumableSessionStore
    private lateinit var testPhotoFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        sessionStore = ResumableSessionStore(context)
        sessionStore.clearAllSessions()

        // Create a 600KB dummy test photo file on disk (requires multiple 256KB chunks)
        val testDir = File(context.filesDir, "test_photos").apply { mkdirs() }
        testPhotoFile = File(testDir, "test_upload_photo.jpg")
        val data = ByteArray(600 * 1024) { (it % 128).toByte() }
        // Magic bytes for JPEG
        data[0] = 0xFF.toByte()
        data[1] = 0xD8.toByte()
        data[2] = 0xFF.toByte()
        testPhotoFile.writeBytes(data)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        val port = server.address.port
        serverBaseUrl = "http://127.0.0.1:$port"
    }

    @After
    fun tearDown() {
        server.stop(0)
        if (::testPhotoFile.isInitialized && testPhotoFile.exists()) {
            testPhotoFile.delete()
        }
        sessionStore.clearAllSessions()
    }

    @Test
    fun test01_successfulMultiChunkResumableUpload() = runBlocking {
        val totalBytes = testPhotoFile.length()
        val receivedChunks = mutableListOf<String>()

        // 1. Files lookup (idempotency check) returns empty list
        server.createContext("/drive/v3/files", HttpHandler { exchange ->
            val resp = JSONObject().put("files", JSONArray()).toString()
            exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(resp.toByteArray()) }
        })

        // 2. Initiate Resumable Session
        server.createContext("/upload/drive/v3/files", HttpHandler { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer valid-token-123", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals(totalBytes.toString(), exchange.requestHeaders.getFirst("X-Upload-Content-Length"))
            exchange.requestBody.readAllBytes()

            val sessionLocation = "$serverBaseUrl/resumable-session/sess_001"
            exchange.responseHeaders.add("Location", sessionLocation)
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        })

        // 3. Resumable Session Upload Handlers
        server.createContext("/resumable-session/sess_001", HttpHandler { exchange ->
            assertEquals("PUT", exchange.requestMethod)
            val contentRange = exchange.requestHeaders.getFirst("Content-Range")
            receivedChunks.add(contentRange)
            exchange.requestBody.readAllBytes()

            // Chunk 1: 0 to 262143
            if (contentRange.startsWith("bytes 0-262143/")) {
                exchange.responseHeaders.add("Range", "bytes=0-262143")
                exchange.sendResponseHeaders(308, -1)
                exchange.close()
            }
            // Chunk 2: 262144 to 524287
            else if (contentRange.startsWith("bytes 262144-524287/")) {
                exchange.responseHeaders.add("Range", "bytes=0-524287")
                exchange.sendResponseHeaders(308, -1)
                exchange.close()
            }
            // Chunk 3: 524288 to 614399 (final)
            else if (contentRange.startsWith("bytes 524288-614399/")) {
                val fileJson = JSONObject().apply {
                    put("id", "drive_file_resumable_success")
                    put("name", "rahim_000142.jpg")
                    put("webViewLink", "https://drive.google.com/file/d/drive_file_resumable_success/view")
                    put("mimeType", "image/jpeg")
                    put("md5Checksum", "mock_md5_hash")
                }.toString()
                exchange.sendResponseHeaders(200, fileJson.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(fileJson.toByteArray()) }
            } else {
                exchange.sendResponseHeaders(400, -1)
                exchange.close()
            }
        })

        val uploader = GoogleDriveResumableUploader(
            sessionStore = sessionStore,
            tokenProvider = { "valid-token-123" },
            uploadEndpointBase = serverBaseUrl,
            apiEndpointBase = serverBaseUrl
        )

        val result = uploader.uploadPhoto(
            ResumableUploadParams(
                photoId = "photo_test_01",
                localFile = testPhotoFile,
                standardizedFileName = "rahim_000142.jpg",
                mimeType = "image/jpeg",
                parentFolderId = "folder_date_01",
                chunkSize = 256 * 1024
            )
        )

        assertEquals("drive_file_resumable_success", result.fileId)
        assertEquals("rahim_000142.jpg", result.fileName)
        assertEquals(3, receivedChunks.size)
        // Session should be cleared from store upon successful completion
        assertEquals(null, sessionStore.getSession("photo_test_01"))
    }

    @Test
    fun test02_interruptedUploadResumesFromStatusQuery308() = runBlocking {
        val totalBytes = testPhotoFile.length()

        // Pre-save an existing session in store to simulate a worker restarting after a crash
        val sessionUrl = "$serverBaseUrl/resumable-session/sess_interrupted"
        sessionStore.saveSession("photo_test_02", sessionUrl, totalBytes)

        server.createContext("/drive/v3/files", HttpHandler { exchange ->
            val resp = JSONObject().put("files", JSONArray()).toString()
            exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(resp.toByteArray()) }
        })

        val statusQueryReceived = AtomicInteger(0)
        val chunksReceived = mutableListOf<String>()

        server.createContext("/resumable-session/sess_interrupted", HttpHandler { exchange ->
            val contentRange = exchange.requestHeaders.getFirst("Content-Range")
            exchange.requestBody.readAllBytes()
            if (contentRange == "bytes */$totalBytes") {
                // Interruption Status Query: returns byte 0-262143 was already received
                statusQueryReceived.incrementAndGet()
                exchange.responseHeaders.add("Range", "bytes=0-262143")
                exchange.sendResponseHeaders(308, -1)
                exchange.close()
            } else {
                chunksReceived.add(contentRange)
                if (contentRange.startsWith("bytes 262144-524287/")) {
                    exchange.responseHeaders.add("Range", "bytes=0-524287")
                    exchange.sendResponseHeaders(308, -1)
                    exchange.close()
                } else if (contentRange.startsWith("bytes 524288-614399/")) {
                    val fileJson = JSONObject().apply {
                        put("id", "drive_file_resumed_ok")
                        put("name", "rahim_000142.jpg")
                        put("webViewLink", "https://drive.google.com/file/d/drive_file_resumed_ok/view")
                        put("mimeType", "image/jpeg")
                    }.toString()
                    exchange.sendResponseHeaders(200, fileJson.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(fileJson.toByteArray()) }
                } else {
                    exchange.sendResponseHeaders(400, -1)
                    exchange.close()
                }
            }
        })

        val uploader = GoogleDriveResumableUploader(
            sessionStore = sessionStore,
            tokenProvider = { "valid-token-123" },
            uploadEndpointBase = serverBaseUrl,
            apiEndpointBase = serverBaseUrl
        )

        val result = uploader.uploadPhoto(
            ResumableUploadParams(
                photoId = "photo_test_02",
                localFile = testPhotoFile,
                standardizedFileName = "rahim_000142.jpg",
                mimeType = "image/jpeg",
                parentFolderId = "folder_date_01",
                chunkSize = 256 * 1024
            )
        )

        assertEquals("drive_file_resumed_ok", result.fileId)
        assertEquals(1, statusQueryReceived.get())
        // Started from byte 262144, skipped first chunk!
        assertEquals(2, chunksReceived.size)
        assertTrue(chunksReceived[0].startsWith("bytes 262144-524287/"))
    }

    @Test
    fun test03_idempotencyLookupPreventsDuplicateUpload() = runBlocking {
        // Mock files lookup returns existing file with appProperties.photoId
        server.createContext("/drive/v3/files", HttpHandler { exchange ->
            val fileObj = JSONObject().apply {
                put("id", "existing_drive_id_456")
                put("name", "rahim_000142.jpg")
                put("webViewLink", "https://drive.google.com/file/d/existing_drive_id_456/view")
                put("mimeType", "image/jpeg")
                put("md5Checksum", "existing_md5")
            }
            val resp = JSONObject().put("files", JSONArray().put(fileObj)).toString()
            exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(resp.toByteArray()) }
        })

        val uploader = GoogleDriveResumableUploader(
            sessionStore = sessionStore,
            tokenProvider = { "valid-token-123" },
            uploadEndpointBase = serverBaseUrl,
            apiEndpointBase = serverBaseUrl
        )

        val result = uploader.uploadPhoto(
            ResumableUploadParams(
                photoId = "photo_idempotent_999",
                localFile = testPhotoFile,
                standardizedFileName = "rahim_000142.jpg",
                mimeType = "image/jpeg",
                parentFolderId = "folder_date_01"
            )
        )

        assertEquals("existing_drive_id_456", result.fileId)
        assertTrue(result.isIdempotentReplay)
    }

    @Test
    fun test04_tokenRefreshOnceOn401() = runBlocking {
        var tokenCallCount = 0
        val tokenProvider: suspend () -> String? = {
            tokenCallCount++
            if (tokenCallCount == 1) "expired_token_01" else "refreshed_token_02"
        }

        server.createContext("/drive/v3/files", HttpHandler { exchange ->
            val resp = JSONObject().put("files", JSONArray()).toString()
            exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(resp.toByteArray()) }
        })

        val sessionInitCalls = AtomicInteger(0)
        server.createContext("/upload/drive/v3/files", HttpHandler { exchange ->
            val auth = exchange.requestHeaders.getFirst("Authorization")
            exchange.requestBody.readAllBytes()
            sessionInitCalls.incrementAndGet()
            if (auth == "Bearer expired_token_01") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.add("Location", "$serverBaseUrl/resumable-session/sess_refreshed")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
        })

        server.createContext("/resumable-session/sess_refreshed", HttpHandler { exchange ->
            exchange.requestBody.readAllBytes()
            val fileJson = JSONObject().apply {
                put("id", "drive_file_refreshed")
                put("name", "rahim_000142.jpg")
                put("webViewLink", "https://drive.google.com/file/d/drive_file_refreshed/view")
                put("mimeType", "image/jpeg")
            }.toString()
            exchange.sendResponseHeaders(200, fileJson.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(fileJson.toByteArray()) }
        })

        val uploader = GoogleDriveResumableUploader(
            sessionStore = sessionStore,
            tokenProvider = tokenProvider,
            uploadEndpointBase = serverBaseUrl,
            apiEndpointBase = serverBaseUrl
        )

        val result = uploader.uploadPhoto(
            ResumableUploadParams(
                photoId = "photo_auth_refresh",
                localFile = testPhotoFile,
                standardizedFileName = "rahim_000142.jpg",
                mimeType = "image/jpeg",
                parentFolderId = "folder_date_01",
                chunkSize = 1024 * 1024 // Large enough for 1 chunk
            )
        )

        assertEquals("drive_file_refreshed", result.fileId)
        assertEquals(2, sessionInitCalls.get())
    }

    @Test
    fun test05_permissionDenied403ThrowsDrivePermissionException() = runBlocking {
        server.createContext("/drive/v3/files", HttpHandler { exchange ->
            val resp = JSONObject().put("files", JSONArray()).toString()
            exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(resp.toByteArray()) }
        })

        server.createContext("/upload/drive/v3/files", HttpHandler { exchange ->
            exchange.requestBody.readAllBytes()
            val errJson = JSONObject().put("error", JSONObject().put("message", "The caller does not have permission")).toString()
            exchange.sendResponseHeaders(403, errJson.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(errJson.toByteArray()) }
        })

        val uploader = GoogleDriveResumableUploader(
            sessionStore = sessionStore,
            tokenProvider = { "valid_token" },
            uploadEndpointBase = serverBaseUrl,
            apiEndpointBase = serverBaseUrl
        )

        try {
            uploader.uploadPhoto(
                ResumableUploadParams(
                    photoId = "photo_perm_denied",
                    localFile = testPhotoFile,
                    standardizedFileName = "rahim_000142.jpg",
                    mimeType = "image/jpeg",
                    parentFolderId = "folder_date_01"
                )
            )
            fail("Expected DrivePermissionException")
        } catch (e: DrivePermissionException) {
            assertEquals(403, e.statusCode)
        }
    }

    @Test
    fun test06_sessionExpiry404RestartsFreshUploadSession() = runBlocking {
        val totalBytes = testPhotoFile.length()
        val expiredSessionUrl = "$serverBaseUrl/resumable-session/sess_expired_404"
        sessionStore.saveSession("photo_test_expiry", expiredSessionUrl, totalBytes)

        server.createContext("/drive/v3/files", HttpHandler { exchange ->
            val resp = JSONObject().put("files", JSONArray()).toString()
            exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(resp.toByteArray()) }
        })

        // Expired session returns 404
        server.createContext("/resumable-session/sess_expired_404", HttpHandler { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        })

        // New session initiation succeeds
        val newSessionInit = AtomicInteger(0)
        server.createContext("/upload/drive/v3/files", HttpHandler { exchange ->
            exchange.requestBody.readAllBytes()
            newSessionInit.incrementAndGet()
            exchange.responseHeaders.add("Location", "$serverBaseUrl/resumable-session/sess_new_fresh")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        })

        server.createContext("/resumable-session/sess_new_fresh", HttpHandler { exchange ->
            exchange.requestBody.readAllBytes()
            val fileJson = JSONObject().apply {
                put("id", "drive_file_from_new_session")
                put("name", "rahim_000142.jpg")
                put("webViewLink", "https://drive.google.com/file/d/drive_file_from_new_session/view")
                put("mimeType", "image/jpeg")
            }.toString()
            exchange.sendResponseHeaders(200, fileJson.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(fileJson.toByteArray()) }
        })

        val uploader = GoogleDriveResumableUploader(
            sessionStore = sessionStore,
            tokenProvider = { "valid_token" },
            uploadEndpointBase = serverBaseUrl,
            apiEndpointBase = serverBaseUrl
        )

        val result = uploader.uploadPhoto(
            ResumableUploadParams(
                photoId = "photo_test_expiry",
                localFile = testPhotoFile,
                standardizedFileName = "rahim_000142.jpg",
                mimeType = "image/jpeg",
                parentFolderId = "folder_date_01",
                chunkSize = 1024 * 1024
            )
        )

        assertEquals("drive_file_from_new_session", result.fileId)
        assertEquals(1, newSessionInit.get())
    }
}
