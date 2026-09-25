package com.ironhabit.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.CategoryShare
import com.ironhabit.app.ui.theme.IronHabitSpacing
import com.ironhabit.app.ui.theme.pieSliceLevels
import kotlin.math.roundToInt

/**
 * 训练类型占比**环形图**（**Compose 原生 `Canvas` 的 `drawArc(useCenter = false)` + `Stroke` 手绘，
 * 零第三方依赖**）。
 *
 * 左侧按 [CategoryShare.ratio] 顺时针切分圆周，颜色取自固定的 `colorScheme` 派生色序列；
 * 右侧图例列出 `category_*` 文案 + 「次数 (百分比%)」，同时用到 [CategoryShare.count] 与 [CategoryShare.ratio]。
 * 环心放**总次数** —— 以前这是一块实心饼，读者想知道"一共多少"只能把图例里四个数加一遍。
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

    // 颜色按**分类**取档，不按列表位置：`categoryShareRows()` 没有 `ORDER BY`，
    // 按位置取色会让同一个分类每次刷新换一个颜色。
    val levels: List<Color> = pieSliceLevels()
    val total: Int = nonEmpty.sumOf { share -> share.count }

    /** 某个分类在这一张图上的颜色（扇区与图例点共用，两处不会各算一遍）。 */
    fun colorOf(share: CategoryShare): Color = levels[share.category.ordinal % levels.size]

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
    ) {
        Box(modifier = Modifier.size(PIE_SIZE)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke: Float = RING_THICKNESS.toPx()
                val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
                val arcSize = Size(size.width - stroke, size.height - stroke)
                var startAngle = START_ANGLE
                nonEmpty.forEach { share ->
                    val sweep: Float = share.ratio.coerceIn(0f, 1f) * FULL_SWEEP
                    if (sweep > 0f) {
                        drawArc(
                            color = colorOf(share),
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = stroke),
                        )
                    }
                    startAngle += sweep
                }
            }
            // 环心两行：总数 + 一句"这是什么数"。放在 Box 里居中，而不是画进 Canvas，
            // 这样它跟着字体缩放走，不用手算字号。
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = total.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.label_pie_center_unit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // `weight(1f)` 而不是 `fillMaxSize()`：这是 `Row` 的子项，父容器是「我的」页那根可滚
        // `Column`（无限高约束）—— 要满父宽满父高会把图例列的高度撑失控，与饼图也不居中了。
        // 横向吃剩余宽度交给 weight，纵向对齐交给上面的 `CenterVertically`。
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            nonEmpty.forEach { share ->
                LegendRow(
                    color = colorOf(share),
                    label = stringResource(categoryLabelRes(share.category)),
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

/**
 * 分类色住在 `ui/theme/Color.kt` 的 `pieSliceLevels()` —— 与热力图密度表共用同一批
 * 青的中间明度档，且那份表里记着实测的相邻对比度。本文件只负责按分类取档。
 */

private val PIE_SIZE = 120.dp

/** 环的描边宽度：组件固有尺寸（决定环心留多大地方放总数），非布局间距，故就地定义。 */
private val RING_THICKNESS = 26.dp
private val LEGEND_DOT = 12.dp
private const val START_ANGLE = -90f
private const val FULL_SWEEP = 360f
