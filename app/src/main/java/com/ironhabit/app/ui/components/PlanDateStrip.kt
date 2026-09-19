package com.ironhabit.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlinx.datetime.LocalDate

/**
 * 计划日期栏：`‹ 日期区间 ›` + 星期 chip 行。
 *
 * 语义（schema-v2 §6）：
 * - **chip 行 = 「有计划的日子」**（`SELECT DISTINCT day_of_week FROM week_plans WHERE is_active = 1`），
 *   因此通常只出现 4~5 个而不是 7 个 chip；
 * - 日期游标锚点 = **包含 `todayEpochDay` 的那一周（周一起算）**，`‹ ›` 允许跨周，
 *   跨周后仍套用同一 weekday 模板（`week_plans.day_of_week` 是模板，不是具体日期）；
 * - chip 上的中文日期标签是**展示层**由游标日期算出，与存储无关。
 *
 * 本组件是**纯展示 + 回调**，不持有状态、不读数据库。
 *
 * @param weekStartEpochDay 所选周的周一 epochDay
 * @param selectedEpochDay 当前所选日期 epochDay
 * @param todayEpochDay 今天 epochDay（高亮用）
 * @param plannedWeekdays 有计划的日子（`1..7`，升序）
 * @param onSelectEpochDay 点某个 chip → 选中该 chip 对应日期
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

        if (plannedWeekdays.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_plan_add_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                // 写在 scroll 之后 → 属于被滚动内容，滚到末尾时最后一片不会压在视口边缘上。
                .padding(end = IronHabitSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            plannedWeekdays.forEach { weekday ->
                val epochDay = weekStartEpochDay + (weekday - 1).coerceAtLeast(0)
                FilterChip(
                    selected = epochDay == selectedEpochDay,
                    onClick = { onSelectEpochDay(epochDay) },
                    label = {
                        val suffix = if (epochDay == todayEpochDay) "*" else ""
                        Text(text = "${weekdayShortLabel(weekday)} ${formatMonthDay(epochDay)}$suffix")
                    },
                )
            }
        }
    }
}

/** 一周 7 天。 */
private const val DAYS_PER_WEEK: Long = 7L

/** `epochDay` → 「M/D」（纯数字，无硬编码中文）。 */
private fun formatMonthDay(epochDay: Long): String {
    val date = LocalDate.fromEpochDays(epochDay.toInt())
    return "${date.monthNumber}/${date.dayOfMonth}"
}

/**
 * 周一 epochDay → 「9/21–9/27」这样的周区间文本。
 *
 * 「你正在改哪一周」的提示在计划编辑页与训练页都要用，抽这一份避免第三处复制粘贴。
 * [weekStartEpochDay] 为 `0`（「每周相同」哨兵值）时返回空串 —— 那份不属于任何一周。
 */
internal fun weekRangeText(weekStartEpochDay: Long): String {
    if (weekStartEpochDay <= 0L) return ""
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
