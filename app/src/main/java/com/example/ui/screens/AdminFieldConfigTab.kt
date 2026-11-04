package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.schema.SchemaFieldConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminFieldConfigTab(
  draftFields: List<SchemaFieldConfig>,
  detectedDropdownOptions: Map<String, List<String>>,
  onUpdateFieldConfig: (SchemaFieldConfig) -> Unit,
  onPublishSchema: (String) -> Unit
) {
  var publishNotes by remember { mutableStateOf("") }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_field_config_screen"),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text(
                "Field Configuration & Dropdown Option Manager",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
              Text(
                "Dynamic configuration with categorical options detected from Master Excel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            OutlinedTextField(
              value = publishNotes,
              onValueChange = { publishNotes = it },
              label = { Text("Schema Release Notes") },
              placeholder = { Text("e.g. Added building wing options") },
              singleLine = true,
              modifier = Modifier.weight(1f)
            )
            Button(
              onClick = {
                onPublishSchema(publishNotes.ifBlank { "Updated field configuration" })
                publishNotes = ""
              },
              modifier = Modifier.testTag("publish_schema_button")
            ) {
              Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Publish")
            }
          }
        }
      }
    }

    // Detected Dropdown Options from Master Excel
    if (detectedDropdownOptions.isNotEmpty()) {
      item {
        ElevatedCard(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Default.Category, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Text(
                "Detected Categorical Dropdown Options (From Master Excel)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
            }
            Text(
              "Distinct options extracted deterministically from mapped categorical columns (Department, Site, Floor, Condition, Tag Status, In-Use).",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            detectedDropdownOptions.forEach { (category, options) ->
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = "$category (${options.size} detected options):",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold
                )
                FlowRow(
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  options.take(15).forEach { opt ->
                    AssistChip(
                      onClick = {},
                      label = { Text(opt, style = MaterialTheme.typography.labelSmall) }
                    )
                  }
                  if (options.size > 15) {
                    AssistChip(
                      onClick = {},
                      label = { Text("+${options.size - 15} more", style = MaterialTheme.typography.labelSmall) }
                    )
                  }
                }
              }
              HorizontalDivider()
            }
          }
        }
      }
    }

    item {
      Text(
        "Configured Form Fields (${draftFields.size})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
      )
    }

    items(draftFields, key = { it.fieldId }) { field ->
      var isMandatory by remember(field) { mutableStateOf<Boolean>(field.isMandatory) }
      var isVisible by remember(field) { mutableStateOf<Boolean>(field.isVisible) }
      var labelInput by remember(field) { mutableStateOf<String>(field.label) }

      OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                "${field.columnHeading} (${field.fieldId})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
              )
              Text(
                "Source: ${field.sourceSheet} | Type: ${field.controlType} | Read-Only FAR: ${field.isReadOnlyFar}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          OutlinedTextField(
            value = labelInput,
            onValueChange = {
              labelInput = it
              onUpdateFieldConfig(field.copy(label = it.trim()))
            },
            label = { Text("Display Label in Count Form") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("Mandatory Field", style = MaterialTheme.typography.bodyMedium)
              Switch(
                checked = isMandatory,
                onCheckedChange = {
                  isMandatory = it
                  onUpdateFieldConfig(field.copy(isMandatory = it))
                }
              )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("Visible in Form", style = MaterialTheme.typography.bodyMedium)
              Switch(
                checked = isVisible,
                onCheckedChange = {
                  isVisible = it
                  onUpdateFieldConfig(field.copy(isVisible = it))
                }
              )
            }
          }
        }
      }
    }
  }
}
