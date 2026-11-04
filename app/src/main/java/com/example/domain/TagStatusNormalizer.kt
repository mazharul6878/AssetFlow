package com.example.domain

import com.example.data.model.AuditDropdownOptions

/**
 * Pure domain helper providing canonical Tag Status definition, legacy alias normalization,
 * and positive status detection for asset verification workflows.
 *
 * Adheres strictly to the architectural rules:
 * - Canonical positive Tag Status: "Tagged & Verified" (matches Lists worksheet in FA_Count_Verification_Sheet.xlsx)
 * - Supported legacy alias: "Tag OK" (normalized to "Tagged & Verified")
 * - Zero Android UI or database dependencies.
 */
object TagStatusNormalizer {

  const val CANONICAL_POSITIVE_TAG = "Tagged & Verified"
  const val LEGACY_TAG_OK_ALIAS = "Tag OK"

  /**
   * Normalizes an incoming tag status string to its canonical form:
   * - "Tagged & Verified" -> "Tagged & Verified"
   * - "Tag OK" (case-insensitive, trimmed) -> "Tagged & Verified"
   * - Any canonical option from [AuditDropdownOptions.TAG_STATUS_OPTIONS] -> preserved as-is
   * - Any special state from [AuditDropdownOptions.SPECIAL_OPTIONAL_STATES] -> preserved as-is
   * - Blank / null -> empty string ""
   * - Any unknown value -> trimmed as-is (remains invalid for validation)
   */
  fun normalize(rawTagStatus: String?): String {
    if (rawTagStatus.isNullOrBlank()) return ""
    val trimmed = rawTagStatus.trim()
    if (trimmed.equals(LEGACY_TAG_OK_ALIAS, ignoreCase = true) ||
      trimmed.equals(CANONICAL_POSITIVE_TAG, ignoreCase = true)
    ) {
      return CANONICAL_POSITIVE_TAG
    }
    val matchOption = AuditDropdownOptions.TAG_STATUS_OPTIONS.find { it.equals(trimmed, ignoreCase = true) }
    if (matchOption != null) return matchOption
    val matchSpecial = AuditDropdownOptions.SPECIAL_OPTIONAL_STATES.find { it.equals(trimmed, ignoreCase = true) }
    if (matchSpecial != null) return matchSpecial
    return trimmed
  }

  /**
   * Returns true if the tag status represents an affirmative/positive tag state
   * (i.e. either canonical "Tagged & Verified" or legacy alias "Tag OK").
   */
  fun isPositive(tagStatus: String?): Boolean {
    if (tagStatus.isNullOrBlank()) return false
    val trimmed = tagStatus.trim()
    return trimmed.equals(CANONICAL_POSITIVE_TAG, ignoreCase = true) ||
      trimmed.equals(LEGACY_TAG_OK_ALIAS, ignoreCase = true)
  }

  /**
   * Validates whether a non-blank tag status is accepted by the domain.
   * Accepts all canonical options in [AuditDropdownOptions.TAG_STATUS_OPTIONS],
   * special optional states, and the legacy alias "Tag OK".
   * Rejects unknown values.
   */
  fun isValidTagStatus(tagStatus: String?): Boolean {
    if (tagStatus.isNullOrBlank()) return true // Blank handled separately by required field validation
    val normalized = normalize(tagStatus)
    return normalized in AuditDropdownOptions.TAG_STATUS_OPTIONS ||
      normalized in AuditDropdownOptions.SPECIAL_OPTIONAL_STATES
  }

  /**
   * Validates whether the tag status fulfills required-field completion for Found = "Yes".
   * Must be a recognized option in [AuditDropdownOptions.TAG_STATUS_OPTIONS] (or its legacy alias).
   */
  fun isCompleteForVerification(tagStatus: String?): Boolean {
    if (tagStatus.isNullOrBlank()) return false
    val normalized = normalize(tagStatus)
    return normalized in AuditDropdownOptions.TAG_STATUS_OPTIONS
  }
}
