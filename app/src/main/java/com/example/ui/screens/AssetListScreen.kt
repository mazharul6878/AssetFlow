package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.AssetWithCount
import com.example.data.model.AuditDropdownOptions
import com.example.data.model.MasterDataConfig
import com.example.data.model.VerificationResultStatus
import com.example.domain.AuditCalculationEngine
import com.example.ui.components.AuditDropdownField
import com.example.ui.components.ResultStatusBadge
import com.example.ui.theme.AuditNavyPrimary
import com.example.ui.theme.StatusVerifiedBg
import com.example.ui.theme.StatusVerifiedFg
import com.example.ui.theme.StatusWarningBg
import com.example.ui.theme.StatusWarningFg
import com.example.ui.viewmodel.AuditFilterState
import java.util.Locale

@Composable
fun AssetListScreen(
  allAssets: List<AssetWithCount>,
  filteredAssets: List<AssetWithCount>,
  filterState: AuditFilterState,
  masterData: MasterDataConfig,
  onSearchQueryChange: (String) -> Unit,
  onUpdateFilter: (
    assetClass: String?,
    found: String?,
    site: String?,
    floor: String?,
    department: String?,
    countedBy: String?,
    result: String?
  ) -> Unit,
  onResetFilters: () -> Unit,
  onSelectAsset: (AssetWithCount) -> Unit,
  onCreateManualObservation: (searchedAssetId: String) -> Unit
) {
  var showAdvancedFilters by remember { mutableStateOf(false) }

  val countableAssets = remember(allAssets) {
    allAssets.filter { it.farAsset.sheetOrigin != "Land, AUC & Clearing" }
  }

  val searchOutcome = remember(countableAssets, filterState.searchQuery) {
    AuditCalculationEngine.searchFarAssets(countableAssets, filterState.searchQuery)
  }

  val assetClasses = remember(countableAssets) {
    listOf("All") + countableAssets
      .map { it.farAsset.assetClass }
      .filter { it.isNotBlank() }
      .distinct()
  }
  val sites = remember(masterData, countableAssets) {
    (listOf("All") + masterData.siteList() + countableAssets.mapNotNull { it.countingRecord?.resolvedBuildingSite()?.takeIf { s -> s.isNotBlank() } }).distinct()
  }
  val floors = remember(masterData, countableAssets) {
    (listOf("All") + masterData.floorList() + countableAssets.mapNotNull { it.countingRecord?.resolvedFloor()?.takeIf { s -> s.isNotBlank() } }).distinct()
  }
  val departments = remember(masterData, countableAssets) {
    (listOf("All") + masterData.departmentList() + countableAssets.mapNotNull { it.countingRecord?.resolvedDepartment()?.takeIf { s -> s.isNotBlank() } }).distinct()
  }
  val auditors = remember(masterData, countableAssets) {
    (listOf("All") + masterData.auditorList() + countableAssets.mapNotNull { it.countingRecord?.countedBy?.takeIf { s -> s.isNotBlank() } }).distinct()
  }

  val resultQuickTabs = listOf(
    "All",
    VerificationResultStatus.PENDING,
    VerificationResultStatus.DRAFT,
    VerificationResultStatus.PENDING_VERIFICATION,
    VerificationResultStatus.COUNTED,
    VerificationResultStatus.PENDING_REVIEW,
    VerificationResultStatus.REVIEWED,
    VerificationResultStatus.VERIFIED,
    VerificationResultStatus.NOT_FOUND,
    VerificationResultStatus.NOT_ACCESSIBLE,
    VerificationResultStatus.QTY_SHORT,
    VerificationResultStatus.QTY_EXCESS,
    VerificationResultStatus.CONDITION_ISSUE,
    VerificationResultStatus.TAG_ISSUE
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .testTag("asset_list_screen")
  ) {
    // Mandatory Entry Point: Search Asset ID First
    Surface(
      color = MaterialTheme.colorScheme.surface,
      shadowElevation = 3.dp
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = "MANDATORY STEP 1 — SEARCH FIXED ASSET REGISTER BY ASSET ID FIRST",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Bold
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = filterState.searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Enter Asset ID (e.g., FA-10001) or partial ID/Description...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
              if (filterState.searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                  Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
              }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .weight(1f)
              .testTag("asset_search_input")
          )
          IconButton(
            onClick = { showAdvancedFilters = !showAdvancedFilters },
            modifier = Modifier.testTag("toggle_filters_button")
          ) {
            Icon(
              imageVector = Icons.Default.FilterList,
              contentDescription = "Toggle multi-criteria filters",
              tint = if (showAdvancedFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        // Exact Single Match Quick-Action Banner
        if (searchOutcome.exactMatches.size == 1 && !searchOutcome.hasDuplicateExactAssetId) {
          val match = searchOutcome.exactMatches.first()
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = StatusVerifiedBg,
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Exact FAR Match: ${match.farAsset.assetId} (SL #${match.farAsset.sl}, Row ${match.farAsset.sourceRow})",
                  style = MaterialTheme.typography.labelLarge,
                  color = StatusVerifiedFg,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = match.farAsset.assetDescription,
                  style = MaterialTheme.typography.bodyMedium,
                  color = StatusVerifiedFg
                )
              }
              Spacer(modifier = Modifier.width(8.dp))
              Button(
                onClick = { onSelectAsset(match) },
                colors = ButtonDefaults.buttonColors(containerColor = StatusVerifiedFg),
                modifier = Modifier.testTag("open_exact_far_match_button")
              ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Start / Open Count")
              }
            }
          }
        }

        // Duplicate Asset ID Disambiguation Banner (Never Silently Merge!)
        if (searchOutcome.hasDuplicateExactAssetId) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = StatusWarningBg,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("duplicate_asset_id_banner")
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = StatusWarningFg,
                modifier = Modifier.size(22.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Duplicate Asset ID '${searchOutcome.query}' found across ${searchOutcome.exactMatches.size} separate FAR source rows. Select the exact source row below to count (duplicate records are never merged).",
                style = MaterialTheme.typography.bodyMedium,
                color = StatusWarningFg,
                fontWeight = FontWeight.Medium
              )
            }
          }
        }

        // Counting, Review & Result Status Quick Filter Chips
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          resultQuickTabs.forEach { tab ->
            FilterChip(
              selected = filterState.resultFilter == tab,
              onClick = { onUpdateFilter(null, null, null, null, null, null, tab) },
              label = { Text(tab) }
            )
          }
        }

        // Expandable 7-Criteria Filter Panel
        AnimatedVisibility(visible = showAdvancedFilters) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              AuditDropdownField(
                label = "Asset Class",
                selectedValue = filterState.assetClassFilter,
                options = assetClasses,
                onValueSelected = { onUpdateFilter(it, null, null, null, null, null, null) },
                allowBlankSelection = false,
                modifier = Modifier.weight(1f)
              )
              AuditDropdownField(
                label = "Found?",
                selectedValue = filterState.foundFilter,
                options = listOf("All", "Blank") + AuditDropdownOptions.FOUND_OPTIONS,
                onValueSelected = { onUpdateFilter(null, it, null, null, null, null, null) },
                allowBlankSelection = false,
                modifier = Modifier.weight(1f)
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              AuditDropdownField(
                label = "Building / Site",
                selectedValue = filterState.siteFilter,
                options = sites,
                onValueSelected = { onUpdateFilter(null, null, it, null, null, null, null) },
                allowBlankSelection = false,
                modifier = Modifier.weight(1f)
              )
              AuditDropdownField(
                label = "Floor",
                selectedValue = filterState.floorFilter,
                options = floors,
                onValueSelected = { onUpdateFilter(null, null, null, it, null, null, null) },
                allowBlankSelection = false,
                modifier = Modifier.weight(1f)
              )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              AuditDropdownField(
                label = "Department",
                selectedValue = filterState.departmentFilter,
                options = departments,
                onValueSelected = { onUpdateFilter(null, null, null, null, it, null, null) },
                allowBlankSelection = false,
                modifier = Modifier.weight(1f)
              )
              AuditDropdownField(
                label = "Counted By",
                selectedValue = filterState.countedByFilter,
                options = auditors,
                onValueSelected = { onUpdateFilter(null, null, null, null, null, it, null) },
                allowBlankSelection = false,
                modifier = Modifier.weight(1f)
              )
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Showing ${filteredAssets.size} FAR asset record(s)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              OutlinedButton(
                onClick = onResetFilters,
                modifier = Modifier.testTag("reset_filters_button")
              ) {
                Text("Reset All Filters")
              }
            }
          }
        }
      }
    }

    if (filteredAssets.isEmpty()) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        Icon(
          imageVector = Icons.Default.Inventory2,
          contentDescription = null,
          modifier = Modifier.size(56.dp),
          tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (filterState.searchQuery.isNotBlank()) {
          // Requirement 3 Exact Message when searched Asset ID does not exist in imported FAR
          Text(
            text = "Asset ID not found in FAR. Create a new physical asset observation?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("asset_not_found_in_far_prompt")
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Searched query '${filterState.searchQuery.trim()}' did not match any record in the imported Fixed Asset Register. You can record this item as a separate Unregistered Asset Observation (Not in FAR).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(20.dp))
          Button(
            onClick = { onCreateManualObservation(filterState.searchQuery.trim()) },
            modifier = Modifier
              .height(52.dp)
              .testTag("create_manual_observation_button"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AuditNavyPrimary)
          ) {
            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Physical Asset Observation")
          }
        } else {
          Text(
            text = "No matching FAR assets for selected filter",
            style = MaterialTheme.typography.titleMedium
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Reset filters or search an Asset ID to begin physical counting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        items(filteredAssets, key = { it.farAsset.assetKey }) { item ->
          AssetListCard(
            item = item,
            onClick = { onSelectAsset(item) }
          )
        }
      }
    }
  }
}

@Composable
private fun AssetListCard(
  item: AssetWithCount,
  onClick: () -> Unit
) {
  val far = item.farAsset
  val cnt = item.countingRecord

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
      .testTag("asset_card_${far.assetKey}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = far.assetId,
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold
            )
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.surfaceVariant
            ) {
              Text(
                text = "SL #${far.sl} • Row ${far.sourceRow}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
              )
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = far.assetDescription,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
          )
        }
        Spacer(modifier = Modifier.width(8.dp))
        ResultStatusBadge(result = item.effectiveResult)
      }

      // Separate Counting Status & Review Status indicators
      if (cnt != null) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
          ) {
            Text(
              text = "Count State: ${item.effectiveCountingStatus}",
              style = MaterialTheme.typography.labelSmall,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
          }
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (item.effectiveReviewStatus == VerificationResultStatus.REVIEWED) {
              StatusVerifiedBg
            } else {
              MaterialTheme.colorScheme.surfaceVariant
            }
          ) {
            Text(
              text = "Review: ${item.effectiveReviewStatus}",
              style = MaterialTheme.typography.labelSmall,
              color = if (item.effectiveReviewStatus == VerificationResultStatus.REVIEWED) {
                StatusVerifiedFg
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(
          text = "Class: ${far.assetClass}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = "FAR Qty: ${if (far.farQtyPresent) far.farQty.toString() else "MISSING"} | Phys Qty: ${cnt?.physicalQtyText?.ifBlank { "—" } ?: "—"} | Diff: ${cnt?.qtyDiffText?.ifBlank { "—" } ?: "—"}",
          style = MaterialTheme.typography.labelLarge
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Cost: Tk ${String.format(Locale.US, "%,.0f", far.costTk)} • NBV: Tk ${String.format(Locale.US, "%,.0f", far.nbvTk)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.EditNote,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = if (cnt == null) "Start Count" else if (cnt.isDraft) "Resume Draft" else "Edit / Review",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }

      if (cnt != null && (cnt.resolvedBuildingSite().isNotBlank() || cnt.countedBy.isNotBlank())) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = listOf(cnt.resolvedBuildingSite(), cnt.resolvedFloor(), cnt.resolvedRoomLocation())
                  .filter { it.isNotBlank() }
                  .joinToString(" › ")
                  .ifBlank { "Location not specified" },
                style = MaterialTheme.typography.bodyMedium
              )
            }
            if (cnt.countedBy.isNotBlank()) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "Counted: ${cnt.countedBy}${if (cnt.reviewedBy.isNotBlank()) " • Reviewed: ${cnt.reviewedBy}" else ""}",
                  style = MaterialTheme.typography.labelSmall
                )
              }
            }
          }
        }
      }
    }
  }
}
