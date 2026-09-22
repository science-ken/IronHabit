package com.ironhabit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.CategoryShare
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlin.math.roundToInt

/**
 * 训练类型占比饼图（**Compose 原生 `Canvas` 的 `drawArc(useCenter = true)` 手绘，零第三方依赖**）。
 *
 * 左侧按 [CategoryShare.ratio] 顺时针切分圆周，颜色取自固定的 `colorScheme` 派生色序列；
 * 右侧图例列出 `category_*` 文案 + 「次数 (百分比%)」，同时用到 [CategoryShare.count] 与 [CategoryShare.ratio]。
 * 空数据时显示 `empty_charts`。
 */
@Composable
fun CategoryPieChart(
    shares: List<CategoryShare>,
    modifier: Modifier = Modifier,
) {
    val nonEmpty: List<CategoryShare> = shares.filter { it.ratio > 0f && it.count > 0 }

    if (nonEmpty.isEmpty()) {
        Text(
            text = stringResource(R.string.empty_charts),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val sliceColors: List<Color> = sliceColors(MaterialTheme.colorScheme)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
    ) {
        Canvas(modifier = Modifier.size(PIE_SIZE)) {
            var startAngle = START_ANGLE
            nonEmpty.forEachIndexed { index, share ->
                val sweep: Float = share.ratio.coerceIn(0f, 1f) * FULL_SWEEP
                if (sweep > 0f) {
                    drawArc(
                        color = sliceColors[index % sliceColors.size],
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = true,
                    )
                }
                startAngle += sweep
            }
        }

        // `weight(1f)` 而不是 `fillMaxSize()`：这是 `Row` 的子项，父容器是「我的」页那根可滚
        // `Column`（无限高约束）—— 要满父宽满父高会把图例列的高度撑失控，与饼图也不居中了。
        // 横向吃剩余宽度交给 weight，纵向对齐交给上面的 `CenterVertically`。
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            nonEmpty.forEachIndexed { index, share ->
                LegendRow(
                    color = sliceColors[index % sliceColors.size],
                    label = categoryLabel(share.category),
                    count = share.count,
                    ratio = share.ratio,
                )
            }
        }
    }
}

@Composable
private fun LegendRow(
    color: Color,
    label: String,
    count: Int,
    ratio: Float,
) {
    Row(
        modifier = Modifier.padding(vertical = IronHabitSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(LEGEND_DOT)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = "$label  $count (${(ratio * 100f).roundToInt()}%)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** `category_*` 资源映射（分类 → 中文文案）。 */
@Composable
private fun categoryLabel(category: ExerciseCategory): String = stringResource(
    when (category) {
        ExerciseCategory.BODYWEIGHT -> R.string.category_bodyweight
        ExerciseCategory.STRENGTH -> R.string.category_strength
        ExerciseCategory.CARDIO -> R.string.category_cardio
        ExerciseCategory.CUSTOM -> R.string.category_custom
    },
)

/**
 * 分类色。
 *
 * ⚠️ 第 4 类（`CUSTOM`）以前直接用 `colorScheme.error`（红）—— 那是"出错了"的语义色，
 * 拿它当一种动作分类，用户第一反应是这里出了问题（审查报告 2.3）。
 * 换成 `primaryContainer`：与 primary 同色系（同一个维度 = 训练量），深浅两套底上都成立，
 * 且不占用任何语义色。
 */
private fun sliceColors(colorScheme: ColorScheme): List<Color> = listOf(
    colorScheme.primary,
    colorScheme.secondary,
    colorScheme.tertiary,
    colorScheme.primaryContainer,
)

private val PIE_SIZE = 140.dp
private val LEGEND_DOT = 12.dp
private const val START_ANGLE = -90f
private const val FULL_SWEEP = 360f
