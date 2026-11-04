package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AssetWithCount
import com.example.data.model.VerificationResultStatus
import com.example.data.repository.AuditRepository
import com.example.ui.common.UiState
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AdminDashboardMetrics(
    val totalAssets: Int = 0,
    val countedAssets: Int = 0,
    val remainingAssets: Int = 0,
    val pendingReviewRecords: Int = 0,
    val submittedToday: Int = 0
)

class AdminDashboardViewModel(
    private val repository: AuditRepository,
    private val currentUserId: String,
    private val clientId: String = "",
    private val fiscalYearId: String = ""
) : ViewModel() {

    val metrics: StateFlow<UiState<AdminDashboardMetrics>> = combine(
        repository.observeFarAssets(currentUserId, clientId, fiscalYearId),
        repository.observeCountingRecords(currentUserId, clientId, fiscalYearId),
        repository.observeDailyBatches(currentUserId, clientId, fiscalYearId)
    ) { assets, counts, batches ->
        val countMap = counts.associateBy { it.assetKey }
        val assetsWithCounts = assets.map { asset ->
            AssetWithCount(
                farAsset = asset,
                countingRecord = countMap[asset.assetKey]
            )
        }
        val countable = assetsWithCounts.filter { it.farAsset.sheetOrigin != "Land, AUC & Clearing" }
        val total = countable.size
        val counted = countable.count { it.countingRecord != null && !it.countingRecord.isDraft }
        val pendingReview = countable.count {
            it.countingRecord != null && !it.countingRecord.isDraft &&
                    it.effectiveReviewStatus == VerificationResultStatus.PENDING_REVIEW
        }
        
        val today = java.time.LocalDate.now().toString()
        val submittedToday = batches.filter { it.countingDate == today }.size
        
        UiState.Success(
            AdminDashboardMetrics(
                totalAssets = total,
                countedAssets = counted,
                remainingAssets = total - counted,
                pendingReviewRecords = pendingReview,
                submittedToday = submittedToday
            )
        ) as UiState<AdminDashboardMetrics>
    }.catch { e ->
        emit(UiState.Error(e.message ?: "Failed to load dashboard metrics") as UiState<AdminDashboardMetrics>)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UiState.Loading as UiState<AdminDashboardMetrics>
    )

}
