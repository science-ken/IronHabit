package com.ironhabit.app.ui.screens.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.WeekDayDetail
import com.ironhabit.app.ui.components.planGoalText
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlinx.datetime.LocalDate
import com.ironhabit.app.domain.util.DateUtils

/**
 * 星期格点开的**只读**日卡。
 *
 * ⚠️ 刻意不复用「今日」页那三个 `ModalBottomSheet`（`TodayScreen` / `CheckInSheet` /
 * `MealEditSheet`）：它们都是**可写**的，而且绑的是 `selectedEpochDay` 游标而不是参数 ——
 * 给过去的日期复用等于"点开上周四，手一抖把上周四打了卡"。本弹层不收任何写回调，
 * 能做的只有关闭。
 *
 * 同样刻意**不显示**四件数据层就没有的事（宁可空着，也不编 —— 见「磁贴必须诚实」）：
 * 逐组是哪几组（只在 `completed_sets_mask` 里）、动作时长（[WeekDayDetail] 这层不带）、
 * 逐组 RPE（RPE 是整条动作 × 当天一条）、计划容量（计划侧只有组×次×重量）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeekDaySheet(
    day: WeekDayDetail,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = IronHabitSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IronHabitSpacing.xl),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = dayTitle(day.dateEpochDay),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.ai_package_close))
                }
            }

            DaySummaryRow(day = day)

            if (day.items.isNotEmpty()) {
                DayLabel(text = stringResource(R.string.ai_day_items_label))
                day.items.forEach { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = IronHabitSpacing.xl),
                    ) {
                        Text(
                            text = item.exerciseName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = itemDetailLine(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (day.completedSets < day.plannedSets) {
                Text(
                    text = stringResource(
                        R.string.ai_day_shortfall,
                        day.plannedSets,
                        day.completedSets,
                        day.plannedSets - day.completedSets,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = IronHabitSpacing.xl),
                )
            }

            // 只有"什么都没排、什么都没做、什么都没记"才配得上那句"这天没有记录"。
            // 排了 12 组一次没做的天要走的上面那条 shortfall，不是这句 ——
            // 那句里的"计划 0 组"是写死的，用错地方就是编。
            if (day.items.isEmpty() && day.completedSets == 0 && day.plannedSets == 0 &&
                day.weightKg == null && day.kcal == null
            ) {
                Text(
                    text = stringResource(R.string.ai_day_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = IronHabitSpacing.xl),
                    textAlign = TextAlign.Center,
                )
            }

            Text(
                text = stringResource(R.string.ai_day_readonly_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IronHabitSpacing.xl),
            )
        }
    }
}

/** 四格摘要：没数据的那一格**整格不显示**，不画一个 0 或破折号冒充有数。 */
@Composable
private fun DaySummaryRow(day: WeekDayDetail) {
    val cells: List<Pair<String, String>> = buildList {
        add(stringResource(R.string.ai_day_stat_sets) to "${day.completedSets}/${day.plannedSets}")
        day.weightKg?.let { weight ->
            add(stringResource(R.string.ai_day_stat_weight) to formatKg(weight))
        }
        day.kcal?.let { kcal ->
            val label: String = if (day.dietPrecise) {
                stringResource(R.string.ai_day_stat_kcal)
            } else {
                stringResource(R.string.ai_day_stat_kcal_approx)
            }
            add(label to if (day.dietPrecise) kcal.toString() else stringResource(R.string.ai_day_value_approx, kcal))
        }
        day.proteinG?.let { protein ->
            add(stringResource(R.string.ai_day_stat_protein) to protein.toString())
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = IronHabitSpacing.xl),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
    ) {
        cells.forEach { (label, value) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = IronHabitSpacing.xl),
    )
}

/** `4 × 10 · 40kg · RPE 6 · 备注`；没评级的要标出来，它会让下次进步判断用不到这条。 */
@Composable
private fun itemDetailLine(item: com.ironhabit.app.domain.model.WeekItemDetail): String {
    val goal: String = planGoalText(
        sets = item.sets,
        reps = item.reps,
        weightKg = item.weightKg,
    )
    val rpePart: String = item.rpe?.let { rpe -> " · RPE $rpe" }
        ?: " · ${stringResource(R.string.ai_day_rpe_missing)}"
    val notePart: String = item.note?.trim()?.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
    return "$goal$rpePart$notePart"
}

/** `9/14 周一`。 */
@Composable
private fun dayTitle(epochDay: Long): String {
    val date: LocalDate = LocalDate.fromEpochDays(epochDay.toInt())
    val weekdayRes: Int = when (DateUtils.weekdayMon1(epochDay)) {
        1 -> R.string.weekday_mon
        2 -> R.string.weekday_tue
        3 -> R.string.weekday_wed
        4 -> R.string.weekday_thu
        5 -> R.string.weekday_fri
        6 -> R.string.weekday_sat
        else -> R.string.weekday_sun
    }
    return "${date.monthNumber}/${date.dayOfMonth} ${stringResource(weekdayRes)}"
}
