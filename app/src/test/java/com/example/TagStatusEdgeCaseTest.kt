package com.example

import com.example.data.model.AuditDropdownOptions
import com.example.data.model.CountingRecord
import com.example.data.model.FARAsset
import com.example.data.model.VerificationResultStatus
import com.example.domain.AuditCalculationEngine
import com.example.domain.TagStatusNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2.3.5E — Tag Status Edge-Case Correction & Regression Test Suite
 *
 * Verifies that:
 * 1. Unknown Tag Status produces a validation error.
 * 2. Unknown Tag Status does NOT generate a Tag Issue exception.
 * 3. Canonical positive status ("Tagged & Verified") generates no Tag Issue exception.
 * 4. Legacy Tag OK generates no Tag Issue exception.
 * 5. Valid negative statuses ("Untagged - New Tag Required", "Damaged / Illegible Tag", "Tag Mismatch") preserve their Tag Issue behavior.
 * 6. "Not Applicable" preserves previous behavior.
 * 7. Blank status preserves previous required-field behavior.
 * 8. Existing calculation priority rules remain intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagStatusEdgeCaseTest {

  private val sampleFar = FARAsset(
    id = FARAsset.buildAssetKey("FA-88801", 10),
    sl = 10,
    assetId = "FA-88801",
    sourceRow = 10,
    assetDescription = "Precision Laser Measurement Bench",
    assetClass = "Lab Equipment",
    capitalizedOn = "2024-01-15",
    costTk = 145000.0,
    nbvTk = 96667.0,
    farQty = 1,
    farQtyPresent = true,
    method = "Straight Line"
  )

  private val baseVerifiedRecord = CountingRecord(
    id = sampleFar.assetKey,
    assetKey = sampleFar.assetKey,
    assetId = sampleFar.assetId,
    sourceRow = sampleFar.sourceRow,
    found = "Yes",
    countDate = "2026-11-01",
    countedBy = "Jane Doe (Senior Auditor)",
    countedByUserId = "uid-auditor-1",
    buildingSite = "Main Campus",
    floor = "2nd Floor",
    roomLocation = "Room 204",
    department = "Metrology",
    userCustodian = "R. Karim",
    tagStatus = TagStatusNormalizer.CANONICAL_POSITIVE_TAG,
    tagNumberAcn = "TAG-88801",
    brandModel = "Keysight High-Res Meter",
    physicalQtyText = "1",
    condition = "Good",
    inUse = "Yes",
    photoRef = "file:///storage/photos/rec_501.jpg",
    remarks = "Audited in place",
    clientExplanation = ""
  )

  // 1. Unknown Tag Status produces a validation error
  @Test
  fun test01_unknownTagStatus_producesValidationError() {
    val invalidInputs = listOf("InvalidTag", "Custom Status", "XYZ-999", "Unknown Tag", "Broken")
    for (invalid in invalidInputs) {
      val outcome = AuditCalculationEngine.evaluateRecord(
        farAsset = sampleFar,
        record = baseVerifiedRecord.copy(tagStatus = invalid)
      )
      assertTrue(
        "Expected validation error for invalid tag status '$invalid'",
        outcome.validationErrors.any { it.contains("Invalid Tag Status option: '$invalid'") }
      )
    }
  }

  // 2. Unknown Tag Status does not generate a Tag Issue exception
  @Test
  fun test02_unknownTagStatus_doesNotGenerateTagIssueException() {
    val invalidInputs = listOf("InvalidTag", "Custom Status", "XYZ-999", "Unknown Tag", "Broken")
    for (invalid in invalidInputs) {
      val outcome = AuditCalculationEngine.evaluateRecord(
        farAsset = sampleFar,
        record = baseVerifiedRecord.copy(tagStatus = invalid)
      )
      assertFalse(
        "Must NOT fabricate a Tag Issue exception for unknown input '$invalid'",
        outcome.applicableExceptions.any { it.contains("Tag Issue") }
      )
      assertEquals(
        "Must be PENDING_VERIFICATION due to validation error, not VERIFIED_TAG_ISSUE",
        VerificationResultStatus.PENDING_VERIFICATION,
        outcome.primaryResult
      )
    }
  }

  // 3. Canonical positive status generates no Tag Issue
  @Test
  fun test03_canonicalPositiveStatus_generatesNoTagIssue() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(tagStatus = TagStatusNormalizer.CANONICAL_POSITIVE_TAG)
    )
    assertTrue("Validation errors must be empty", outcome.validationErrors.isEmpty())
    assertFalse("Must not generate Tag Issue exception", outcome.applicableExceptions.any { it.contains("Tag Issue") })
    assertEquals(VerificationResultStatus.VERIFIED, outcome.primaryResult)
    assertEquals(VerificationResultStatus.COUNTED, outcome.countingStatus)
  }

  // 4. Legacy Tag OK generates no Tag Issue
  @Test
  fun test04_legacyTagOk_generatesNoTagIssue() {
    val legacyInputs = listOf("Tag OK", "tag ok", "TAG OK", " Tag OK ")
    for (legacy in legacyInputs) {
      val outcome = AuditCalculationEngine.evaluateRecord(
        farAsset = sampleFar,
        record = baseVerifiedRecord.copy(tagStatus = legacy)
      )
      assertTrue("Validation errors must be empty for legacy alias '$legacy'", outcome.validationErrors.isEmpty())
      assertFalse("Must not generate Tag Issue exception for legacy alias '$legacy'", outcome.applicableExceptions.any { it.contains("Tag Issue") })
      assertEquals("Legacy alias '$legacy' must evaluate to VERIFIED", VerificationResultStatus.VERIFIED, outcome.primaryResult)
    }
  }

  // 5. Valid negative statuses preserve their existing Tag Issue behavior
  @Test
  fun test05_validNegativeStatuses_preserveExistingTagIssueBehavior() {
    val validNegativeStatuses = listOf(
      "Untagged - New Tag Required",
      "Damaged / Illegible Tag",
      "Tag Mismatch"
    )
    for (negative in validNegativeStatuses) {
      val outcome = AuditCalculationEngine.evaluateRecord(
        farAsset = sampleFar,
        record = baseVerifiedRecord.copy(tagStatus = negative)
      )
      assertTrue("Validation errors must be empty for valid negative option '$negative'", outcome.validationErrors.isEmpty())
      assertTrue(
        "Expected Tag Issue exception for '$negative'",
        outcome.applicableExceptions.any { it == "Tag Issue ($negative)" }
      )
      assertEquals(
        "Valid negative tag status '$negative' must evaluate to VERIFIED_TAG_ISSUE",
        VerificationResultStatus.VERIFIED_TAG_ISSUE,
        outcome.primaryResult
      )
    }
  }

  // 6. Not Applicable remains unchanged
  @Test
  fun test06_notApplicableStatus_remainsUnchanged() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(tagStatus = "Not Applicable")
    )
    assertTrue("Validation errors must be empty for 'Not Applicable'", outcome.validationErrors.isEmpty())
    assertTrue(
      "Expected Tag Issue exception for 'Not Applicable'",
      outcome.applicableExceptions.any { it == "Tag Issue (Not Applicable)" }
    )
    assertEquals(
      "Not Applicable must evaluate to VERIFIED_TAG_ISSUE",
      VerificationResultStatus.VERIFIED_TAG_ISSUE,
      outcome.primaryResult
    )
  }

  // 7. Blank status remains unchanged
  @Test
  fun test07_blankStatus_remainsUnchanged() {
    val outcome = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(tagStatus = "")
    )
    // Blank tag status is incomplete for verification, but is not an explicit dropdown validation error
    assertFalse("Blank tag status must not generate Tag Issue exception", outcome.applicableExceptions.any { it.contains("Tag Issue") })
    assertEquals(
      "Blank tag status must evaluate to PENDING_VERIFICATION due to incomplete required fields",
      VerificationResultStatus.PENDING_VERIFICATION,
      outcome.primaryResult
    )
    assertNotEquals(VerificationResultStatus.VERIFIED, outcome.primaryResult)
  }

  // 8. Existing calculation priority rules remain intact
  @Test
  fun test08_existingCalculationPriorityRules_remainIntact() {
    // Step 1: Found blank -> PENDING
    val step1 = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(found = "")
    )
    assertEquals(VerificationResultStatus.PENDING, step1.primaryResult)

    // Step 2: Found = "No" -> NOT_FOUND
    val step2 = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(found = "No")
    )
    assertEquals(VerificationResultStatus.NOT_FOUND, step2.primaryResult)

    // Step 3: Found = "Not Accessible" -> NOT_ACCESSIBLE
    val step3 = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(found = "Not Accessible")
    )
    assertEquals(VerificationResultStatus.NOT_ACCESSIBLE, step3.primaryResult)

    // Step 4: Draft -> PENDING_VERIFICATION
    val step4Draft = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(isDraft = true)
    )
    assertEquals(VerificationResultStatus.PENDING_VERIFICATION, step4Draft.primaryResult)

    // Step 5: Qty Short (farQty = 2, physicalQty = 1) -> QTY_SHORT (has priority over tag issue)
    val step5QtyShort = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar.copy(farQty = 2),
      record = baseVerifiedRecord.copy(physicalQtyText = "1", tagStatus = "Untagged - New Tag Required")
    )
    assertEquals(VerificationResultStatus.QTY_SHORT, step5QtyShort.primaryResult)
    assertTrue("Should retain Tag Issue in exceptions", step5QtyShort.applicableExceptions.any { it.contains("Tag Issue") })
    assertTrue("Should retain Qty Short in exceptions", step5QtyShort.applicableExceptions.any { it.startsWith(VerificationResultStatus.QTY_SHORT) })

    // Step 6: Qty Excess (farQty = 1, physicalQty = 3) -> QTY_EXCESS (has priority over tag issue)
    val step6QtyExcess = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar.copy(farQty = 1),
      record = baseVerifiedRecord.copy(physicalQtyText = "3", tagStatus = "Untagged - New Tag Required")
    )
    assertEquals(VerificationResultStatus.QTY_EXCESS, step6QtyExcess.primaryResult)
    assertTrue("Should retain Tag Issue in exceptions", step6QtyExcess.applicableExceptions.any { it.contains("Tag Issue") })

    // Step 7: Condition Issue -> VERIFIED_CONDITION_ISSUE (has priority over tag issue)
    val step7CondIssue = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(condition = "Damaged", tagStatus = "Untagged - New Tag Required")
    )
    assertEquals(VerificationResultStatus.VERIFIED_CONDITION_ISSUE, step7CondIssue.primaryResult)
    assertTrue(step7CondIssue.applicableExceptions.any { it.contains("Condition Issue") })
    assertTrue(step7CondIssue.applicableExceptions.any { it.contains("Tag Issue") })

    // Step 8: Tag Status Issue alone -> VERIFIED_TAG_ISSUE
    val step8TagIssue = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord.copy(tagStatus = "Untagged - New Tag Required")
    )
    assertEquals(VerificationResultStatus.VERIFIED_TAG_ISSUE, step8TagIssue.primaryResult)

    // Step 9: Clean record -> VERIFIED
    val step9Clean = AuditCalculationEngine.evaluateRecord(
      farAsset = sampleFar,
      record = baseVerifiedRecord
    )
    assertEquals(VerificationResultStatus.VERIFIED, step9Clean.primaryResult)
    assertTrue(step9Clean.applicableExceptions.isEmpty())
  }
}
