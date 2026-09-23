package com.ironhabit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.TrendPoint
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlinx.datetime.LocalDate

/**
 * 近 N 天打卡趋势柱状图（**Compose 原生 `Canvas` 手绘，零第三方依赖**）。
 *
 * - x 轴按 [points] 顺序等宽分列，y 轴按最大值归一；
 * - `max == 0`（或空数据）时画一条基线并居中提示 `empty_charts`；
 * - 底部标出首 / 中 / 末三点的「M/D」文本（数字格式，不含中文）。
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val maxCount: Int = remember(points) { points.maxOfOrNull { it.count } ?: 0 }
    val isEmpty: Boolean = points.isEmpty() || maxCount == 0

    val barColor: Color = colorScheme.primary
    val baselineColor: Color = colorScheme.outline

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 基线
            drawLine(
                color = baselineColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = BASELINE_WIDTH,
            )

            if (isEmpty) {
                // 空数据：单色基线之外不绘柱
                return@Canvas
            }

            val slotWidth: Float = size.width / points.size.toFloat()
            val barWidth: Float = (slotWidth * BAR_WIDTH_FRACTION).coerceAtLeast(MIN_BAR_WIDTH)
            val chartHeight: Float = size.height - BASELINE_WIDTH

            points.forEachIndexed { index, point ->
                val fraction: Float = point.count.toFloat() / maxCount.toFloat()
                val barHeight: Float = (chartHeight * fraction).coerceAtLeast(0f)
                val left: Float = slotWidth * index + (slotWidth - barWidth) / 2f
                val top: Float = chartHeight - barHeight
                if (barHeight > 0f) {
                    drawRect(
                        color = barColor,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, barHeight),
                    )
                }
            }
        }

        if (isEmpty) {
            Text(
                text = stringResource(R.string.empty_charts),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }

    if (points.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = IronHabitSpacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AxisLabel(points.first().epochDay)
            if (points.size > 2) {
                AxisLabel(points[points.size / 2].epochDay)
            }
            AxisLabel(points.last().epochDay)
        }
    }
}

/** 轴标签：`epochDay` → 「M/D」文本。 */
@Composable
private fun AxisLabel(epochDay: Long) {
    Text(
        text = formatMonthDay(epochDay),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * `epochDay` → 本地日期「M/D」（纯数字，避免硬编码中文本地化文案）。
 *
 * 全工程**只有这一份**：以前它在 `TrendChart` / `PlanDateStrip` / `BodyMetricsScreen` /
 * `ExerciseDetailScreen` / `HistoryScreen` / `TrainScreen` 六个文件里各有一份逐字相同的
 * 私有副本（改一处漏五处），J 第 5 刀收拢到此。
 */
internal fun formatMonthDay(epochDay: Long): String {
    val date = LocalDate.fromEpochDays(epochDay.toInt())
    return "${date.monthNumber}/${date.dayOfMonth}"
}

private val CHART_HEIGHT = 160.dp
private const val BASELINE_WIDTH = 2f
private const val BAR_WIDTH_FRACTION = 0.6f
private const val MIN_BAR_WIDTH = 2f
