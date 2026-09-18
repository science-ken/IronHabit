package com.ironhabit.app.ui.screens.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.ui.components.DietTotalsBar
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.ExerciseCheckCard
import com.ironhabit.app.ui.components.HabitRow
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState
import com.ironhabit.app.ui.components.MealBlock
import com.ironhabit.app.ui.components.PlanDateStrip
import com.ironhabit.app.ui.components.ProgressRing
import com.ironhabit.app.ui.components.SkeletonCard
import com.ironhabit.app.ui.screens.checkin.CheckInSheet
import com.ironhabit.app.ui.screens.meals.MealEditSheet
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * Tab1「今日」页面：进度环 + 日期栏 + 训练打卡卡片 + 习惯勾选行。
 *
 * 三态齐全：加载中 → 骨架屏；加载失败 → 空态 + 「重试」；空数据 → 空态 + 「去创建」。
 * 写操作结果通过全局 [LocalSnackbarHostState] 反馈；补录详情由 [CheckInSheet]（`ModalBottomSheet`）承载。
 *
 * **日期切换**：顶部 [PlanDateStrip] 让用户点日期 chip（或 `‹ ›` 跨周）切换查看的日期；
 * 切换只改 ViewModel 的日期游标（[TodayViewModel.onSelectEpochDay]），
 * 计划 / 习惯 / 打卡状态随游标经 Room 数据流自动刷新（schema-v2 §6.1 / §6.3 坑 1）。
 *
 * **未来日只读（v3）**：所选日 > 今天时，本页**只读**——禁用全部写入口
 * （逐组勾选 / RPE / 一键打卡 / 撤销 / 补录弹层 / 习惯勾选），并给出可见提示
 * （`msg_future_day_readonly`），避免「点了没反应」。过去的日期仍可正常补录。
 *
 * @param onCreatePlan 今日无计划时「去创建」回调
 * @param onCreateHabit 今日无习惯时「去创建」回调
 * @param onEditHabit 习惯行「编辑」回调（附加的可选参数，便于 T05 复用二级页表单）
 * @param onOpenExerciseDetail 打卡卡片「动作详情」回调（参数 = 动作 id），跳转动作详情页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onCreatePlan: () -> Unit,
    onCreateHabit: () -> Unit,
    onEditHabit: (Long) -> Unit = {},
    onOpenExerciseDetail: (Long) -> Unit = {},
    /** 编辑今日某条计划（参数：计划 id、星期 1..7）。今日页此前只能打卡、不能改，这是补上的入口。 */
    onEditPlan: (Long, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current

    var sheetItem by remember { mutableStateOf<TodayPlanItem?>(null) }

    // 未来日只读：所选日 > 今天（`todayEpochDay` == 0 表示尚未加载，不判定）。
    val isFutureDay: Boolean =
        uiState.todayEpochDay > 0L && uiState.dateEpochDay > uiState.todayEpochDay

    val snackbarText: String? = uiState.snackbarRes?.let { res ->
        stringResource(res, *uiState.snackbarArgs.toTypedArray())
    }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onSnackbarShown()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // `verticalScroll` 没有 `contentPadding`：侧边与顶部内缩写在容器**外**（常驻，
            // 不会随内容滚走），底部留白写在容器**内**（滚到末尾时才有呼吸空间）。
            .padding(start = IronHabitSpacing.lg, top = IronHabitSpacing.lg, end = IronHabitSpacing.lg)
            .verticalScroll(rememberScrollState())
            .padding(bottom = IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
    ) {
        ProgressRing(
            completed = uiState.completedCount,
            total = uiState.totalCount,
            streak = uiState.trainingStreak,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                LoadingSkeleton()
                SkeletonCard()
                SkeletonCard()
            }

            errorRes != null -> {
                EmptyState(
                    text = stringResource(errorRes),
                    actionText = stringResource(R.string.action_retry),
                    onAction = viewModel::onRetry,
                )
            }

            else -> {
                // ---- 日期栏：切换查看日期（`‹ ›` 跨周；chip = 有计划的星期模板）----
                val selectedEpochDay = uiState.dateEpochDay
                if (selectedEpochDay > 0L) {
                    val weekStartEpochDay =
                        selectedEpochDay - (DateUtils.weekdayMon1(selectedEpochDay) - 1)
                    PlanDateStrip(
                        weekStartEpochDay = weekStartEpochDay,
                        selectedEpochDay = selectedEpochDay,
                        todayEpochDay = uiState.todayEpochDay,
                        plannedWeekdays = uiState.plannedWeekdays,
                        onSelectEpochDay = viewModel::onSelectEpochDay,
                        onPreviousWeek = {
                            viewModel.onSelectEpochDay(selectedEpochDay - DAYS_PER_WEEK)
                        },
                        onNextWeek = {
                            viewModel.onSelectEpochDay(selectedEpochDay + DAYS_PER_WEEK)
                        },
                    )
                }

                // ---- 未来日只读提示（可见反馈，避免「点了没反应」）----
                if (isFutureDay) {
                    Text(
                        text = stringResource(R.string.msg_future_day_readonly),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }

                if (uiState.isRestDay) {
                    Text(
                        text = stringResource(R.string.msg_rest_day),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ---- 今日训练 ----
                SectionTitle(text = stringResource(R.string.title_today_train))
                when {
                    // 这一周还没有任何计划（P3：计划按周存放）→ 只给一个「创建训练计划」入口。
                    // 与下面的"今天是休息日"是两种不同状态，不能混为一谈。
                    uiState.plans.isEmpty() && !uiState.hasPlanThisWeek -> {
                        WeekPlanEmptyCard(
                            isCreating = uiState.isCreatingPlan,
                            onCreateByAi = viewModel::onCreatePlanByAi,
                            onCreateManually = onCreatePlan,
                        )
                    }

                    uiState.plans.isEmpty() -> {
                        EmptyState(
                            text = stringResource(R.string.empty_today_plan),
                            actionText = stringResource(R.string.action_create),
                            onAction = onCreatePlan,
                        )
                    }

                    else -> {
                        uiState.plans.forEach { item ->
                            ExerciseCheckCard(
                                item = item,
                                onQuickCheckIn = { viewModel.onQuickCheckIn(item) },
                                onUndo = { viewModel.onUndoCheckIn(item) },
                                onOpenDetail = { onOpenExerciseDetail(item.exercise.id) },
                                onOpenSheet = { sheetItem = item },
                                onToggleSet = { setIndex -> viewModel.onToggleSet(item, setIndex) },
                                onSetRpe = { rpe -> viewModel.onSetRpe(item, rpe) },
                                onEditPlan = { onEditPlan(item.plan.id, item.plan.dayOfWeek) },
                                enabled = !isFutureDay,
                            )
                        }
                        // 「每周相同」开关（P3）：只在这一周**自己有**计划时出现
                        //（正在显示"每周相同"那份时，开关已经开着，用来关掉它）。
                        if (!isFutureDay) {
                            RepeatWeeklyRow(
                                checked = uiState.isRepeatWeeklyOn,
                                enabled = !uiState.isTogglingRepeatWeekly,
                                onCheckedChange = viewModel::onToggleRepeatWeekly,
                            )
                        }
                    }
                }

                // ---- 今日饮食（v3）----
                SectionTitle(text = stringResource(R.string.title_today_meals))
                if (uiState.meals.isEmpty()) {
                    EmptyState(
                        text = stringResource(R.string.empty_today_meals),
                        actionText = stringResource(R.string.action_generate_diet),
                        onAction = viewModel::onGenerateDiet,
                    )
                } else {
                    DietTotalsBar(
                        totals = uiState.mealTotals,
                        target = uiState.dietTarget,
                    )
                    uiState.meals.forEach { meal ->
                        MealBlock(
                            meal = meal,
                            onToggle = { done -> viewModel.onToggleMeal(meal, done) },
                            onEdit = { viewModel.onOpenMealEditor(meal) },
                            onDelete = { viewModel.onDeleteMeal(meal) },
                            enabled = !isFutureDay,
                        )
                    }
                    if (uiState.dietTarget.usedDefaults) {
                        Text(
                            text = stringResource(R.string.profile_incomplete_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    TextButton(
                        onClick = viewModel::onGenerateDiet,
                        enabled = !isFutureDay,
                    ) {
                        Text(text = stringResource(R.string.action_regenerate_diet))
                    }
                }

                // ---- 今日习惯 ----
                SectionTitle(text = stringResource(R.string.title_today_habits))
                if (uiState.habits.isEmpty()) {
                    EmptyState(
                        text = stringResource(R.string.empty_today_habits),
                        actionText = stringResource(R.string.action_create),
                        onAction = onCreateHabit,
                    )
                } else {
                    uiState.habits.forEach { item ->
                        HabitRow(
                            item = item,
                            onToggle = { viewModel.onToggleHabit(item) },
                            onEdit = { onEditHabit(item.habit.id) },
                            enabled = !isFutureDay,
                        )
                    }
                }
            }
        }
    }

    // 补录弹层：未来日只读，不弹（双保险：卡片入口已禁用）。
    val currentSheetItem = sheetItem
    if (currentSheetItem != null && !isFutureDay) {
        CheckInSheet(
            item = currentSheetItem,
            onDismissRequest = { sheetItem = null },
            onSubmit = { sets, reps, weightKg, durationMinutes, notes ->
                viewModel.onDetailedCheckIn(
                    item = currentSheetItem,
                    sets = sets,
                    reps = reps,
                    weightKg = weightKg,
                    durationMinutes = durationMinutes,
                    notes = notes,
                )
                sheetItem = null
            },
        )
    }

    // 编辑一餐弹层：未来日只读，不弹（双保险：卡片的编辑入口已禁用）。
    val editingMeal = uiState.editingMeal
    if (editingMeal != null && !isFutureDay) {
        MealEditSheet(
            meal = editingMeal,
            onDismissRequest = viewModel::onDismissMealEditor,
            onSubmit = { mealType, items, kcal, proteinG ->
                viewModel.onSaveMealEdit(
                    mealType = mealType,
                    items = items,
                    kcal = kcal,
                    proteinG = proteinG,
                )
            },
        )
    }
}

/** 分区小标题。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 一周 7 天（日期栏 `‹ ›` 跨周步长）。 */
private const val DAYS_PER_WEEK: Long = 7L

/**
 * 「这一周还没有训练计划」空状态卡（P3）。
 *
 * 计划从 v5 起**按周存放**：翻到某一周而那一周没排课时，只给一个创建入口，不显示任何动作 ——
 * 这就是"看得到下一周、但看不到训练计划"。
 *
 * 两个入口（都不需要用户先理解"模板 / 专属"这些概念）：
 * - [onCreateByAi]：让 AI 按档案给**这一周**排一份（本地规则兜底，见 `GenerateTrainingPlanUseCase`）；
 * - [onCreateManually]：跳到「训练」页自己挑动作（既有入口，未改）。
 *
 * @param isCreating 正在生成 → 两个按钮都禁用，主按钮文案换成「正在生成这一周的计划…」
 */
@Composable
private fun WeekPlanEmptyCard(
    isCreating: Boolean,
    onCreateByAi: () -> Unit,
    onCreateManually: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IronHabitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.empty_week_plan_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.empty_week_plan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onCreateByAi,
                enabled = !isCreating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(
                        if (isCreating) R.string.msg_plan_creating else R.string.action_create_plan_ai,
                    ),
                )
            }
            OutlinedButton(
                onClick = onCreateManually,
                enabled = !isCreating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.action_create_plan_manual))
            }
        }
    }
}

/**
 * 「每周相同」开关行（P3）。
 *
 * 语义：勾上之后，**没有单独排计划的周**都会用这一份（等于以前那个"模板"）；
 * 取消之后，没排计划的周就是空的，只显示「创建训练计划」。
 *
 * 界面刻意用"人话"解释后果（[R.string.hint_repeat_weekly]），因为"模板 / 循环 / 专属"
 * 这些词对用户没有意义 —— 他关心的是"下周会不会自动有课"。
 */
@Composable
private fun RepeatWeeklyRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = IronHabitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.action_repeat_weekly),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.hint_repeat_weekly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}
