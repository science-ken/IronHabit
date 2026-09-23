package com.ironhabit.app.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.CategoryPieChart
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.HeatmapGrid
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.TrendChart
import com.ironhabit.app.ui.theme.heatmapLevels
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 「训练统计」页：打卡柱状 + 类型占比 + 热力图，三块共用顶部那一个区间。
 *
 * 这三块是从「我的」页首屏搬下来的 —— 它们属于"按月回顾才看"的低频数据，
 * 留在首屏就把那一屏撑成三屏。搬过来只换了页面，没换数据源。
 *
 * 复用 `AppRoot` 的 `Scaffold`（顶栏标题与返回箭头都由路由给）。
 */
@Composable
fun TrainingStatsScreen(
    modifier: Modifier = Modifier,
    viewModel: TrainingStatsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
    ) {
        // 普通本地 val：委托属性不支持智能转换，需先取出再在分支内使用。
        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                LoadingSkeleton()
                LoadingSkeleton()
                LoadingSkeleton()
            }

            errorRes != null -> EmptyState(
                text = stringResource(errorRes),
                actionText = stringResource(R.string.action_retry),
                onAction = viewModel::onRetry,
            )

            else -> {
                val activeDays: Int = uiState.trend.count { point -> point.count > 0 }
                val peak: Int = uiState.trend.maxOfOrNull { point -> point.count } ?: 0

                RangeSelector(
                    selected = uiState.days,
                    activeDays = activeDays,
                    totalDays = uiState.days,
                    onSelect = viewModel::onRangeSelected,
                )

                ChartCard(
                    title = stringResource(R.string.title_trend_chart),
                    extra = if (peak > 0) {
                        stringResource(R.string.label_peak_count, peak)
                    } else {
                        null
                    },
                ) {
                    TrendChart(points = uiState.trend)
                }

                ChartCard(title = stringResource(R.string.title_category_chart)) {
                    CategoryPieChart(shares = uiState.categoryShare)
                }

                ChartCard(title = stringResource(R.string.title_heatmap_chart)) {
                    HeatmapGrid(cells = uiState.heatmap)
                    HeatmapLegend()
                }
            }
        }
    }
}

/** 区间选择（7 / 30 / 90 天）+ 右侧「活跃 N / M 天」。 */
@Composable
private fun RangeSelector(
    selected: Int,
    activeDays: Int,
    totalDays: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        TrainingStatsViewModel.RANGE_DAYS.forEach { days ->
            FilterChip(
                selected = days == selected,
                onClick = { onSelect(days) },
                label = { Text(text = stringResource(R.string.label_range_days, days)) },
            )
        }
        Text(
            text = stringResource(R.string.label_active_days, activeDays, totalDays),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = IronHabitSpacing.xs),
        )
    }
}

/** 一张图一卡：标题（可带右上角的一个数）+ 内容。 */
@Composable
private fun ChartCard(
    title: String,
    modifier: Modifier = Modifier,
    extra: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (extra != null) {
                    Text(
                        text = extra,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            content()
        }
    }
}

/** 热力图的密度图例：`少 ▫▫▫▫▫ 多`。 */
@Composable
private fun HeatmapLegend(modifier: Modifier = Modifier) {
    val levels: List<Color> = heatmapLevels()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.label_heatmap_less),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        levels.forEach { level ->
            Box(
                modifier = Modifier
                    .size(LEGEND_CELL)
                    .clip(IronHabitShapes.cell)
                    .background(level),
            )
        }
        Text(
            text = stringResource(R.string.label_heatmap_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 图例色块边长：组件固有尺寸（与 `HeatmapGrid` 的格子同档），非布局间距。 */
private val LEGEND_CELL = 12.dp
