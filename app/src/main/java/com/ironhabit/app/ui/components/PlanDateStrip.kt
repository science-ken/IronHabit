package com.ironhabit.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.ui.formatMonthDay
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 计划日期栏：`‹ 日期区间 ›` + 一周 7 天。
 *
 * 语义（2026-09-27 改版：一格一天）：
 * - **日期行 = 所选周的整 7 天**，休息日照常在列。改版前这里只渲染「排了课的那几天」，
 *   而它又是今日页**唯一**的翻页控件，于是休息日在导航上压根不存在：
 *   `‹ ›` 步长 ±7，从周一只能翻到周一，一旦离开今天（今天没排课时）就再也回不去；
 * - 「有课」降级为格子顶部的一颗**圆点**（数据仍是 [plannedWeekdays]），
 *   不再决定"这一天存不存在"；
 * - 日期游标锚点 = **包含 `todayEpochDay` 的那一周（周一起算）**，`‹ ›` 允许跨周，
 *   跨周后仍套用同一 weekday 模板（`week_plans.day_of_week` 是模板，不是具体日期）；
 * - 格子里的「9/27」是**展示层**由游标日期算出，与存储无关。
 *
 * 本组件是**纯展示 + 回调**，不持有状态、不读数据库。
 *
 * @param weekStartEpochDay 所选周的周一 epochDay
 * @param selectedEpochDay 当前所选日期 epochDay
 * @param todayEpochDay 今天 epochDay（标签加 `*` 用）
 * @param plannedWeekdays 排了课的日子（`1..7`，升序）→ 圆点
 * @param onSelectEpochDay 点某一天 → 选中该天
 * @param onPreviousWeek 上一周
 * @param onNextWeek 下一周
 */
@Composable
fun PlanDateStrip(
    weekStartEpochDay: Long,
    selectedEpochDay: Long,
    todayEpochDay: Long,
    plannedWeekdays: List<Int>,
    onSelectEpochDay: (Long) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekEndEpochDay = weekStartEpochDay + DAYS_PER_WEEK - 1

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPreviousWeek) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = stringResource(R.string.cd_previous_week),
                )
            }
            Text(
                text = "${formatMonthDay(weekStartEpochDay)} - ${formatMonthDay(weekEndEpochDay)}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onNextWeek) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = stringResource(R.string.cd_next_week),
                )
            }
        }

        // 一周 7 格等宽。**不用 `TabRow`**：它给每个 Tab 左右各留 16dp 文字内边距，
        // 7 等分后每格只剩 ~14dp，「9/27」会被挤成两行（真机实测到的排版事故）。
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAY_RANGE.forEach { weekday ->
                val epochDay = weekStartEpochDay + (weekday - 1)
                DayCell(
                    weekday = weekday,
                    epochDay = epochDay,
                    isToday = epochDay == todayEpochDay,
                    isPlanned = weekday in plannedWeekdays,
                    isSelected = epochDay == selectedEpochDay,
                    onClick = { onSelectEpochDay(epochDay) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (plannedWeekdays.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_plan_add_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一格一天：训练日圆点 + 单字星期 + `M/d`（今天再带一个 `*`）+ 选中条。
 *
 * 圆点与选中条**都始终占位**：省掉未选中的那一条会让文字上下跳，省掉没课那天的圆点
 * 会让 7 个格子看起来高度不一。
 */
@Composable
private fun DayCell(
    weekday: Int,
    epochDay: Long,
    isToday: Boolean,
    isPlanned: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = if (isSelected) colorScheme.onSurface else colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clip(IronHabitShapes.cell)
            .clickable(onClick = onClick)
            .semantics { selected = isSelected }
            .padding(top = IronHabitSpacing.xs, bottom = IronHabitSpacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xxs),
    ) {
        Box(
            modifier = Modifier
                .size(DAY_DOT_SIZE)
                .clip(CircleShape)
                .background(
                    if (isPlanned) colorScheme.primary else Color.Transparent,
                ),
        )
        Text(
            text = weekdayShortLabel(weekday),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
        )
        Text(
            text = if (isToday) "${formatMonthDay(epochDay)}*" else formatMonthDay(epochDay),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .padding(top = IronHabitSpacing.xxs)
                .width(DAY_MARKER_WIDTH)
                .height(DAY_MARKER_HEIGHT)
                .clip(IronHabitShapes.full)
                .background(if (isSelected) colorScheme.primary else Color.Transparent),
        )
    }
}

/** 一周 7 天。 */
private const val DAYS_PER_WEEK: Long = 7L

/** 周一~周日（与 `week_plans.day_of_week` 同口径：`1` = 周一 … `7` = 周日）。 */
private val WEEKDAY_RANGE: IntRange = 1..7

/** 训练日标记的直径。 */
private val DAY_DOT_SIZE = 4.dp

/** 选中条（取代 `TabRow` 的下划线，尺寸自己定）。 */
private val DAY_MARKER_WIDTH = 20.dp
private val DAY_MARKER_HEIGHT = 3.dp

// `epochDay` → 「M/D」 用的是本包 `TrendChart.kt` 里那份 `formatMonthDay`
// （以前这里有一份逐字相同的私有副本，同包两份会在其中一份改 internal 时直接撞车）。

/**
 * 周一 epochDay → 「9/21–9/27」这样的周区间文本。
 *
 * 「你正在改哪一周」的提示在计划编辑页与训练页都要用，抽这一份避免第三处复制粘贴。
 * [weekStartEpochDay] 为 `0`（「每周相同」哨兵值）时返回空串 —— 那份不属于任何一周。
 */
/** epochDay → 「9/21」；预览页逐天卡片要标出日期，不只是星期。 */
internal fun monthDayText(epochDay: Long): String = formatMonthDay(epochDay)

internal fun weekRangeText(weekStartEpochDay: Long): String {    if (weekStartEpochDay <= 0L) return ""
    val end = weekStartEpochDay + DAYS_PER_WEEK - 1
    return "${formatMonthDay(weekStartEpochDay)}–${formatMonthDay(end)}"
}

/** 星期（`1..7`）→ 单字中文标签资源。 */
@Composable
internal fun weekdayShortLabel(weekday: Int): String = stringResource(
    when (weekday) {
        1 -> R.string.weekday_short_mon
        2 -> R.string.weekday_short_tue
        3 -> R.string.weekday_short_wed
        4 -> R.string.weekday_short_thu
        5 -> R.string.weekday_short_fri
        6 -> R.string.weekday_short_sat
        else -> R.string.weekday_short_sun
    },
)

/** 星期（`1..7`）→ 「周一」…「周日」。今日页的休息日提示要说清是**哪一天**。 */
@Composable
internal fun weekdayFullLabel(weekday: Int): String = stringResource(
    when (weekday) {
        1 -> R.string.weekday_mon
        2 -> R.string.weekday_tue
        3 -> R.string.weekday_wed
        4 -> R.string.weekday_thu
        5 -> R.string.weekday_fri
        6 -> R.string.weekday_sat
        else -> R.string.weekday_sun
    },
)
