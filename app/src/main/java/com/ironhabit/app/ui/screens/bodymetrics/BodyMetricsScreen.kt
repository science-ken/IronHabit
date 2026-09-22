package com.ironhabit.app.ui.screens.bodymetrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlinx.datetime.LocalDate

/**
 * 「身体数据」页（P1）：类型选择 + 当前值 + 趋势线（Canvas 手绘，零第三方依赖）+ 录入 + 列表。
 *
 * 复用 `AppRoot` 的 `Scaffold`；趋势线由本文件内 [BodyMetricTrendChart] 绘制（不改动 T04 的 `TrendChart`）。
 */
@Composable
fun BodyMetricsScreen(
    modifier: Modifier = Modifier,
    viewModel: BodyMetricsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current

    // 这一页的删除是**物理 DELETE**（`BodyMetricDao.kt:73`），不像习惯/食物/计划行那样留软删行，
    // 也就没有任何"恢复"这条路 —— 记录是用户一条一条量出来的，误触一下就永久少一个点。
    // 补一道确认，形状与「数据备份」导入、「删除习惯」一致（三处同类动作不该长三种样子）。
    var recordToDelete: BodyMetric? by remember { mutableStateOf(null) }

    val snackbarText: String? = uiState.snackbarRes?.let { res -> stringResource(res) }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onConsumeSnackbar()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
    ) {
        Text(
            text = stringResource(R.string.title_body_metrics),
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
                Text(
                    text = stringResource(R.string.hint_metric_type),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MetricTypePicker(
                    selected = uiState.selectedType,
                    onSelect = viewModel::onSelectType,
                )

                LatestValueCard(latest = uiState.latest, fallbackType = uiState.selectedType)

                BodyMetricTrendChart(records = uiState.records)

                Text(
                    text = stringResource(R.string.title_add_body_metric),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
                ) {
                    OutlinedTextField(
                        value = uiState.valueText,
                        onValueChange = viewModel::onValueChange,
                        label = { Text(text = stringResource(R.string.hint_metric_value)) },
                        isError = uiState.numberErrorRes != null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = uiState.unitText,
                        onValueChange = viewModel::onUnitChange,
                        label = { Text(text = stringResource(R.string.hint_metric_unit)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                uiState.numberErrorRes?.let { res ->
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = viewModel::onAddRecord,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.action_add))
                }

                if (uiState.records.isEmpty()) {
                    EmptyState(text = stringResource(R.string.empty_body_metrics))
                } else {
                    uiState.records.forEach { record ->
                        MetricRecordRow(
                            record = record,
                            onDelete = { recordToDelete = record },
                        )
                    }
                }
            }
        }
    }

    val pending: BodyMetric? = recordToDelete
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { recordToDelete = null },
            title = { Text(text = stringResource(R.string.dialog_delete_body_metric_title)) },
            text = {
                // 把"哪一条"念出来：日期 + 值 + 单位，与行里显示的同形，用户能一眼对上。
                Text(
                    text = stringResource(
                        R.string.dialog_delete_body_metric_message,
                        formatMonthDay(pending.dateEpochDay),
                        "${pending.value} ${pending.unit}",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recordToDelete = null
                        viewModel.onDeleteRecord(pending.id)
                    },
                ) {
                    Text(text = stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { recordToDelete = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 指标类型选择 chips。 */
@Composable
private fun MetricTypePicker(
    selected: BodyMetricType,
    onSelect: (BodyMetricType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        BodyMetricType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(text = stringResource(metricLabelRes(type))) },
            )
        }
    }
}

/** 当前值卡片：显示最新一条；无数据时显示占位文案。 */
@Composable
private fun LatestValueCard(
    latest: BodyMetric?,
    fallbackType: BodyMetricType,
) {
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
                text = stringResource(metricLabelRes(latest?.type ?: fallbackType)),
                style = MaterialTheme.typography.titleMedium,
            )
            if (latest == null) {
                Text(
                    text = stringResource(R.string.empty_body_metrics),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = "${latest.value} ${latest.unit}",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatMonthDay(latest.dateEpochDay),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * 身体数据趋势线（Compose 原生 `Canvas` 手绘折线，0 第三方依赖）。
 *
 * 数据点按日期升序排列，y 轴按最小/最大值归一。
 */
@Composable
private fun BodyMetricTrendChart(
    records: List<BodyMetric>,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val ascending: List<BodyMetric> = remember(records) { records.sortedBy { it.dateEpochDay } }
    val lineColor = colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT),
    ) {
        if (ascending.size < 2) {
            Text(
                text = stringResource(R.string.empty_charts),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val values: List<Float> = ascending.map { it.value }
                val minValue = values.min()
                val maxValue = values.max()
                val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f
                val stepX = size.width / (values.size - 1).toFloat()

                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = stepX * index
                    val y = size.height - (value - minValue) / range * size.height
                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(path = path, color = lineColor, style = Stroke(width = STROKE_WIDTH))
            }
        }
    }
}

/** 单条记录行：日期 + 值 + 删除。 */
@Composable
private fun MetricRecordRow(
    record: BodyMetric,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = IronHabitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatMonthDay(record.dateEpochDay),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${record.value} ${record.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
            )
        }
    }
}

/** 指标类型 → 文案资源。 */
private fun metricLabelRes(type: BodyMetricType): Int = when (type) {
    BodyMetricType.WEIGHT -> R.string.metric_weight
    BodyMetricType.BODY_FAT -> R.string.metric_body_fat
    BodyMetricType.MUSCLE_MASS -> R.string.metric_muscle
    BodyMetricType.WAIST -> R.string.metric_waist
    BodyMetricType.CHEST -> R.string.metric_chest
    BodyMetricType.ARM -> R.string.metric_arm
    BodyMetricType.HIP -> R.string.metric_hip
}

/** `epochDay` → 「M/D」（纯数字，无硬编码中文）。 */
private fun formatMonthDay(epochDay: Long): String {
    val date = LocalDate.fromEpochDays(epochDay.toInt())
    return "${date.monthNumber}/${date.dayOfMonth}"
}

private val CHART_HEIGHT = 140.dp
private const val STROKE_WIDTH = 4f
