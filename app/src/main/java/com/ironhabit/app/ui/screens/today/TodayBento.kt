package com.ironhabit.app.ui.screens.today

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.ironhabit.app.ui.formatKg
import com.ironhabit.app.ui.theme.IronHabitShapes
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlin.math.roundToInt

/**
 * 今日页的磁贴概览（① 形态：整屏只有磁贴，清单在点开的底部弹窗里）。
 *
 * 三条规则：
 * 1. **只渲染有真实数据的格子** —— 没数据的整块缺席，绝不出现 `0 / 0` 或编出来的数字
 *    （与 [com.ironhabit.app.domain.model.WeeklyReview] 的「`null` 不用 `0` 冒充」同一口径）。
 * 2. **磁贴本身不承载勾选** —— 逐组勾选、餐次勾选在 [onOpenTrain] / [onOpenMeal] 打开的弹窗里。
 *    弹窗仍在同一屏（不是新路由），所以「哪几组被勾了」这个全 app 唯一出口**没有丢**，
 *    只是从 1 tap 变成 2 tap。
 * 3. **动效只有按压** —— 能点的格子按下去缩一档（[TILE_PRESSED_SCALE]），用来回答"这格点得动"。
 *    spec §5 里的其余项（色彩过渡、弹窗自定义动效）仍整体推迟。
 */
@Composable
fun TodayBento(
    state: TodayUiState,
    onOpenTrain: () -> Unit,
    onOpenMeal: () -> Unit,
    onOpenHabit: () -> Unit,
    modifier: Modifier = Modifier,
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
        // `IntrinsicSize.Min` + 磁贴里的 `fillMaxHeight()`：streak 换成 displaySmall 之后
        // 这一行两块磁贴差了一百多像素，不拉平就不是一行网格，只是两块碰巧摆在一起。
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            BentoTile(modifier = Modifier.weight(1f), deep = true) {
                TileTitle(stringResource(R.string.label_streak_tile_title))
                // 全 app 最重要的数字（审查报告 三.2）：以前整句「连续 N 天」一起用 titleLarge，
                // 比历史页那个完成率还小。displaySmall 在 `Type.kt` 里本来就是给这个位置准备的，
                // 只是整句 40sp 会撑破半宽磁贴，所以把标签拆到上一行、这里只留「N 天」。
                Text(
                    text = stringResource(R.string.label_streak_days_value, state.trainingStreak.current),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.label_streak_best, state.trainingStreak.best),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BentoTile(
                modifier = Modifier.weight(1f),
                go = true,
                onClick = onOpenTrain,
            ) {
                TileTitle(stringResource(R.string.title_progress))
                TileValue(stringResource(R.string.label_progress_ratio, state.completedCount, state.totalCount))
                // 左边 streak 换成 displaySmall 之后这一格被拉高了（同行等高），
                // 进度点不压到底部就只剩上面三行内容 + 一块空底。
                Spacer(modifier = Modifier.weight(1f))
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
                        // 分母 = 本周生效计划的目标组数。为 0（没排课）时不挂分母：
                        // 「24 / 0」里的 0 是编出来的对比，不是事实。
                        value = if (state.plannedSetsThisWeek > 0) {
                            stringResource(
                                R.string.label_progress_ratio,
                                review.training.totalSets,
                                state.plannedSetsThisWeek,
                            )
                        } else {
                            review.training.totalSets.toString()
                        },
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

        // 习惯格**常驻**：一个习惯都没有时，这里就是今日页唯一的创建入口
        // （磁贴化之前页面上有那条空态，格子跟着数据消失就等于把路断了）。
        // 饮食格相反：常驻入口里已经有「生成饮食计划」，没数据时不必占一格。
        Row(horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm)) {
            if (hasMeals) {
                BentoTile(
                    modifier = Modifier.weight(1f),
                    go = true,
                    onClick = onOpenMeal,
                ) {
                    TileTitle(stringResource(R.string.title_today_meals))
                    // 分子走 mealIntake（明细优先 → 打勾整餐值 → 0），
                    // **不再**读 mealTotals.intakeKcal —— 那个口径把"打了勾"当成"吃了"，
                    // 而勾可能只是完成计划的标记。AI 生成过这一餐在这里贡献 0。
                    Text(
                        text = stringResource(
                            R.string.label_diet_intake_kcal,
                            state.mealIntake.kcal,
                            dietDenominatorKcal(state),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.label_diet_intake_protein,
                            state.mealIntake.proteinG.roundToInt(),
                            dietDenominatorProtein(state),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BentoTile(
                modifier = Modifier.weight(1f),
                go = true,
                onClick = onOpenHabit,
            ) {
                TileTitle(stringResource(R.string.title_today_habits))
                if (habitTotal == 0) {
                    Text(
                        text = stringResource(R.string.empty_today_habits),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TileValue(stringResource(R.string.label_progress_ratio, habitDone, habitTotal))
                    ProgressPips(done = habitDone, total = habitTotal)
                }
            }
        }

        if (nextItem != null) {
            BentoTile(
                modifier = Modifier.fillMaxWidth(),
                go = true,
                onClick = onOpenTrain,
            ) {
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
        } else if (state.plans.isNotEmpty()) {
            // 全部勾完时不能只是让这格消失 —— 用户分不清"练完了"和"今天没排课"。
            BentoTile(
                modifier = Modifier.fillMaxWidth(),
                go = true,
                onClick = onOpenTrain,
            ) {
                TileTitle(stringResource(R.string.title_today_train))
                Text(
                    text = stringResource(R.string.label_today_done),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
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
    go: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val container: Color =
        if (deep) colorScheme.surfaceContainerHighest else colorScheme.surfaceContainerHigh

    // 按压反馈（spec §5：120ms 缩到 0.975）。只有能点的格子才有 ——
    // 不能点的格子给一个按压缩放，等于谎报"这格能按"。
    // ⚠️ `remember` 必须**无条件**调用（Compose 要求调用点在组合树里位置稳定）：
    // 以前写成 `if (onClick != null) remember {...} else null`，同一槽位的 onClick 在
    // null / 非 null 之间切换时 remember 的位置会漂移，按压态丢失或串到别的格子。
    // "只在能点时才响应"这件事挪到下面取值处判。
    val interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
    val pressed: Boolean = onClick != null && interactionSource.collectIsPressedAsState().value
    val scale: Float by animateFloatAsState(
        targetValue = if (pressed) TILE_PRESSED_SCALE else 1f,
        animationSpec = tween(PRESS_DURATION_MS),
        label = "tilePress",
    )

    Box(
        modifier = modifier
            // 同行磁贴等高（外层 Row 用 IntrinsicSize.Min 量出来），见调用处注释。
            .fillMaxHeight()
            // graphicsLayer 放在 clip/background 之前，整块磁贴（含底色）一起缩。
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(IronHabitShapes.card)
            .background(container)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        Column(
            modifier = Modifier
                // 同行两格内容行数可能不同，给一个共同下限把它们撑平（组件固有规格，非布局间距）。
                .heightIn(min = 92.dp)
                // 外层 Box 已经被拉成等高，Column 不跟着填满的话，格子里 `weight(1f)` 的
                // Spacer 没有可分的余量，内容仍会挤在上半截。
                .fillMaxHeight()
                .padding(IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
            content = content,
        )
        if (go) {
            // 装饰性指示符：以前是 `Text("›")`，在无障碍树里是一个真实文本节点，
            // TalkBack 每划过一个磁贴都会念一个多余符号。`contentDescription = null` 即整节点不进树
            // （与 `ProfileSummaryCard` 的箭头同一写法）。
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(IronHabitSpacing.md),
            )
        }
    }
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

/** 按压缩放比例（spec §5：轻微到只够让人确认"点中了"）。 */
private const val TILE_PRESSED_SCALE: Float = 0.975f

/** 按压动画时长（毫秒，spec §5）。 */
private const val PRESS_DURATION_MS: Int = 120
