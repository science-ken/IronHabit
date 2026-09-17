package com.ironhabit.app.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.HeatmapGrid
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate

/**
 * 「打卡历史」页（P0-9）：区间完成率 + 热力图 + 按日期倒序的打卡明细。
 *
 * 复用 T04 的 [HeatmapGrid] / [EmptyState] / [LoadingSkeleton] 组件与 `AppRoot` 的 `Scaffold`。
 */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
    ) {
        Text(
            text = stringResource(R.string.section_history),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 普通本地 val：委托属性不支持智能转换，需先取出再在分支内使用。
        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                LoadingSkeleton()
                LoadingSkeleton()
            }

            errorRes != null -> {
                EmptyState(text = stringResource(errorRes))
            }

            else -> {
                CompletionRateCard(rate = uiState.completionRate)

                SectionTitle(text = stringResource(R.string.title_heatmap))
                HeatmapGrid(cells = uiState.heatmap)

                if (uiState.days.isEmpty()) {
                    EmptyState(text = stringResource(R.string.empty_history))
                } else {
                    uiState.days.forEach { day ->
                        DayHeader(epochDay = day.epochDay)
                        day.items.forEach { item -> HistoryItemRow(item = item) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** 区间完成率卡片。 */
@Composable
private fun CompletionRateCard(rate: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IronHabitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.label_completion_rate),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${rate.roundToInt()}%",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 日期头。 */
@Composable
private fun DayHeader(epochDay: Long) {
    Text(
        text = formatMonthDay(epochDay),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = IronHabitSpacing.xs),
    )
}

/** 单条历史项：动作名 + 组×次（+ 重量）。 */
@Composable
private fun HistoryItemRow(item: HistoryItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = IronHabitSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = item.exerciseName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val weightSuffix = item.weightKg?.let { weight -> " · ${weight}kg" }.orEmpty()
        Text(
            text = "${item.completedSets} × ${item.completedReps}$weightSuffix",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** `epochDay` → 「M/D」（纯数字，无硬编码中文）。 */
private fun formatMonthDay(epochDay: Long): String {
    val date = LocalDate.fromEpochDays(epochDay.toInt())
    return "${date.monthNumber}/${date.dayOfMonth}"
}
