package com.example.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.data.model.UserProfile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ImageFormatInfo(
  val extension: String,
  val mimeType: String
)

data class StandardizedPhotoNamingResult(
  val finalFilename: String,
  val sequenceNumber: Int
)

/**
 * Manages camera capture and photo picker persistence in app-private storage (`filesDir/audit_photos`),
 * actual image format/extension detection (.jpg, .png, .webp, .heic — never .img),
 * date-folder naming in organization timezone, and standardized collision-safe image naming:
 * `{nickname}_{assetIdentifier}.{extension}` and `{nickname}_{assetIdentifier}_{seq}.{extension}`.
 */
object PhotoStorageManager {

  private const val PHOTOS_DIR_NAME = "audit_photos"
  const val DEFAULT_ORG_TIMEZONE = "Asia/Dhaka"

  fun validatePhotoFileBeforeUpload(file: File?): Pair<Boolean, String> {
    if (file == null || !file.exists()) {
      return false to "Photo file does not exist on device storage."
    }
    if (file.length() <= 0L) {
      return false to "Photo file is empty (0 bytes)."
    }
    return true to ""
  }

  /**
   * Detects the actual image format and extension from magic header bytes or filename/MIME type.
   * Never forces `.img`. Defaults to `.jpg` (`image/jpeg`) when storing JPEG-compressed images.
   */
  fun detectImageFormat(bytes: ByteArray, fileNameHint: String? = null, declaredMimeType: String? = null): ImageFormatInfo {
    if (bytes.size >= 12) {
      // JPEG: FF D8 FF
      if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return ImageFormatInfo("jpg", "image/jpeg")
      }
      // PNG: 89 50 4E 47
      if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
      ) {
        return ImageFormatInfo("png", "image/png")
      }
      // WebP: "RIFF"...."WEBP"
      if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
      ) {
        return ImageFormatInfo("webp", "image/webp")
      }
      // HEIC / HEIF: ...."ftypheic" or "ftypheix" or "ftypmif1"
      val ftyp = String(bytes.sliceArray(4..11), Charsets.US_ASCII)
      if (ftyp.startsWith("ftypheic", ignoreCase = true) ||
        ftyp.startsWith("ftypheix", ignoreCase = true) ||
        ftyp.startsWith("ftypmif1", ignoreCase = true)
      ) {
        return ImageFormatInfo("heic", "image/heic")
      }
    }
    val mimeLower = declaredMimeType?.trim()?.lowercase(Locale.US).orEmpty()
    val nameLower = fileNameHint.orEmpty().trim().lowercase(Locale.US)
    return when {
      mimeLower.contains("png") || nameLower.endsWith(".png") -> ImageFormatInfo("png", "image/png")
      mimeLower.contains("webp") || nameLower.endsWith(".webp") -> ImageFormatInfo("webp", "image/webp")
      mimeLower.contains("heic") || mimeLower.contains("heif") || nameLower.endsWith(".heic") ->
        ImageFormatInfo("heic", "image/heic")
      else -> ImageFormatInfo("jpg", "image/jpeg")
    }
  }

  fun detectImageFormat(file: File?, declaredMimeType: String? = null, fileNameHint: String? = null): ImageFormatInfo {
    if (file != null && file.exists() && file.length() >= 12L) {
      runCatching {
        val header = ByteArray(12)
        FileInputStream(file).use { it.read(header) }
        // JPEG: FF D8 FF
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
          return ImageFormatInfo("jpg", "image/jpeg")
        }
        // PNG: 89 50 4E 47
        if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
          header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
        ) {
          return ImageFormatInfo("png", "image/png")
        }
        // WebP: "RIFF"...."WEBP"
        if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
          header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
          header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
          header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
        ) {
          return ImageFormatInfo("webp", "image/webp")
        }
        // HEIC / HEIF: ...."ftypheic" or "ftypheix" or "ftypmif1"
        val ftyp = String(header.sliceArray(4..11), Charsets.US_ASCII)
        if (ftyp.startsWith("ftypheic", ignoreCase = true) ||
          ftyp.startsWith("ftypheix", ignoreCase = true) ||
          ftyp.startsWith("ftypmif1", ignoreCase = true)
        ) {
          return ImageFormatInfo("heic", "image/heic")
        }
      }
    }

    val mimeLower = declaredMimeType?.trim()?.lowercase(Locale.US).orEmpty()
    val nameLower = (fileNameHint ?: file?.name).orEmpty().trim().lowercase(Locale.US)
    return when {
      mimeLower.contains("png") || nameLower.endsWith(".png") -> ImageFormatInfo("png", "image/png")
      mimeLower.contains("webp") || nameLower.endsWith(".webp") -> ImageFormatInfo("webp", "image/webp")
      mimeLower.contains("heic") || mimeLower.contains("heif") || nameLower.endsWith(".heic") ->
        ImageFormatInfo("heic", "image/heic")
      else -> ImageFormatInfo("jpg", "image/jpeg")
    }
  }

  /**
   * Formats a date folder name (`YYYY-MM-DD`) in the organization's configured timezone.
   */
  fun formatOrgDateFolder(
    timestampMillis: Long = System.currentTimeMillis(),
    orgTimeZoneId: String = DEFAULT_ORG_TIMEZONE
  ): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
      timeZone = TimeZone.getTimeZone(orgTimeZoneId)
    }
    return sdf.format(Date(timestampMillis))
  }

  /**
   * Extracts `YYYY-MM-DD` from an ISO timestamp or returns the current date in the organization timezone.
   */
  fun resolveDateFolderFromCaptureIso(
    capturedAtIso: String,
    orgTimeZoneId: String = DEFAULT_ORG_TIMEZONE
  ): String {
    val prefix = capturedAtIso.trim().take(10)
    return if (prefix.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
      prefix
    } else {
      formatOrgDateFolder(System.currentTimeMillis(), orgTimeZoneId)
    }
  }

  /**
   * Sanitizes an Asset ID / Asset Number for filenames while strictly preserving leading zeros
   * (e.g., `"0001872"` remains `"0001872"`, never converted to integer `1872`).
   */
  fun sanitizeAssetIdentifierPreservingLeadingZeros(rawAssetId: String): String {
    val trimmed = rawAssetId.trim()
    require(trimmed.isNotEmpty()) { "Asset identifier cannot be blank for photo naming." }
    return trimmed.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
  }

  /**
   * Resolves a collision-safe effective nickname for filename generation:
   * - Uses lowercase normalized nickname (`abed`).
   * - If another user (`otherUid != currentUid`) has already claimed the exact same nickname in the workspace,
   *   appends a deterministic short UID suffix (`abed_u1a2`) so two users with the same nickname never overwrite each other's files.
   * - Normal unique nicknames remain unchanged (`abed`).
   */
  fun resolveCollisionSafeNickname(
    rawNickname: String,
    currentUid: String,
    existingNicknameOwners: Map<String, Set<String>> = emptyMap()
  ): String {
    val normalized = UserProfile.normalizeNickname(rawNickname)
    require(UserProfile.isValidNickname(normalized)) {
      "Valid profile nickname (2–24 lowercase alphanumeric characters) is required before uploading photos."
    }
    val cleanFormat = normalized.replace(Regex("[^a-z0-9_\\-]"), "")
    val ownersOfNickname = existingNicknameOwners[cleanFormat].orEmpty()
    val hasDifferentOwnerCollision = ownersOfNickname.any { it.isNotBlank() && it != currentUid }
    return if (hasDifferentOwnerCollision && currentUid.isNotBlank()) {
      val shortUid = currentUid.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").takeLast(4).ifEmpty { "u2" }
      "${cleanFormat}_$shortUid"
    } else {
      cleanFormat
    }
  }

  /**
   * Generates the standardized collision-safe asset photo filename:
   * - 1st photo (no existing match): `{nickname}_{assetIdentifier}.{extension}` (e.g. `abed_0001872.jpg`)
   * - 2nd photo: `{nickname}_{assetIdentifier}_1.{extension}` (`abed_0001872_1.jpg`)
   * - 3rd photo: `{nickname}_{assetIdentifier}_2.{extension}` (`abed_0001872_2.jpg`)
   * - Subsequent photos: `_3`, `_4`, etc.
   *
   * Returns `Pair(fileName, sequenceIndex)` where `sequenceIndex == 0` for the first photo, `1` for `_1`, etc.
   */
  fun buildStandardizedPhotoFilename(
    nickname: String,
    assetIdentifier: String,
    extension: String,
    existingFileNamesInDateFolder: Set<String> = emptySet(),
    currentUid: String = "",
    existingNicknameOwners: Map<String, Set<String>> = emptyMap()
  ): Pair<String, Int> {
    val safeNick = resolveCollisionSafeNickname(nickname, currentUid, existingNicknameOwners)
    val safeAssetId = sanitizeAssetIdentifierPreservingLeadingZeros(assetIdentifier)
    val cleanExt = extension.trim().trimStart('.').lowercase(Locale.US).let {
      if (it in setOf("jpg", "jpeg", "png", "webp", "heic")) {
        if (it == "jpeg") "jpg" else it
      } else {
        "jpg"
      }
    }

    val existingLower = existingFileNamesInDateFolder.map { it.trim().lowercase(Locale.US) }.toSet()
    val baseName = "${safeNick}_${safeAssetId}"
    val firstCandidate = "$baseName.$cleanExt"
    if (firstCandidate.lowercase(Locale.US) !in existingLower) {
      return firstCandidate to 0
    }

    var seq = 1
    while (seq < 10_000) {
      val candidate = "${baseName}_${seq}.$cleanExt"
      if (candidate.lowercase(Locale.US) !in existingLower) {
        return candidate to seq
      }
      seq++
    }
    return "${baseName}_${System.currentTimeMillis()}.$cleanExt" to seq
  }

  /**
   * Generates standardized collision-proof unique filename:
   * `<nickname>_<asset_id>_<unique_photo_id>.<extension>` (e.g. `abed_0001872_a1b2c3.jpg`).
   * Strictly preserves leading zeroes in Asset IDs (e.g. "0001872").
   * Sanitizes nickname to lowercase alphanumeric and underscore only.
   */
  fun buildUniquePhotoFilename(
    nickname: String,
    rawAssetId: String,
    photoId: String,
    extension: String
  ): String {
    val cleanNick = nickname.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_").ifBlank { "auditor" }
    val cleanAssetId = sanitizeAssetIdentifierPreservingLeadingZeros(rawAssetId)
    val shortPhotoId = photoId.removePrefix("photo_").replace(Regex("[^a-zA-Z0-9]"), "").take(8).ifBlank { "p1" }.lowercase(Locale.US)
    val cleanExt = extension.trim().trimStart('.').lowercase(Locale.US).let {
      if (it in setOf("jpg", "jpeg", "png", "webp", "heic")) {
        if (it == "jpeg") "jpg" else it
      } else {
        "jpg"
      }
    }
    return "${cleanNick}_${cleanAssetId}_${shortPhotoId}.$cleanExt"
  }

  fun buildStandardizedPhotoName(
    rawNickname: String,
    currentUid: String = "",
    rawAssetId: String,
    extension: String,
    existingNicknamesToUids: Map<String, Set<String>> = emptyMap(),
    existingFilenamesInDateFolder: Set<String> = emptySet()
  ): StandardizedPhotoNamingResult {
    val (name, seq) = buildStandardizedPhotoFilename(
      nickname = rawNickname,
      assetIdentifier = rawAssetId,
      extension = extension,
      existingFileNamesInDateFolder = existingFilenamesInDateFolder,
      currentUid = currentUid,
      existingNicknameOwners = existingNicknamesToUids
    )
    return StandardizedPhotoNamingResult(finalFilename = name, sequenceNumber = seq)
  }

  fun saveCameraBitmap(
    context: Context,
    recordKey: String,
    bitmap: Bitmap
  ): Result<String> = runCatching {
    val sanitizedKey = recordKey.trim().ifBlank { "obs" }.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    val dir = File(context.applicationContext.filesDir, PHOTOS_DIR_NAME).apply { mkdirs() }
    val targetFile = File(dir, "cam_${sanitizedKey}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}.jpg")
    FileOutputStream(targetFile).use { out ->
      val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
      if (!compressed) {
        throw IllegalStateException("Failed to compress captured camera photograph.")
      }
      out.flush()
    }
    if (!targetFile.exists() || targetFile.length() <= 0L) {
      throw IllegalStateException("Camera photograph file was not written to storage.")
    }
    targetFile.absolutePath
  }

  fun savePickedPhotoUri(
    context: Context,
    recordKey: String,
    sourceUri: Uri
  ): Result<String> = runCatching {
    val sanitizedKey = recordKey.trim().ifBlank { "obs" }.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
    val dir = File(context.applicationContext.filesDir, PHOTOS_DIR_NAME).apply { mkdirs() }
    val resolver = context.applicationContext.contentResolver
    val mimeType = runCatching { resolver.getType(sourceUri) }.getOrNull()
    val extInfo = detectImageFormat(null, mimeType, sourceUri.lastPathSegment)
    val tempFile = File(dir, "pick_${sanitizedKey}_${System.currentTimeMillis()}.${extInfo.extension}")
    val input = resolver.openInputStream(sourceUri)
      ?: throw IllegalStateException("Unable to open selected photograph stream.")
    input.use { inStream ->
      FileOutputStream(tempFile).use { outStream ->
        inStream.copyTo(outStream)
        outStream.flush()
      }
    }
    if (!tempFile.exists() || tempFile.length() <= 0L) {
      throw IllegalStateException("Selected photograph could not be persisted to local storage.")
    }
    // Verify actual header magic bytes and rename extension if needed so extension always matches actual image format
    val verifiedFormat = detectImageFormat(tempFile, mimeType, tempFile.name)
    if (!tempFile.name.endsWith(".${verifiedFormat.extension}", ignoreCase = true)) {
      val correctedFile = File(dir, tempFile.nameWithoutExtension + ".${verifiedFormat.extension}")
      if (tempFile.renameTo(correctedFile)) {
        return@runCatching correctedFile.absolutePath
      }
    }
    tempFile.absolutePath
  }

  fun parsePhotoList(photoRef: String): List<String> =
    photoRef.split("|").map { it.trim() }.filter { it.isNotEmpty() }

  fun serializePhotoList(photos: List<String>): String =
    photos.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" | ")

  fun appendPhoto(existingPhotoRef: String, newPhotoPath: String): String {
    val list = parsePhotoList(existingPhotoRef).toMutableList()
    if (newPhotoPath.isNotBlank() && newPhotoPath.trim() !in list) {
      list.add(newPhotoPath.trim())
    }
    return serializePhotoList(list)
  }

  fun replacePhotoAt(existingPhotoRef: String, index: Int, replacementPath: String): String {
    val list = parsePhotoList(existingPhotoRef).toMutableList()
    if (index in list.indices && replacementPath.isNotBlank()) {
      list[index] = replacementPath.trim()
    } else if (replacementPath.isNotBlank()) {
      list.add(replacementPath.trim())
    }
    return serializePhotoList(list)
  }

  fun removePhotoAt(existingPhotoRef: String, index: Int): String {
    val list = parsePhotoList(existingPhotoRef).toMutableList()
    if (index in list.indices) {
      list.removeAt(index)
    }
    return serializePhotoList(list)
  }
}
