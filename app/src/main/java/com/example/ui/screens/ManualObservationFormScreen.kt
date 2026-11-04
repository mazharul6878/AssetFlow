package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.FoundNotInFarRecord
import com.example.data.model.MasterDataConfig
import com.example.data.model.UserProfile
import com.example.domain.AuditCalculationEngine
import com.example.domain.TagStatusNormalizer
import com.example.ui.components.AuditDropdownField
import com.example.ui.components.PhotoEvidenceSection
import com.example.ui.components.ResultStatusBadge
import com.example.ui.theme.AuditNavyPrimary
import com.example.ui.theme.AuditNavySecondary
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg

@Composable
fun ManualObservationFormScreen(
  initialObservation: FoundNotInFarRecord?,
  prefilledSearchedAssetId: String = "",
  masterData: MasterDataConfig,
  userProfile: UserProfile,
  onSaveObservation: (
    record: FoundNotInFarRecord,
    isDraft: Boolean,
    markAsReviewed: Boolean,
    explicitDateEdit: Boolean
  ) -> Unit,
  onBack: () -> Unit
) {
  BackHandler(onBack = onBack)

  val existing = initialObservation
  val generatedObsId = remember(existing, prefilledSearchedAssetId) {
    existing?.tempId?.takeIf { it.isNotBlank() }
      ?: AuditCalculationEngine.generateUniqueObservationId()
  }

  var assetIdIfKnown by remember(existing, prefilledSearchedAssetId) {
    mutableStateOf(existing?.assetIdIfKnown ?: prefilledSearchedAssetId)
  }
  var description by remember(existing) { mutableStateOf(existing?.description ?: "") }
  var assetClass by remember(existing) { mutableStateOf(existing?.assetClass ?: "") }

  var buildingSite by remember(existing) { mutableStateOf(existing?.buildingSite ?: "") }
  var buildingSiteOther by remember(existing) { mutableStateOf(existing?.buildingSiteOther ?: "") }
  var floor by remember(existing) { mutableStateOf(existing?.floor ?: "") }
  var floorOther by remember(existing) { mutableStateOf(existing?.floorOther ?: "") }

  val filteredRooms = remember(masterData, buildingSite, floor) {
    masterData.filteredRooms(buildingSite, floor)
  }
  var roomLocation by remember(existing) { mutableStateOf(existing?.roomLocation ?: "") }
  var roomLocationOther by remember(existing) { mutableStateOf(existing?.roomLocationOther ?: "") }

  var department by remember(existing) { mutableStateOf(existing?.department ?: "") }
  var departmentOther by remember(existing) { mutableStateOf(existing?.departmentOther ?: "") }
  var userCustodian by remember(existing) { mutableStateOf(existing?.userCustodian ?: "") }
  var tagStatus by remember(existing) { mutableStateOf(TagStatusNormalizer.normalize(existing?.tagStatus ?: "")) }
  var tagNumberAcn by remember(existing) { mutableStateOf(existing?.tagNumberAcn ?: "") }
  var brandModel by remember(existing) { mutableStateOf(existing?.brandModel ?: "") }
  var physicalQtyText by remember(existing) {
    mutableStateOf(
      existing?.physicalQtyText?.ifBlank {
        if ((existing.physicalQty) > 0L) existing.physicalQty.toString() else ""
      } ?: ""
    )
  }
  var found by remember(existing) { mutableStateOf(existing?.found ?: "") }
  var condition by remember(existing) { mutableStateOf(existing?.condition ?: "") }
  var inUse by remember(existing) { mutableStateOf(existing?.inUse ?: "") }

  var countDate by remember(existing) {
    mutableStateOf(
      AuditCalculationEngine.resolveCountDate(
        existingSavedCountDate = existing?.countDate,
        userEnteredDate = null
      )
    )
  }
  var isEditingCountDate by remember { mutableStateOf(false) }
  var explicitDateEdited by remember { mutableStateOf(false) }

  // Automatically populated from authenticated user's profile Full Name on creation,
  // or retains historical countedBy if already saved earlier
  val displayedCountedBy = remember(existing, userProfile) {
    existing?.countedBy?.takeIf { it.isNotBlank() } ?: userProfile.effectiveFullName()
  }
  val displayedCountedByUid = remember(existing, userProfile) {
    existing?.countedByUserId?.takeIf { it.isNotBlank() } ?: userProfile.userId
  }

  var photoRef by remember(existing) { mutableStateOf(existing?.photoRef ?: "") }
  var remarks by remember(existing) { mutableStateOf(existing?.remarks ?: "") }
  var clientExplanation by remember(existing) { mutableStateOf(existing?.clientExplanation ?: "") }
  var attemptedSubmit by remember { mutableStateOf(false) }

  val draftRecord by remember(
    generatedObsId, assetIdIfKnown, description, assetClass,
    buildingSite, buildingSiteOther, floor, floorOther,
    roomLocation, roomLocationOther, department, departmentOther,
    userCustodian, tagStatus, tagNumberAcn, brandModel,
    physicalQtyText, found, condition, inUse, countDate,
    displayedCountedBy, displayedCountedByUid, photoRef,
    remarks, clientExplanation, existing
  ) {
    derivedStateOf {
      FoundNotInFarRecord(
        id = existing?.id?.ifBlank { generatedObsId } ?: generatedObsId,
        tempId = generatedObsId,
        assetIdIfKnown = assetIdIfKnown,
        description = description,
        assetClass = assetClass,
        buildingSite = buildingSite,
        buildingSiteOther = buildingSiteOther,
        floor = floor,
        floorOther = floorOther,
        roomLocation = roomLocation,
        roomLocationOther = roomLocationOther,
        department = department,
        departmentOther = departmentOther,
        userCustodian = userCustodian,
        tagStatus = tagStatus,
        tagNumberAcn = tagNumberAcn,
        brandModel = brandModel,
        physicalQtyText = physicalQtyText,
        found = found,
        condition = condition,
        inUse = inUse,
        countDate = countDate,
        countedBy = displayedCountedBy,
        countedByUserId = displayedCountedByUid,
        reviewedBy = existing?.reviewedBy ?: "",
        reviewedByUserId = existing?.reviewedByUserId ?: "",
        reviewedAt = existing?.reviewedAt ?: "",
        photoRef = photoRef,
        remarks = remarks,
        clientExplanation = clientExplanation,
        auditTrailSummary = existing?.auditTrailSummary ?: ""
      )
    }
  }

  val liveValidation by remember(draftRecord, existing, userProfile, explicitDateEdited) {
    derivedStateOf {
      AuditCalculationEngine.evaluateManualObservation(
        draft = draftRecord,
        existingRecord = existing,
        authenticatedCounterFullName = userProfile.effectiveFullName(),
        authenticatedCounterUserId = userProfile.userId,
        authenticatedReviewerFullName = userProfile.effectiveFullName(),
        authenticatedReviewerUserId = userProfile.userId,
        markAsReviewed = false,
        explicitDateEdit = explicitDateEdited
      )
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .testTag("manual_observation_form_screen")
  ) {
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = 4.dp
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("manual_obs_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
          Column {
            Text(
              text = "Unregistered Asset Observation (Not in FAR)",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Observation ID: $generatedObsId • Unknown fields may be left blank",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
        ResultStatusBadge(
          result = if (existing?.isDraft == true) "Draft" else "Not in FAR"
        )
      }
    }

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Classification Notice Banner
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = StatusWarningBg)
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "CLASSIFICATION: UNREGISTERED ASSET OBSERVATION (NOT IN FAR)",
              style = MaterialTheme.typography.labelLarge,
              color = StatusWarningFg,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "This record is stored separately from the original Fixed Asset Register. It is NOT automatically classified as a confirmed client-owned fixed asset; ownership and accounting classification require subsequent review.",
              style = MaterialTheme.typography.bodyMedium,
              color = StatusWarningFg
            )
          }
        }
      }

      // Section 1: Identification & Core Observation Fields
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "1. IDENTIFICATION & ASSET DESCRIPTION",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )
            Text(
              text = "Minimum required to save: Observation ID ($generatedObsId) plus either an Asset Description, Photo attachment, or known Asset ID/Tag. Leave any unknown field blank.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
              value = assetIdIfKnown,
              onValueChange = { assetIdIfKnown = it },
              label = { Text("Asset ID (If Known — Leave Blank if Unknown)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_obs_asset_id_input")
            )

            OutlinedTextField(
              value = description,
              onValueChange = { description = it },
              label = { Text("Asset Description (Required unless Photo or Asset ID is provided)") },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_obs_description_input")
            )

            AuditDropdownField(
              label = "Probable Asset Class (Optional)",
              selectedValue = assetClass,
              options = AuditDropdownOptions.PROBABLE_ASSET_CLASSES,
              onValueSelected = { assetClass = it },
              supportOther = true,
              includeSpecialOptionalStates = true,
              testTag = "manual_obs_class_dropdown"
            )
          }
        }
      }

      // Section 2: Physical Observation Details (All Optional if Unknown)
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "2. PHYSICAL COUNTING & TAG DETAILS (SKIP IF UNAVAILABLE)",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )

            // Counted By (Auto-populated from authenticated profile displayName)
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Column {
                    Text(
                      text = "Counted By (Auto from Authenticated Profile)",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                      text = displayedCountedBy,
                      style = MaterialTheme.typography.bodyLarge,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
                Text(
                  text = "UID: ${displayedCountedByUid.take(10)}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }

            // Count Date (Auto on create, preserved on reopen, explicit edit option)
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedTextField(
                value = countDate,
                onValueChange = {
                  if (isEditingCountDate) {
                    countDate = it
                    explicitDateEdited = true
                  }
                },
                readOnly = !isEditingCountDate,
                label = { Text("Count Date (YYYY-MM-DD)") },
                supportingText = {
                  Text(
                    if (explicitDateEdited) "Explicit date change will be recorded in audit history."
                    else if (existing != null) "Original Count Date preserved on reopen."
                    else "Auto-set to current local date."
                  )
                },
                singleLine = true,
                modifier = Modifier
                  .weight(1f)
                  .testTag("manual_obs_count_date_input")
              )
              OutlinedButton(
                onClick = { isEditingCountDate = !isEditingCountDate },
                modifier = Modifier.testTag("manual_obs_edit_date_button")
              ) {
                Icon(Icons.Default.EditCalendar, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isEditingCountDate) "Lock" else "Edit Date")
              }
            }

            AuditDropdownField(
              label = "Found? (Optional)",
              selectedValue = found,
              options = AuditDropdownOptions.FOUND_OPTIONS,
              onValueSelected = { found = it },
              testTag = "manual_obs_found_dropdown"
            )

            OutlinedTextField(
              value = physicalQtyText,
              onValueChange = { physicalQtyText = it },
              label = { Text("Physical Qty (Optional — Non-negative whole number if known)") },
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_obs_qty_input")
            )

            AuditDropdownField(
              label = "Condition (Optional)",
              selectedValue = condition,
              options = AuditDropdownOptions.CONDITION_OPTIONS,
              onValueSelected = { condition = it },
              includeSpecialOptionalStates = true,
              testTag = "manual_obs_condition_dropdown"
            )

            AuditDropdownField(
              label = "In Use? (Optional)",
              selectedValue = inUse,
              options = AuditDropdownOptions.IN_USE_OPTIONS,
              onValueSelected = { inUse = it },
              includeSpecialOptionalStates = true,
              testTag = "manual_obs_in_use_dropdown"
            )

            AuditDropdownField(
              label = "Tag Status (Optional)",
              selectedValue = tagStatus,
              options = AuditDropdownOptions.TAG_STATUS_OPTIONS,
              onValueSelected = { tagStatus = it },
              includeSpecialOptionalStates = true,
              testTag = "manual_obs_tag_status_dropdown"
            )

            OutlinedTextField(
              value = tagNumberAcn,
              onValueChange = { tagNumberAcn = it },
              label = { Text("Tag Number (ACN) (Optional — Preserves leading zeros)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_obs_tag_acn_input")
            )

            OutlinedTextField(
              value = brandModel,
              onValueChange = { brandModel = it },
              label = { Text("Brand / Model (Optional)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_obs_brand_input")
            )
          }
        }
      }

      // Section 3: Location & Custodian (All Optional if Unknown)
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "3. LOCATION & CUSTODIAN (OPTIONAL — BLANK / N/A / UNKNOWN ALLOWED)",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )

            AuditDropdownField(
              label = "Building / Site",
              selectedValue = buildingSite,
              options = masterData.siteList(),
              onValueSelected = { buildingSite = it },
              supportOther = true,
              otherValue = buildingSiteOther,
              onOtherValueChange = { buildingSiteOther = it },
              includeSpecialOptionalStates = true,
              searchable = true,
              testTag = "manual_obs_site_dropdown"
            )

            AuditDropdownField(
              label = "Floor",
              selectedValue = floor,
              options = masterData.floorList(),
              onValueSelected = { floor = it },
              supportOther = true,
              otherValue = floorOther,
              onOtherValueChange = { floorOther = it },
              includeSpecialOptionalStates = true,
              searchable = true,
              testTag = "manual_obs_floor_dropdown"
            )

            AuditDropdownField(
              label = "Room / Location",
              selectedValue = roomLocation,
              options = filteredRooms,
              onValueSelected = { roomLocation = it },
              supportOther = true,
              otherValue = roomLocationOther,
              onOtherValueChange = { roomLocationOther = it },
              includeSpecialOptionalStates = true,
              searchable = true,
              testTag = "manual_obs_room_dropdown"
            )

            AuditDropdownField(
              label = "Department",
              selectedValue = department,
              options = masterData.departmentList(),
              onValueSelected = { department = it },
              supportOther = true,
              otherValue = departmentOther,
              onOtherValueChange = { departmentOther = it },
              includeSpecialOptionalStates = true,
              searchable = true,
              testTag = "manual_obs_dept_dropdown"
            )

            OutlinedTextField(
              value = userCustodian,
              onValueChange = { userCustodian = it },
              label = { Text("User / Custodian (Optional)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_obs_custodian_input")
            )
          }
        }
      }

      // Section 4: Photo Evidence, Remarks & Client Explanation
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = "4. CAMERA PHOTO EVIDENCE, REMARKS & FOLLOW-UP",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary
            )

            PhotoEvidenceSection(
              recordKey = generatedObsId,
              photoRef = photoRef,
              userProfile = userProfile,
              onPhotoRefChange = { photoRef = it }
            )

            OutlinedTextField(
              value = remarks,
              onValueChange = { remarks = it },
              label = { Text("Remarks (Optional)") },
              minLines = 3,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_obs_remarks_input")
            )

            OutlinedTextField(
              value = clientExplanation,
              onValueChange = { clientExplanation = it },
              label = { Text("Client Explanation / Follow-up (Optional)") },
              minLines = 3,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("manual_obs_explanation_input")
            )
          }
        }
      }

      // Validation & Save Actions
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            if (attemptedSubmit && !liveValidation.isValidToSave) {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = StatusWarningBg,
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(
                  modifier = Modifier.padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusWarningFg)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = "Cannot Save Yet — Unsaved Form Data Preserved:",
                      style = MaterialTheme.typography.labelLarge,
                      color = StatusWarningFg,
                      fontWeight = FontWeight.Bold
                    )
                  }
                  liveValidation.validationErrors.forEach { err ->
                    Text("• $err", style = MaterialTheme.typography.bodyMedium, color = StatusWarningFg)
                  }
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              OutlinedButton(
                onClick = {
                  attemptedSubmit = true
                  if (liveValidation.isValidToSave) {
                    onSaveObservation(draftRecord.copy(isDraft = true), true, false, explicitDateEdited)
                  }
                },
                modifier = Modifier
                  .weight(1f)
                  .height(50.dp)
                  .testTag("manual_obs_save_draft_button"),
                shape = RoundedCornerShape(12.dp)
              ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Draft")
              }

              Button(
                onClick = {
                  attemptedSubmit = true
                  if (liveValidation.isValidToSave) {
                    onSaveObservation(draftRecord.copy(isDraft = false), false, false, explicitDateEdited)
                  }
                },
                modifier = Modifier
                  .weight(1.3f)
                  .height(50.dp)
                  .testTag("manual_obs_save_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AuditNavyPrimary)
              ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Observation")
              }
            }

            if (existing != null) {
              Button(
                onClick = {
                  attemptedSubmit = true
                  if (liveValidation.isValidToSave) {
                    onSaveObservation(draftRecord.copy(isDraft = false), false, true, explicitDateEdited)
                  }
                },
                modifier = Modifier
                  .fillMaxWidth()
                  .height(48.dp)
                  .testTag("manual_obs_review_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AuditNavySecondary)
              ) {
                Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Review Observation as ${userProfile.effectiveDisplayName()}")
              }
            }

            if (!existing?.auditTrailSummary.isNullOrBlank()) {
              HorizontalDivider()
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Observation Audit History", style = MaterialTheme.typography.labelLarge)
              }
              Text(
                text = existing!!.auditTrailSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      }
    }
  }
}
