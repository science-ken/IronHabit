package com.ironhabit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.util.DateUtils

/**
 * 类 GitHub 贡献图（日历热力图）。
 *
 * 布局：**列 = 周（时间递增），行 = 周一..周日**。
 * 采用 [DateUtils.weekdayMon1] 计算首格所在行，`epochDay` 递增填充；数据不足 7 个/周时补空格。
 * 颜色按 `level 0..4` 在 `colorScheme.surfaceVariant`（无打卡）与 `colorScheme.primary`（高密度）之间插值。
 *
 * @param cells 升序排列的热力图单元格（`StatsRepository.heatmap` 输出）
 */
@Composable
fun HeatmapGrid(
    cells: List<HeatmapCell>,
    modifier: Modifier = Modifier,
) {
    if (cells.isEmpty()) {
        Text(
            text = stringResource(R.string.empty_heatmap),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val columns: List<List<HeatmapCell?>> = remember(cells) { buildColumns(cells) }
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
    ) {
        columns.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                week.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .size(CELL_SIZE)
                            .clip(IronHabitShapes.cell)
                            .background(cellColor(cell)),
                    )
                }
            }
        }
    }
}

/** 把升序单元格铺进「周列 × 七日行」网格；空白格为 `null`。 */
private fun buildColumns(cells: List<HeatmapCell>): List<List<HeatmapCell?>> {
    val offset: Int = DateUtils.weekdayMon1(cells.first().epochDay) - 1
    val totalSlots: Int = offset + cells.size
    val weekCount: Int = (totalSlots + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK
    val columns: List<MutableList<HeatmapCell?>> =
        List(weekCount) { MutableList(DAYS_PER_WEEK) { null } }

    cells.forEachIndexed { index, cell ->
        val slot = offset + index
        columns[slot / DAYS_PER_WEEK][slot % DAYS_PER_WEEK] = cell
    }
    return columns
}

/** 单元格颜色：按密度等级 `0..4` 在 `surfaceVariant` → `primary` 之间线性插值。 */
@Composable
private fun cellColor(cell: HeatmapCell?): Color {
    val colorScheme = MaterialTheme.colorScheme
    val level: Int = cell?.level ?: MIN_LEVEL
    val fraction: Float = level.coerceIn(MIN_LEVEL, MAX_LEVEL).toFloat() / MAX_LEVEL.toFloat()
    return lerp(colorScheme.surfaceVariant, colorScheme.primary, fraction)
}

private const val DAYS_PER_WEEK = 7
private const val MIN_LEVEL = 0
private const val MAX_LEVEL = 4
/** 单元格边长：组件固有尺寸，非布局间距，故就地定义。 */
private val CELL_SIZE = 14.dp

/** 单元格间距：热力图需要比 4dp 更紧凑才能在一屏放下 52 周，故作为组件规格就地定义。 */
private val COLUMN_GAP = 3.dp
