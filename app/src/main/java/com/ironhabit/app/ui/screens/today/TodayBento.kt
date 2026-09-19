package com.ironhabit.app.ui.screens.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.ui.components.cellColor
import com.ironhabit.app.ui.components.planGoalText
import com.ironhabit.app.ui.components.weekdayShortLabel
import com.ironhabit.app.ui.screens.ai.formatKg
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlin.math.roundToInt

/**
 * 今日页的磁贴概览（F1：概览 + **同屏**清单）。
 *
 * 三条硬规则：
 * 1. **只渲染有真实数据的格子** —— 没数据的整块缺席，绝不出现 `0 / 0` 或编出来的数字
 *    （与 [com.ironhabit.app.domain.model.WeeklyReview] 的「`null` 不用 `0` 冒充」同一口径）。
 * 2. **格子本身不承载勾选** —— 逐组勾选、餐次勾选全在下方清单里，这里只是读数。
 *    唯一的例外是习惯格：它的勾选在「自律」tab 也有一份（`HabitRow` 由 `DisciplineScreen` 渲染，
 *    `DisciplineViewModel.toggleHabit` 在），所以跳过去不丢功能。
 * 3. **不做动效** —— 本轮（spec §5）整体推迟，这里连 `animateColorAsState` 都不用。
 */
@Composable
fun TodayBento(
    state: TodayUiState,
    modifier: Modifier = Modifier,
    onOpenDiscipline: () -> Unit = {},
) {
    // 「下一项」= 当天首个未完成，按 sortOrder 取（与清单的展示顺序一致）。
    val nextItem: TodayPlanItem? = state.plans.asSequence()
        .filterNot { it.isCompleted }
        .minByOrNull { it.plan.sortOrder }
    val habitTotal: Int = state.habits.size
    val habitDone: Int = state.habits.count { it.isCompletedToday }
    val hasMeals: Boolean = state.meals.isNotEmpty()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm)) {
            BentoTile(modifier = Modifier.weight(1f), deep = true) {
                Text(
                    text = stringResource(R.string.label_streak_days, state.trainingStreak.current),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.label_streak_best, state.trainingStreak.best),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BentoTile(modifier = Modifier.weight(1f)) {
                TileTitle(stringResource(R.string.title_progress))
                TileValue(stringResource(R.string.label_progress_ratio, state.completedCount, state.totalCount))
                ProgressPips(done = state.completedCount, total = state.totalCount)
            }
        }

        // ---- 本周复盘（周总容量 / 平均 RPE / 总组数 / 体重变化）----
        val review: WeeklyReview? = state.weeklyReview
        if (review != null && (review.training.completedDays > 0 || review.body.deltaKg != null)) {
            val missing: String = stringResource(R.string.ai_review_value_missing)
            BentoTile(modifier = Modifier.fillMaxWidth()) {
                TileTitle(stringResource(R.string.ai_review_title))
                Row(horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.md)) {
                    TileStat(
                        label = stringResource(R.string.ai_review_stat_volume),
                        value = formatKg(review.training.totalVolumeKg),
                    )
                    TileStat(
                        label = stringResource(R.string.ai_review_stat_rpe),
                        value = review.training.avgRpe?.let { formatKg(it) } ?: missing,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.md)) {
                    TileStat(
                        label = stringResource(R.string.label_week_total_sets),
                        value = review.training.totalSets.toString(),
                    )
                    TileStat(
                        label = stringResource(R.string.ai_review_stat_weight),
                        value = review.body.deltaKg?.let { delta ->
                            (if (delta > 0f) "+" else "") + formatKg(delta)
                        } ?: missing,
                    )
                }
                // D 版：热力条贴在数字下面，不额外占一格。
                WeekHeatStrip(cells = state.weekHeatmap)
            }
        }

        if (hasMeals || habitTotal > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm)) {
                if (hasMeals) {
                    BentoTile(modifier = Modifier.weight(1f)) {
                        TileTitle(stringResource(R.string.title_today_meals))
                        Text(
                            text = stringResource(
                                R.string.label_diet_intake_kcal,
                                state.mealTotals.intakeKcal,
                                dietDenominatorKcal(state),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.label_diet_intake_protein,
                                state.mealTotals.intakeProtein.roundToInt(),
                                dietDenominatorProtein(state),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (habitTotal > 0) {
                    BentoTile(
                        modifier = Modifier.weight(1f),
                        onClick = onOpenDiscipline,
                    ) {
                        TileTitle(stringResource(R.string.title_today_habits))
                        TileValue(stringResource(R.string.label_progress_ratio, habitDone, habitTotal))
                        ProgressPips(done = habitDone, total = habitTotal)
                    }
                }
            }
        }

        if (nextItem != null) {
            BentoTile(modifier = Modifier.fillMaxWidth()) {
                TileTitle(stringResource(R.string.label_today_pending))
                Text(
                    text = nextItem.exercise.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = nextItem.setsProgressText() ?: nextItem.goalText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 单个磁贴：更深一档的容器 + 卡片圆角。
 *
 * [deep] 用于一行里需要压住视觉重心的那一格（连续天数）。
 */
@Composable
private fun BentoTile(
    modifier: Modifier = Modifier,
    deep: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val container: Color =
        if (deep) colorScheme.surfaceContainerHighest else colorScheme.surfaceContainerHigh
    Column(
        modifier = modifier
            .clip(IronHabitShapes.card)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // 同行两格内容行数可能不同，给一个共同下限把它们撑平（组件固有规格，非布局间距）。
            .heightIn(min = 92.dp)
            .padding(IronHabitSpacing.md),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
        content = content,
    )
}

@Composable
private fun TileTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TileValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 磁贴内的一列「标签 + 数值」，与同排其他列平分宽度。 */
@Composable
private fun RowScope.TileStat(label: String, value: String) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xxs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 本周热力条：周一 → 周日共 7 格，每格下面标一个单字星期。
 *
 * 配色复用 [cellColor]（`surfaceVariant → primary` 按密度档插值），标签复用
 * [weekdayShortLabel] —— 和「自律」「历史」页的多周热力图同一套色阶，不另调一版。
 */
@Composable
private fun WeekHeatStrip(cells: List<HeatmapCell>, modifier: Modifier = Modifier) {
    if (cells.isEmpty()) return
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xxs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs)) {
            cells.forEach { cell ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(HEAT_CELL_HEIGHT)
                        .clip(IronHabitShapes.cell)
                        .background(cellColor(cell)),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs)) {
            cells.forEach { cell ->
                Text(
                    text = weekdayShortLabel(DateUtils.weekdayMon1(cell.epochDay)),
                    style = labelStyle,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 分段进度条：一格一项，填色 = 已完成。
 *
 * 项数过多时（一天排了十几项）格子会细到看不见，退化成一根线性进度条。
 */
@Composable
private fun ProgressPips(done: Int, total: Int, modifier: Modifier = Modifier) {
    if (total <= 0) return
    val colorScheme = MaterialTheme.colorScheme
    if (total > PIP_MAX) {
        LinearProgressIndicator(
            progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.xxs),
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(PIP_HEIGHT)
                    .clip(RoundedCornerShape(PIP_CORNER))
                    .background(
                        if (index < done) colorScheme.primary else colorScheme.surfaceContainerHighest,
                    ),
            )
        }
    }
}

/** 目标为 0 时回落到「计划总量」作分母，避免 `x / 0`（与 [com.ironhabit.app.ui.components.DietTotalsBar] 同口径）。 */
private fun dietDenominatorKcal(state: TodayUiState): Int =
    if (state.dietTarget.targetKcal > 0) state.dietTarget.targetKcal else state.mealTotals.planKcal

private fun dietDenominatorProtein(state: TodayUiState): Int =
    if (state.dietTarget.targetProtein > 0) {
        state.dietTarget.targetProtein
    } else {
        state.mealTotals.planProtein.roundToInt()
    }

/** 已完成组数 / 目标组数；目标组数为 0（`SetCheckboxRow` 因此压根不渲染勾选框）时返回 `null`。 */
@Composable
private fun TodayPlanItem.setsProgressText(): String? {
    val target = plan.targetSets
    val done = checkIn?.completedSets ?: 0
    return if (target > 0) stringResource(R.string.label_sets_progress, done, target) else null
}

/** 目标本身（组 × 次 / 重量 / 时长），复用清单卡片同一套文案口径。 */
@Composable
private fun TodayPlanItem.goalText(): String =
    planGoalText(plan.targetSets, plan.targetReps, plan.targetWeightKg, plan.targetDurationMin)

/** 分段条的段数上限，超过则退化为线性进度条。 */
private const val PIP_MAX: Int = 12

/** 分段条单段高度（组件固有规格）。 */
private val PIP_HEIGHT = 6.dp

/** 热力条单格高度（组件固有规格）。 */
private val HEAT_CELL_HEIGHT = 20.dp

/** 分段条单段圆角（组件固有规格）。 */
private val PIP_CORNER = 2.dp
