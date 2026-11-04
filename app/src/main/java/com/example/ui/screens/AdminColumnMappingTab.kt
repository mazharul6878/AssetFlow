package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import com.example.data.model.ExcelColumnMappingConfig

@Composable
fun AdminColumnMappingTab(
  columnMapping: ExcelColumnMappingConfig,
  onSaveMapping: (ExcelColumnMappingConfig) -> Unit
) {
  var sheetName by remember(columnMapping) { mutableStateOf(columnMapping.targetSheetName) }
  var headerRowText by remember(columnMapping) { mutableStateOf(columnMapping.headerRowIndex.toString()) }

  var slCol by remember(columnMapping) { mutableStateOf(columnMapping.slColumn) }
  var assetIdCol by remember(columnMapping) { mutableStateOf(columnMapping.assetIdColumn) }
  var descCol by remember(columnMapping) { mutableStateOf(columnMapping.descriptionColumn) }
  var classCol by remember(columnMapping) { mutableStateOf(columnMapping.assetClassColumn) }
  var costCol by remember(columnMapping) { mutableStateOf(columnMapping.costColumn) }
  var deptCol by remember(columnMapping) { mutableStateOf(columnMapping.departmentColumn) }
  var siteCol by remember(columnMapping) { mutableStateOf(columnMapping.siteColumn) }
  var floorCol by remember(columnMapping) { mutableStateOf(columnMapping.floorColumn) }
  var qtyCol by remember(columnMapping) { mutableStateOf(columnMapping.physicalQtyColumn) }
  var condCol by remember(columnMapping) { mutableStateOf(columnMapping.conditionColumn) }
  var tagCol by remember(columnMapping) { mutableStateOf(columnMapping.tagStatusColumn) }
  var inUseCol by remember(columnMapping) { mutableStateOf(columnMapping.inUseColumn) }
  var remarksCol by remember(columnMapping) { mutableStateOf(columnMapping.remarksColumn) }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .testTag("admin_column_mapping_screen"),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text(
                text = "Excel Column Mapping Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = "Map standard asset verification fields to target Excel worksheet columns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Button(
              onClick = {
                val headerRow = headerRowText.toIntOrNull() ?: 1
                onSaveMapping(
                  columnMapping.copy(
                    targetSheetName = sheetName.trim(),
                    headerRowIndex = headerRow,
                    slColumn = slCol.trim(),
                    assetIdColumn = assetIdCol.trim(),
                    descriptionColumn = descCol.trim(),
                    assetClassColumn = classCol.trim(),
                    costColumn = costCol.trim(),
                    departmentColumn = deptCol.trim(),
                    siteColumn = siteCol.trim(),
                    floorColumn = floorCol.trim(),
                    physicalQtyColumn = qtyCol.trim(),
                    conditionColumn = condCol.trim(),
                    tagStatusColumn = tagCol.trim(),
                    inUseColumn = inUseCol.trim(),
                    remarksColumn = remarksCol.trim()
                  )
                )
              },
              modifier = Modifier.testTag("save_column_mapping_button")
            ) {
              Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Save Mapping")
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedTextField(
              value = sheetName,
              onValueChange = { sheetName = it },
              label = { Text("Target Worksheet Name") },
              singleLine = true,
              modifier = Modifier.weight(1.5f)
            )
            OutlinedTextField(
              value = headerRowText,
              onValueChange = { headerRowText = it },
              label = { Text("Header Row (1-indexed)") },
              singleLine = true,
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
    }

    item {
      OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("FAR Read-Only Fields Mapping", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = slCol, onValueChange = { slCol = it }, label = { Text("SL Col") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = assetIdCol, onValueChange = { assetIdCol = it }, label = { Text("Asset ID Col") }, modifier = Modifier.weight(1f))
          }
          OutlinedTextField(value = descCol, onValueChange = { descCol = it }, label = { Text("Asset Description Col") }, modifier = Modifier.fillMaxWidth())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = classCol, onValueChange = { classCol = it }, label = { Text("Asset Class Col") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = costCol, onValueChange = { costCol = it }, label = { Text("Cost Col") }, modifier = Modifier.weight(1f))
          }
        }
      }
    }

    item {
      OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("Physical Observation & Verification Fields Mapping", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = deptCol, onValueChange = { deptCol = it }, label = { Text("Department Col") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = siteCol, onValueChange = { siteCol = it }, label = { Text("Building/Site Col") }, modifier = Modifier.weight(1f))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = floorCol, onValueChange = { floorCol = it }, label = { Text("Floor Col") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = qtyCol, onValueChange = { qtyCol = it }, label = { Text("Physical Qty Col") }, modifier = Modifier.weight(1f))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = condCol, onValueChange = { condCol = it }, label = { Text("Condition Col") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = tagCol, onValueChange = { tagCol = it }, label = { Text("Tag Status Col") }, modifier = Modifier.weight(1f))
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = inUseCol, onValueChange = { inUseCol = it }, label = { Text("In-Use Col") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = remarksCol, onValueChange = { remarksCol = it }, label = { Text("Remarks Col") }, modifier = Modifier.weight(1f))
          }
        }
      }
    }
  }
}
