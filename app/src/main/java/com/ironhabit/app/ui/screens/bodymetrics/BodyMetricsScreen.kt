package com.ironhabit.app.ui.screens.bodymetrics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
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
import com.ironhabit.app.ui.components.formatMonthDay
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 「身体数据」页：类型选择 + 当前值（含条数）+ 趋势线 + 「记一笔」+ 历史记录。
 *
 * 页内**没有**大标题 —— 顶栏标题由路由给（`AppRoot.titleResFor`），以前这里同一屏
 * 上下各写了一遍「身体数据」。
 * 录入表单收在 [AddBodyMetricSheet] 底部弹层里：这一页的主体是"读自己量过什么"，
 * 填数是一次性动作，不该占掉趋势图的位置。
 *
 * 趋势线由本文件内 [BodyMetricTrendChart] 绘制（Compose 原生 `Canvas`，零第三方依赖，
 * 不改动 T04 的 `TrendChart`）。
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

    /** 录入表单收在底部弹层里：这一页的主体是"读"，"记"是一次性的动作。 */
    var showAddSheet by remember { mutableStateOf(false) }

    val snackbarText: String? = uiState.snackbarRes?.let { res -> stringResource(res) }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onConsumeSnackbar()
            // 写成功了（或表单被判非法）才关弹层：非法时留着，让用户看见错误并改。
            if (uiState.numberErrorRes == null) showAddSheet = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
    ) {
        // 页内**不**再写一遍「身体数据」大标题：顶栏标题由路由给（`AppRoot.titleResFor`），
        // 以前同一屏上下出现两次同名标题。
        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                LoadingSkeleton()
                LoadingSkeleton()
                // 录入入口在加载/错误时也在：这一页不能变成走不通的死路。
                AddRecordRow(onClick = { showAddSheet = true })
            }

            errorRes != null -> {
                EmptyState(text = stringResource(errorRes))
                AddRecordRow(onClick = { showAddSheet = true })
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

                LatestValueCard(
                    latest = uiState.latest,
                    recordCount = uiState.records.size,
                    fallbackType = uiState.selectedType,
                )

                BodyMetricTrendChart(records = uiState.records)

                AddRecordRow(onClick = { showAddSheet = true })

                Text(
                    text = stringResource(R.string.title_body_metric_history),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

    if (showAddSheet) {
        AddBodyMetricSheet(
            valueText = uiState.valueText,
            unitText = uiState.unitText,
            numberErrorRes = uiState.numberErrorRes,
            typeLabel = stringResource(metricLabelRes(uiState.selectedType)),
            onValueChange = viewModel::onValueChange,
            onUnitChange = viewModel::onUnitChange,
            onSubmit = viewModel::onAddRecord,
            onDismiss = { showAddSheet = false },
        )
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

/**
 * 「记一笔」入口行：录入表单搬进底部弹层之后，页面上只留这一行。
 *
 * 右侧那句列出常用指标名，是为了让"点下去要填什么"在点之前就知道 ——
 * 一个光板的「+」号承担不了这个信息量。
 */
@Composable
private fun AddRecordRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(IronHabitShapes.card)
            .background(colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(IronHabitSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.action_add_body_metric),
            style = MaterialTheme.typography.titleSmall,
            color = colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.hint_add_body_metric_types),
            style = MaterialTheme.typography.labelMedium,
            color = colorScheme.primary,
        )
    }
}

/**
 * 录入弹层：数值 + 单位两格，默认单位跟着上面选中的指标走。
 *
 * 表单状态与校验全在 `BodyMetricsViewModel`（`valueText` / `unitText` / `numberErrorRes`），
 * 这里只负责渲染 —— 与改版前的内联表单同一套逻辑，只是换了个位置。
 * 非法时弹层不关（见调用处对 `numberErrorRes` 的判断），否则用户连改的机会都没有。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBodyMetricSheet(
    valueText: String,
    unitText: String,
    numberErrorRes: Int?,
    typeLabel: String,
    onValueChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = IronHabitSpacing.xl)
                .padding(bottom = IronHabitSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            Text(
                text = "$typeLabel · " + stringResource(R.string.title_add_body_metric),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
            ) {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = onValueChange,
                    label = { Text(text = stringResource(R.string.hint_metric_value)) },
                    isError = numberErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = unitText,
                    onValueChange = onUnitChange,
                    label = { Text(text = stringResource(R.string.hint_metric_unit)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            numberErrorRes?.let { res ->
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.action_add))
            }
        }
    }
}

/**
 * 当前值卡片：这一类指标的最新一条 + 一共记了几条；无数据时显示占位文案。
 *
 * 「共 N 条」放在这里而不是别处：它是"这项我坚持量了多久"的直接答案，
 * 也是下面那张趋势图为什么画得出来（或画不出来）的解释。
 */
@Composable
private fun LatestValueCard(
    latest: BodyMetric?,
    recordCount: Int,
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
                val dateText = stringResource(
                    R.string.label_body_metric_records,
                    recordCount,
                ) + " · " + formatMonthDay(latest.dateEpochDay)
                Text(
                    text = dateText,
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
            // 不用 `empty_charts`：那句写的是"继续打卡"，而这一页记的是体重/体脂，
            // 跟打卡无关；而且一个点的问题不是"数据少"，是"两个点才连得成线"。
            Text(
                text = stringResource(R.string.empty_body_metric_chart),
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

private val CHART_HEIGHT = 140.dp
private const val STROKE_WIDTH = 4f
