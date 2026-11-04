package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.UserProfile
import com.example.data.network.DynamicFieldDto
import com.example.data.network.DynamicFieldOptionDto

/**
 * UI State for the Dynamic Auditor Form.
 */
sealed class DynamicFormState {
    object Loading : DynamicFormState()
    data class Uninitialized(val message: String) : DynamicFormState()
    data class IncompleteBaseline(val message: String, val missingColumns: List<String>) : DynamicFormState()
    data class Ready(val fields: List<DynamicFieldDto>, val farBaseline: Map<String, String>) : DynamicFormState()
    data class Submitting(val fields: List<DynamicFieldDto>, val farBaseline: Map<String, String>) : DynamicFormState()
    data class Success(val recordId: String, val isReplay: Boolean) : DynamicFormState()
    data class Error(val message: String, val fields: List<DynamicFieldDto>?, val farBaseline: Map<String, String>?) : DynamicFormState()
    data class OfflineStatus(
        val status: String,
        val message: String?,
        val fields: List<DynamicFieldDto>,
        val farBaseline: Map<String, String>
    ) : DynamicFormState()
}

/**
 * Reusable Compose Form Renderer for Dynamic Client Schemas.
 * Functions with arbitrary field counts (8, 10, 15, 24, etc.) without static field assumptions.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DynamicFormRenderer(
    state: DynamicFormState,
    enteredValues: Map<String, Any?>,
    validationErrors: Map<String, String>,
    onValueChange: (fieldId: String, value: Any?) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit = {},
    assetId: String = "",
    userProfile: UserProfile? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("dynamic_form_container")
    ) {
        when (state) {
            is DynamicFormState.Loading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading Client Schema & Asset Data...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is DynamicFormState.Uninitialized -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .align(Alignment.Center)
                        .testTag("uninitialized_client_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Dynamic Schema Not Initialized",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.message.ifBlank { "This Client has not yet configured or published a Dynamic Input Schema. The Admin must upload and configure the Master Input Sheet first." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            is DynamicFormState.IncompleteBaseline -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .align(Alignment.Center)
                        .testTag("incomplete_baseline_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Incomplete FAR Master Baseline",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (state.missingColumns.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Missing columns: ${state.missingColumns.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Connect & Download Baseline")
                        }
                    }
                }
            }

            is DynamicFormState.Success -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .align(Alignment.Center)
                        .testTag("submission_success_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Counting Record Submitted!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Record ID: ${state.recordId}", style = MaterialTheme.typography.bodyMedium)
                        if (state.isReplay) {
                            Text("(Idempotent replay detected - record unchanged)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }

            is DynamicFormState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("submission_error_banner"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Submission Error", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            Button(onClick = onRetry) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Retry")
                            }
                        }
                    }

                    if (state.fields != null && state.farBaseline != null) {
                        Spacer(Modifier.height(12.dp))
                        FormFieldsList(
                            fields = state.fields,
                            farBaseline = state.farBaseline,
                            enteredValues = enteredValues,
                            validationErrors = validationErrors,
                            isSubmitting = false,
                            onValueChange = onValueChange,
                            onSubmit = onSubmit,
                            assetId = assetId,
                            userProfile = userProfile
                        )
                    }
                }
            }

            is DynamicFormState.OfflineStatus -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("offline_status_banner"),
                        colors = CardDefaults.cardColors(
                            containerColor = when (state.status) {
                                "Synced" -> MaterialTheme.colorScheme.primaryContainer
                                "Saved Locally", "Waiting for Internet", "Pending Sync" -> MaterialTheme.colorScheme.secondaryContainer
                                "Conflict Requires Review" -> MaterialTheme.colorScheme.tertiaryContainer
                                else -> MaterialTheme.colorScheme.errorContainer
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (state.status) {
                                    "Synced" -> Icons.Default.CheckCircle
                                    "Saved Locally", "Waiting for Internet", "Pending Sync" -> Icons.Default.Info
                                    else -> Icons.Default.ErrorOutline
                                },
                                contentDescription = null,
                                tint = when (state.status) {
                                    "Synced" -> MaterialTheme.colorScheme.primary
                                    "Saved Locally", "Waiting for Internet", "Pending Sync" -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.status,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                if (!state.message.isNullOrBlank()) {
                                    Text(
                                        text = state.message,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Button(onClick = onRetry) {
                                Text("Sync Now")
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    FormFieldsList(
                        fields = state.fields,
                        farBaseline = state.farBaseline,
                        enteredValues = enteredValues,
                        validationErrors = validationErrors,
                        isSubmitting = false,
                        onValueChange = onValueChange,
                        onSubmit = onSubmit,
                        assetId = assetId,
                        userProfile = userProfile
                    )
                }
            }

            is DynamicFormState.Ready, is DynamicFormState.Submitting -> {
                val fields = if (state is DynamicFormState.Ready) state.fields else (state as DynamicFormState.Submitting).fields
                val farBaseline = if (state is DynamicFormState.Ready) state.farBaseline else (state as DynamicFormState.Submitting).farBaseline
                val isSubmitting = state is DynamicFormState.Submitting

                FormFieldsList(
                    fields = fields,
                    farBaseline = farBaseline,
                    enteredValues = enteredValues,
                    validationErrors = validationErrors,
                    isSubmitting = isSubmitting,
                    onValueChange = onValueChange,
                    onSubmit = onSubmit,
                    assetId = assetId,
                    userProfile = userProfile
                )
            }
        }
    }
}

@Composable
private fun FormFieldsList(
    fields: List<DynamicFieldDto>,
    farBaseline: Map<String, String>,
    enteredValues: Map<String, Any?>,
    validationErrors: Map<String, String>,
    isSubmitting: Boolean,
    onValueChange: (fieldId: String, value: Any?) -> Unit,
    onSubmit: () -> Unit,
    assetId: String = "",
    userProfile: UserProfile? = null
) {
    val activeFields = remember(fields) {
        fields
            .filter { it.isVisible && !it.isArchived && it.inputMode != "HIDDEN" }
            .sortedBy { it.sortOrder }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("dynamic_form_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(activeFields, key = { it.fieldId }) { field ->
            DynamicFieldItem(
                field = field,
                farBaseline = farBaseline,
                currentValue = enteredValues[field.fieldId],
                errorMessage = validationErrors[field.fieldId],
                isEnabled = !isSubmitting,
                onValueChange = { newVal -> onValueChange(field.fieldId, newVal) },
                assetId = assetId,
                userProfile = userProfile
            )
        }

        item {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSubmit,
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("submit_count_button")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Submitting Count...")
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Submit Physical Verification")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DynamicFieldItem(
    field: DynamicFieldDto,
    farBaseline: Map<String, String>,
    currentValue: Any?,
    errorMessage: String?,
    isEnabled: Boolean,
    onValueChange: (Any?) -> Unit,
    assetId: String = "",
    userProfile: UserProfile? = null
) {
    val fieldTag = "field_${field.fieldId}"

    when (field.inputMode) {
        "FIXED_IMPORTED", "READ_ONLY", "FIXED_MASTER_DATA", "FIXED" -> {
            // Read-Only Baseline Card displaying imported Master FAR data
            val headerName = field.originalHeader ?: field.displayLabel
            val importedValue = farBaseline[headerName] ?: farBaseline[field.displayLabel] ?: farBaseline[field.fieldKey] ?: "N/A"

            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(fieldTag),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(field.displayLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(importedValue, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        "AUTO" -> {
            // Read-Only System Generated Field
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(fieldTag),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("${field.displayLabel} (System Generated)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        currentValue?.toString() ?: field.autoSourceRule ?: "Auto-calculated at submission",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        "DROPDOWN" -> {
            // Categorical Dropdown Menu
            var expanded by remember { mutableStateOf(false) }
            val selectedText = currentValue?.toString() ?: ""
            val activeOptions = remember(field.options) { field.options.filter { it.isActive } }

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (isEnabled) expanded = !expanded },
                modifier = Modifier.fillMaxWidth().testTag(fieldTag)
            ) {
                OutlinedTextField(
                    value = selectedText,
                    onValueChange = {},
                    readOnly = true,
                    enabled = isEnabled,
                    label = { Text(if (field.isRequired) "${field.displayLabel} *" else field.displayLabel) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    isError = !errorMessage.isNullOrBlank(),
                    supportingText = if (!errorMessage.isNullOrBlank()) { { Text(errorMessage) } } else null,
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, isEnabled).fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    activeOptions.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt.optionLabel) },
                            onClick = {
                                onValueChange(opt.optionValue)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        "MANUAL" -> {
            when (field.dataType) {
                "BOOLEAN" -> {
                    val checked = (currentValue as? Boolean) ?: false
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag(fieldTag),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (field.isRequired) "${field.displayLabel} *" else field.displayLabel, style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = checked,
                            onCheckedChange = { onValueChange(it) },
                            enabled = isEnabled
                        )
                    }
                }

                "MULTI_SELECT" -> {
                    val currentList = (currentValue as? List<*>)?.map { it.toString() } ?: emptyList()
                    val activeOptions = remember(field.options) { field.options.filter { it.isActive } }

                    Column(modifier = Modifier.fillMaxWidth().testTag(fieldTag)) {
                        Text(if (field.isRequired) "${field.displayLabel} *" else field.displayLabel, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            activeOptions.forEach { opt ->
                                val isSelected = currentList.contains(opt.optionValue)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (isEnabled) {
                                            val updated = if (isSelected) currentList - opt.optionValue else currentList + opt.optionValue
                                            onValueChange(updated)
                                        }
                                    },
                                    label = { Text(opt.optionLabel) },
                                    enabled = isEnabled
                                )
                            }
                        }
                        if (!errorMessage.isNullOrBlank()) {
                            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }

                "PHOTO_EVIDENCE" -> {
                    val photoVal = (currentValue as? String).orEmpty()
                    val resolvedRecordKey = assetId.ifBlank { photoVal.ifBlank { field.fieldKey } }
                    PhotoEvidenceSection(
                        recordKey = resolvedRecordKey,
                        photoRef = photoVal,
                        onPhotoRefChange = { onValueChange(it) },
                        userProfile = userProfile,
                        modifier = Modifier.fillMaxWidth().testTag(fieldTag)
                    )
                }

                else -> {
                    // Standard Text / Numeric / Date Inputs
                    val keyboardType = when (field.dataType) {
                        "INTEGER" -> KeyboardType.Number
                        "DECIMAL" -> KeyboardType.Decimal
                        else -> KeyboardType.Text
                    }

                    OutlinedTextField(
                        value = currentValue?.toString() ?: "",
                        onValueChange = { onValueChange(it) },
                        label = { Text(if (field.isRequired) "${field.displayLabel} *" else field.displayLabel) },
                        isError = !errorMessage.isNullOrBlank(),
                        supportingText = if (!errorMessage.isNullOrBlank()) { { Text(errorMessage) } } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        singleLine = field.dataType != "MULTILINE_TEXT",
                        maxLines = if (field.dataType == "MULTILINE_TEXT") 4 else 1,
                        enabled = isEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(fieldTag)
                    )
                }
            }
        }

        else -> {
            // Graceful Fallback for unknown or unsupported field input mode
            OutlinedTextField(
                value = currentValue?.toString() ?: "",
                onValueChange = { onValueChange(it) },
                label = { Text(field.displayLabel) },
                enabled = isEnabled,
                modifier = Modifier.fillMaxWidth().testTag(fieldTag)
            )
        }
    }
}
