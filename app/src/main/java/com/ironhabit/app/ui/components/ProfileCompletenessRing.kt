package com.ironhabit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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

/**
 * 档案完整度环：一圈底环 + 一段进度弧 + 中央 `n/m`。
 *
 * 为什么手绘而不用 M3 的 `CircularProgressIndicator`：那个组件没有中央文本槽，
 * 硬叠一层 `Box` 还要去拧它自带的轨道色与端点形状。这里只需要两笔弧，画出来更可控。
 *
 * 颜色按 `Color.kt` 的分工走：完整度是**进度**（还差几格），所以取 `primary` 青；
 * `tertiary` 金留给成就 / 连续 / 纪录，以及「还差哪几项」那一行缺口提示。
 * 满圈时弧与底环重合，看不出差别 —— 那是"没有缺口"的正常态，不需要额外强调。
 *
 * @param done 已填项数
 * @param total 判据总项数（来自 `ProfileField.entries`，不在界面写死）
 */
@Composable
fun ProfileCompletenessRing(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val fraction: Float = if (total <= 0) 0f else done.toFloat() / total.toFloat()

    Box(modifier = modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke: Float = RING_STROKE.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)

            drawArc(
                color = colorScheme.outlineVariant,
                startAngle = 0f,
                sweepAngle = FULL_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = colorScheme.primary,
                // 12 点方向起算，顺时针。
                startAngle = START_ANGLE,
                sweepAngle = FULL_SWEEP * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = stringResource(R.string.value_profile_fraction, done, total),
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurface,
        )
    }
}

/** 环的直径：组件固有尺寸（与档案头像同档），非布局间距，故就地定义。 */
private val RING_SIZE = 40.dp

/** 环的描边宽度。 */
private val RING_STROKE = 4.dp

private const val START_ANGLE = -90f
private const val FULL_SWEEP = 360f
