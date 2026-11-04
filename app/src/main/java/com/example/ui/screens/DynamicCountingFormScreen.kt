package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AssetWithCount
import com.example.data.model.UserProfile
import com.example.data.network.DynamicSchemaDto
import com.example.data.network.FarAssetBaselineDto
import com.example.data.network.WorkerApiResult
import com.example.data.offline.DynamicSubmissionRecord
import com.example.data.offline.DynamicSyncState
import com.example.data.repository.DynamicOfflineCacheRepository
import com.example.data.repository.DynamicSyncRepository
import com.example.data.repository.DynamicSyncResult
import com.example.ui.components.DynamicFormRenderer
import com.example.ui.components.DynamicFormState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern Dynamic Counting Verification Form Screen.
 * Fully integrates [DynamicFormRenderer] with the auditor workflow:
 * - Pin-resolves schema for the selected Client and Audit Cycle.
 * - Retrieves and displays complete FAR baseline data without hardcoding business fields.
 * - Preserves leading-zero string Asset IDs across all operations.
 * - Enforces durable Room persistence before network transmission.
 * - Preserves user-entered values on network interruptions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicCountingFormScreen(
    clientId: String,
    cycleId: String,
    assetWithCount: AssetWithCount,
    userProfile: UserProfile,
    syncRepository: DynamicSyncRepository,
    cacheRepository: DynamicOfflineCacheRepository,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()

    val far = assetWithCount.farAsset
    val assetKey = far.assetKey
    val assetIdText = far.assetId // Strict string, preserving leading zeroes

    var formState by remember { mutableStateOf<DynamicFormState>(DynamicFormState.Loading) }
    var loadedSchema by remember { mutableStateOf<DynamicSchemaDto?>(null) }
    var loadedBaseline by remember { mutableStateOf<FarAssetBaselineDto?>(null) }

    val enteredValues = remember { mutableStateMapOf<String, Any?>() }
    val validationErrors = remember { mutableStateMapOf<String, String>() }

    fun loadFormContext(forceNetwork: Boolean = false) {
        scope.launch {
            formState = DynamicFormState.Loading

            // 1. Resolve schema pinned to Cycle
            val schemaRes = cacheRepository.getSchemaForCycle(clientId, cycleId, forceRefresh = forceNetwork)
            val schema = (schemaRes as? WorkerApiResult.Success)?.data

            if (schema == null) {
                formState = DynamicFormState.Uninitialized(
                    "Dynamic Schema not found or uninitialized for Client: '$clientId', Cycle: '$cycleId'. Contact Admin to publish a Master Input Sheet."
                )
                return@launch
            }
            loadedSchema = schema

            // 2. Resolve FAR baseline (offline-first)
            val baselineRes = cacheRepository.getFarAsset(clientId, cycleId, assetKey, forceRefresh = forceNetwork)
            var baseline = (baselineRes as? WorkerApiResult.Success)?.data

            if (baseline == null) {
                // Fallback to locally loaded FAR asset attributes if Worker API asset record is not yet synced
                val fallbackData = mutableMapOf<String, String>()
                fallbackData["Asset ID"] = assetIdText
                fallbackData["Asset Description"] = far.assetDescription
                fallbackData["Location"] = far.location
                fallbackData["Department"] = far.department
                fallbackData["Asset Class"] = far.assetClass
                baseline = FarAssetBaselineDto(
                    assetKey = assetKey,
                    assetId = assetIdText,
                    clientId = clientId,
                    fiscalYearId = cycleId,
                    sourceRow = far.sourceRow,
                    rawRowData = fallbackData
                )
            }
            loadedBaseline = baseline

            // Verify baseline completeness against schema requirements
            // Never silently accept partial legacy FARAsset fallback when arbitrary columns are required
            val missingRequiredColumns = mutableListOf<String>()
            for (field in schema.fields) {
                val isImported = field.inputMode in listOf("FIXED_IMPORTED", "READ_ONLY", "FIXED_MASTER_DATA", "FIXED")
                if (isImported) {
                    val colKey = field.originalHeader?.takeIf { it.isNotBlank() } ?: field.fieldKey
                    val hasCol = baseline.rawRowData.containsKey(colKey) ||
                                 baseline.rawRowData.containsKey(field.displayLabel) ||
                                 baseline.rawRowData.containsKey(field.fieldKey)
                    if (!hasCol && field.isRequired) {
                        missingRequiredColumns.add(field.displayLabel.ifBlank { colKey })
                    }
                }
            }

            if (missingRequiredColumns.isNotEmpty()) {
                formState = DynamicFormState.IncompleteBaseline(
                    message = "Complete FAR master baseline is required for this asset. The schema requires master columns missing from the offline cache: ${missingRequiredColumns.joinToString(", ")}. Please connect to network to download the complete baseline.",
                    missingColumns = missingRequiredColumns
                )
                return@launch
            }

            // 3. Prepopulate existing saved submission values if present
            val existing = syncRepository.getSubmission("$clientId:$cycleId:$assetKey")
            if (existing != null) {
                existing.fieldValues.forEach { (k, v) ->
                    enteredValues[k] = v
                }
                formState = DynamicFormState.OfflineStatus(
                    status = existing.syncStatus,
                    message = existing.lastErrorMessage,
                    fields = schema.fields,
                    farBaseline = baseline.rawRowData
                )
            } else {
                formState = DynamicFormState.Ready(
                    fields = schema.fields,
                    farBaseline = baseline.rawRowData
                )
            }
        }
    }

    LaunchedEffect(clientId, cycleId, assetKey) {
        loadFormContext()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dynamic_counting_form_screen")
    ) {
        // Sticky Header with Asset ID (text), Client, Cycle, and Back Navigation
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("back_to_list_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Asset $assetIdText",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Client: $clientId • Cycle: $cycleId",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { loadFormContext(forceNetwork = true) },
                    modifier = Modifier.testTag("refresh_schema_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Schema")
                }
            }
        }

        // Dynamic Form Renderer with arbitrary field count support
        DynamicFormRenderer(
            state = formState,
            enteredValues = enteredValues,
            validationErrors = validationErrors,
            assetId = assetIdText,
            userProfile = userProfile,
            onValueChange = { fieldId, value ->
                enteredValues[fieldId] = value
                validationErrors.remove(fieldId)
            },
            onSubmit = {
                val currentSchema = loadedSchema
                val currentBaseline = loadedBaseline

                // Prevent submission when required schema or baseline information is unavailable
                if (currentSchema == null || currentBaseline == null) {
                    formState = DynamicFormState.Error(
                        message = "Cannot submit: Schema or FAR Baseline information is unavailable.",
                        fields = loadedSchema?.fields,
                        farBaseline = loadedBaseline?.rawRowData
                    )
                } else {
                    // Client-side schema validation
                    validationErrors.clear()
                    for (field in currentSchema.fields) {
                        if (field.isVisible && field.isRequired && field.inputMode != "FIXED_IMPORTED") {
                            val v = enteredValues[field.fieldId]
                            if (v == null || (v is String && v.isBlank()) || (v is List<*> && v.isEmpty())) {
                                validationErrors[field.fieldId] = "${field.displayLabel.ifBlank { field.fieldKey }} is required."
                            }
                        }
                    }

                    if (validationErrors.isEmpty()) {
                        // Execute persistence-before-network sequence
                        scope.launch {
                            formState = DynamicFormState.Submitting(currentSchema.fields, currentBaseline.rawRowData)

                            val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

                            val submissionRecord = DynamicSubmissionRecord(
                                clientId = clientId,
                                auditCycleId = cycleId,
                                assetKey = assetKey,
                                assetId = assetIdText, // Strictly preserved as TEXT with leading zeroes
                                schemaVersionId = currentSchema.schemaVersionId,
                                countDate = todayIso,
                                fieldValues = enteredValues.toMap(),
                                auditorUid = userProfile.userId,
                                auditorDisplayName = userProfile.effectiveDisplayName()
                            )

                            val syncResult = syncRepository.saveAndSubmit(submissionRecord, attemptImmediateNetwork = true)

                            when (syncResult) {
                                is DynamicSyncResult.SyncedOnline -> {
                                    formState = DynamicFormState.Success(
                                        recordId = syncResult.recordId,
                                        isReplay = syncResult.isReplay
                                    )
                                }

                                is DynamicSyncResult.SavedOffline -> {
                                    formState = DynamicFormState.OfflineStatus(
                                        status = syncResult.status,
                                        message = "Saved locally. WorkManager will synchronize when connected.",
                                        fields = currentSchema.fields,
                                        farBaseline = currentBaseline.rawRowData
                                    )
                                }

                                is DynamicSyncResult.SyncFailedRetryable -> {
                                    // Preserves all entered values on temporary network errors!
                                    formState = DynamicFormState.OfflineStatus(
                                        status = DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                                        message = "Saved locally. Sync retry available (${syncResult.message})",
                                        fields = currentSchema.fields,
                                        farBaseline = currentBaseline.rawRowData
                                    )
                                }

                                is DynamicSyncResult.ActionRequired -> {
                                    formState = DynamicFormState.OfflineStatus(
                                        status = syncResult.status,
                                        message = syncResult.message,
                                        fields = currentSchema.fields,
                                        farBaseline = currentBaseline.rawRowData
                                    )
                                }

                                is DynamicSyncResult.DatabaseUnavailable -> {
                                    formState = DynamicFormState.Error(
                                        message = syncResult.message,
                                        fields = currentSchema.fields,
                                        farBaseline = currentBaseline.rawRowData
                                    )
                                }
                            }
                        }
                    }
                }
            },
            onRetry = {
                // Re-attempt synchronization of the current form
                val currentSchema = loadedSchema
                val currentBaseline = loadedBaseline
                if (currentSchema != null && currentBaseline != null) {
                    scope.launch {
                        formState = DynamicFormState.Submitting(currentSchema.fields, currentBaseline.rawRowData)
                        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        val submissionRecord = DynamicSubmissionRecord(
                            clientId = clientId,
                            auditCycleId = cycleId,
                            assetKey = assetKey,
                            assetId = assetIdText,
                            schemaVersionId = currentSchema.schemaVersionId,
                            countDate = todayIso,
                            fieldValues = enteredValues.toMap(),
                            auditorUid = userProfile.userId,
                            auditorDisplayName = userProfile.effectiveDisplayName()
                        )
                        val res = syncRepository.saveAndSubmit(submissionRecord, attemptImmediateNetwork = true)
                        when (res) {
                            is DynamicSyncResult.SyncedOnline -> formState = DynamicFormState.Success(res.recordId, res.isReplay)
                            is DynamicSyncResult.DatabaseUnavailable -> formState = DynamicFormState.Error(
                                message = res.message,
                                fields = currentSchema.fields,
                                farBaseline = currentBaseline.rawRowData
                            )
                            else -> formState = DynamicFormState.OfflineStatus(
                                status = (res as? DynamicSyncResult.ActionRequired)?.status ?: DynamicSyncState.SYNC_FAILED_RETRY_AVAILABLE,
                                message = (res as? DynamicSyncResult.ActionRequired)?.message ?: (res as? DynamicSyncResult.SyncFailedRetryable)?.message,
                                fields = currentSchema.fields,
                                farBaseline = currentBaseline.rawRowData
                            )
                        }
                    }
                } else {
                    loadFormContext(forceNetwork = true)
                }
            }
        )
    }
}
