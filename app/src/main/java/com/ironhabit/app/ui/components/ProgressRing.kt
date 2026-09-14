package com.ironhabit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.StreakInfo

/**
 * 今日完成度圆环 + 中央 streak 大字。
 *
 * - 外环轨道用 `colorScheme.surfaceVariant`，进度弧用 `colorScheme.primary`；
 * - 中央显示 `label_progress_ratio`（完成/总数）、`label_streak_days`（连续天数大字）、
 *   `label_streak_best`（历史最长，小字）。
 *
 * @param completed 已完成项数
 * @param total 总项数（`0` 时进度视为 0，不绘制进度弧）
 * @param streak 训练连续打卡信息
 */
@Composable
fun ProgressRing(
    completed: Int,
    total: Int,
    streak: StreakInfo,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val trackColor = colorScheme.surfaceVariant
    val progressColor = colorScheme.primary
    val ratio: Float = if (total > 0) {
        (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = modifier.size(RING_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = STROKE_WIDTH.toPx()
            val diameter = size.minDimension - strokeWidthPx
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)

            // 轨道
            drawArc(
                color = trackColor,
                startAngle = START_ANGLE,
                sweepAngle = FULL_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )
            // 进度
            if (ratio > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = START_ANGLE,
                    sweepAngle = FULL_SWEEP * ratio,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.label_progress_ratio, completed, total),
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.label_streak_days, streak.current),
                style = MaterialTheme.typography.displaySmall,
                color = colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.label_streak_best, streak.best),
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val RING_SIZE = 200.dp
private val STROKE_WIDTH = 16.dp
private const val START_ANGLE = -90f
private const val FULL_SWEEP = 360f
