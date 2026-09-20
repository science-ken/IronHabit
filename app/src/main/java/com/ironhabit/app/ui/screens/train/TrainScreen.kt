package com.ironhabit.app.ui.screens.train

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.ui.components.ExerciseSourceChip
import com.ironhabit.app.ui.components.exerciseSourceLabelRes
import com.ironhabit.app.ui.components.hasVisibleSourceChip
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState
import com.ironhabit.app.ui.components.planGoalText
import com.ironhabit.app.ui.components.weekRangeText
import com.ironhabit.app.ui.theme.IronHabitSpacing
import kotlinx.datetime.LocalDate

/** 训练页三个分段。 */
private enum class TrainTab(@StringRes val labelRes: Int) {
    PLAN(R.string.section_week_plan),
    LIBRARY(R.string.section_exercise_library),
    HISTORY(R.string.section_history),
}

/**
 * Tab2「训练」页面：周计划 / 动作库 / 打卡历史 三分段切换。
 *
 * 二级页导航入口经可选回调下沉（默认空实现，保证与 T04 既有调用点兼容）：
 * 新增/编辑计划、新增动作、打开动作详情、打开完整打卡历史。
 *
 * @param onAddPlan 新增计划（参数 = 当前选中星期 `1..7`）
 * @param onEditPlan 编辑计划（参数 = 计划 id、星期）
 * @param onAddExercise 新增自建动作
 * @param onOpenExercise 打开动作详情（参数 = 动作 id）
 * @param onOpenHistory 打开完整打卡历史页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainScreen(
    onAddPlan: (Int) -> Unit = {},
    onEditPlan: (Long, Int) -> Unit = { _, _ -> },
    onAddExercise: () -> Unit = {},
    onOpenExercise: (Long) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: TrainViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    var selectedTab by remember { mutableStateOf(TrainTab.PLAN) }

    val snackbarText: String? = uiState.snackbarRes?.let { res -> stringResource(res) }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onConsumeSnackbar()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TrainTab.entries.forEachIndexed { index, tab ->
                SegmentedButton(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TrainTab.entries.size),
                    label = { Text(text = stringResource(tab.labelRes)) },
                )
            }
        }

        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                LoadingSkeleton()
                LoadingSkeleton()
            }

            errorRes != null -> {
                EmptyState(
                    text = stringResource(errorRes),
                    actionText = stringResource(R.string.action_retry),
                    onAction = viewModel::onRetry,
                )
            }

            selectedTab == TrainTab.PLAN -> PlanSection(
                uiState = uiState,
                onSelectDay = viewModel::onSelectDay,
                onDeletePlan = viewModel::onDeletePlan,
                onResetPlan = viewModel::onResetPlan,
                onAddPlan = { onAddPlan(uiState.selectedDay) },
                onEditPlan = { planId -> onEditPlan(planId, uiState.selectedDay) },
            )

            selectedTab == TrainTab.LIBRARY -> LibrarySection(
                uiState = uiState,
                onAddExercise = onAddExercise,
                onOpenExercise = onOpenExercise,
                onOpenAddToPlan = viewModel::onOpenAddToPlanSheet,
            )

            else -> HistorySection(
                history = uiState.history,
                onOpenHistory = onOpenHistory,
            )
        }
    }

    // 「加入每周训练计划」弹层（动作库行尾 + / ✓ 打开；写结果由页面级 Snackbar / 错误态承载）。
    val sheetExercise: Exercise? = uiState.addToPlanSheetExercise
    if (sheetExercise != null) {
        AddToPlanSheet(
            exercise = sheetExercise,
            initialDays = uiState.plannedDaysByExercise[sheetExercise.id].orEmpty() +
                uiState.repeatDaysByExercise[sheetExercise.id].orEmpty(),
            initialRepeatWeekly =
                uiState.repeatDaysByExercise[sheetExercise.id]?.isNotEmpty() == true,
            isSubmitting = uiState.isSubmittingAdd,
            onDismissRequest = viewModel::onDismissAddToPlanSheet,
            onSubmit = { days, sets, reps, durationMin, alsoRepeat ->
                viewModel.onSubmitAddToPlan(
                    exerciseId = sheetExercise.id,
                    selectedDays = days,
                    targetSets = sets,
                    targetReps = reps,
                    targetDurationMin = durationMin,
                    alsoRepeatWeekly = alsoRepeat,
                )
            },
        )
    }
}

// ---------------- 周计划 ----------------

@Composable
private fun PlanSection(
    uiState: TrainUiState,
    onSelectDay: (Int) -> Unit,
    onDeletePlan: (Long) -> Unit,
    onResetPlan: (Long) -> Unit,
    onAddPlan: () -> Unit,
    onEditPlan: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
            ) {
                WEEKDAY_SHORT_RES.forEachIndexed { index, labelRes ->
                    val day = index + 1
                    FilterChip(
                        selected = uiState.selectedDay == day,
                        onClick = { onSelectDay(day) },
                        label = { Text(text = stringResource(labelRes)) },
                    )
                }
            }
            IconButton(onClick = onAddPlan) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_add),
                )
            }
        }

        // 这一页编辑的永远是当前周，但页面上以前一个字都没写 —— 从别处翻着周跳过来时看不出来。
        Text(
            text = stringResource(R.string.label_week_this, weekRangeText(uiState.weekStartEpochDay)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (uiState.plans.isEmpty()) {
            EmptyState(
                text = stringResource(R.string.empty_plan),
                actionText = stringResource(R.string.action_create),
                onAction = onAddPlan,
            )
        } else {
            uiState.plans.forEach { plan ->
                PlanRow(
                    plan = plan,
                    exerciseName = uiState.exerciseNameById[plan.exerciseId].orEmpty(),
                    onClick = { onEditPlan(plan.id) },
                    onDelete = { onDeletePlan(plan.id) },
                    onReset = { onResetPlan(plan.id) },
                )
            }
        }
    }
}

@Composable
private fun PlanRow(
    plan: WeekPlan,
    exerciseName: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = IronHabitSpacing.lg, vertical = IronHabitSpacing.md),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = exerciseName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // 行级「已改」角标：被用户改过的行，AI 生成时整行跳过（schema-v2 §6.2）。
                    if (plan.isUserEdited) {
                        Text(
                            text = stringResource(R.string.label_user_edited),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = IronHabitSpacing.sm),
                        )
                    }
                }
                // 操作：恢复推荐（仅用户改过）/ 编辑 / 删除
                if (plan.isUserEdited) {
                    TextButton(onClick = onReset) {
                        Text(text = stringResource(R.string.action_reset_recommended))
                    }
                }
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.action_edit_plan),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                    )
                }
            }
            Text(
                text = planGoalText(
                    sets = plan.targetSets,
                    reps = plan.targetReps,
                    weightKg = plan.targetWeightKg,
                    durationMin = plan.targetDurationMin,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------- 动作库 ----------------

@Composable
private fun LibrarySection(
    uiState: TrainUiState,
    onAddExercise: () -> Unit,
    onOpenExercise: (Long) -> Unit,
    onOpenAddToPlan: (Exercise) -> Unit,
) {
    // v6 起动作无"停用"概念（开关已下线，数据库列退化为永真标记），动作库即全量动作。
    if (uiState.exercises.isEmpty()) {
        EmptyState(
            text = stringResource(R.string.empty_exercise),
            actionText = stringResource(R.string.action_create),
            onAction = onAddExercise,
        )
        return
    }

    // 来源筛选：全部 / 内置 / 自建 / AI 推荐
    var sourceFilter by rememberSaveable { mutableStateOf(ExerciseSource.BUILT_IN) }
    var showAll by rememberSaveable { mutableStateOf(true) }
    // 肌群筛选（方案 A）：空串 = 全部；chips 数据驱动自动作的主肌群，不动数据库
    var muscleFilter by rememberSaveable { mutableStateOf("") }
    // 搜索（v7）：动作名 / 肌群子串。库里动作一多，只靠分类滚动就找不到东西。
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val visibleExercises = remember(uiState.exercises, sourceFilter, showAll) {
        if (showAll) uiState.exercises else uiState.exercises.filter { it.source == sourceFilter }
    }
    val searchedExercises = remember(visibleExercises, searchQuery) {
        searchExercises(visibleExercises, searchQuery)
    }
    val muscleOptions = remember(searchedExercises) { distinctMuscleGroups(searchedExercises) }
    val filteredExercises = remember(searchedExercises, muscleFilter) {
        filterExercisesByMuscle(searchedExercises, muscleFilter)
    }

    // LazyColumn：40+ 动作不再全量组合，滚动/重组只处理可见项
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        item(key = "library-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.section_exercise_library),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddExercise) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.action_add_exercise),
                        modifier = Modifier.padding(start = IronHabitSpacing.xs),
                    )
                }
            }
        }
        item(key = "source-filter") {
            SourceFilterRow(
                showAll = showAll,
                selected = sourceFilter,
                onSelectAll = { showAll = true },
                onSelect = { source ->
                    showAll = false
                    sourceFilter = source
                },
            )
        }
        item(key = "exercise-search") {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(text = stringResource(R.string.hint_search_exercise)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IronHabitSpacing.xs),
            )
        }
        if (muscleOptions.isNotEmpty()) {
            item(key = "muscle-filter") {
                MuscleFilterRow(
                    selected = muscleFilter,
                    options = muscleOptions,
                    onSelect = { muscleFilter = it },
                )
            }
        }
        ExerciseCategory.entries.forEach { category ->
            val exercises = filteredExercises.filter { it.category == category }
            if (exercises.isNotEmpty()) {
                item(key = "category-${category.name}") {
                    CategoryHeader(text = stringResource(categoryLabelRes(category)))
                }
                items(exercises, key = { it.id }) { exercise ->
                    val plannedDays: Set<Int> =
                        uiState.plannedDaysByExercise[exercise.id].orEmpty() +
                            uiState.repeatDaysByExercise[exercise.id].orEmpty()
                    ExerciseRow(
                        exercise = exercise,
                        isInPlan = plannedDays.isNotEmpty(),
                        onClick = { onOpenExercise(exercise.id) },
                        onAddToPlan = { onOpenAddToPlan(exercise) },
                    )
                }
            }
        }
        if (filteredExercises.isEmpty()) {
            // 有动作但筛空了：必须说"没找到"，否则界面只剩标题与筛选条，像坏了。
            item(key = "library-no-match") {
                Text(
                    text = stringResource(R.string.empty_exercise_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(IronHabitSpacing.xs),
                )
            }
        }
    }
}

/**
 * 动作来源筛选条：全部 / 内置 / 自建 / AI 推荐。
 *
 * 目的：用户自建与 AI 推荐的动作混在 40+ 个内置动作里很难找，这里给一个一键筛出。
 */
@Composable
private fun SourceFilterRow(
    showAll: Boolean,
    selected: ExerciseSource,
    onSelectAll: () -> Unit,
    onSelect: (ExerciseSource) -> Unit,
) {
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(IronHabitSpacing.sm)) {
        FilterChip(
            selected = showAll,
            onClick = onSelectAll,
            label = { Text(text = stringResource(R.string.label_filter_all)) },
        )
        ExerciseSource.entries.forEach { source ->
            FilterChip(
                selected = !showAll && selected == source,
                onClick = { onSelect(source) },
                label = { Text(text = stringResource(exerciseSourceLabelRes(source))) },
            )
        }
    }
}

@Composable
private fun CategoryHeader(text: String) {
    Column {
        HorizontalDivider()
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = IronHabitSpacing.sm, bottom = IronHabitSpacing.xs),
        )
    }
}

/**
 * 肌群筛选条（方案 A）：`全部 / 腿部 / 胸部 / …`。
 *
 * chips 数据驱动自动作的**主肌群**（[Exercise.primaryMuscleGroup]，数据库现成字段），
 * 不是硬编码枚举——新增动作带新肌群时 chips 自动出现；选中后列表只剩该肌群的动作，
 * 「加动作到计划」从滚 5 屏变成两步直达。
 */
@Composable
private fun MuscleFilterRow(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        FilterChip(
            selected = selected.isBlank(),
            onClick = { onSelect("") },
            label = { Text(text = stringResource(R.string.label_filter_all)) },
        )
        options.forEach { muscle ->
            FilterChip(
                selected = selected == muscle,
                onClick = { onSelect(muscle) },
                label = { Text(text = muscle) },
            )
        }
    }
}

/**
 * 动作库行（v6）：行尾不再是「启用 / 停用」开关，而是「加入计划」入口 ——
 * 未加入显示 `+`，已加入（本周生效计划或「每周相同」里有它）显示对勾 `✓`；
 * 两者点击都打开 [AddToPlanSheet]（`✓` = 查看并增减已排的天）。
 */
@Composable
private fun ExerciseRow(
    exercise: Exercise,
    isInPlan: Boolean,
    onClick: () -> Unit,
    onAddToPlan: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = IronHabitSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = exercise.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (exercise.hasVisibleSourceChip()) {
                    ExerciseSourceChip(source = exercise.source)
                }
            }
            val muscle = exercise.primaryMuscleGroup
            if (!muscle.isNullOrBlank()) {
                Text(
                    text = muscle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onAddToPlan) {
            Icon(
                imageVector = if (isInPlan) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = stringResource(
                    if (isInPlan) R.string.cd_already_in_plan else R.string.cd_add_to_plan,
                ),
                tint = if (isInPlan) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// ---------------- 历史 ----------------

@Composable
private fun HistorySection(
    history: List<HistoryEntry>,
    onOpenHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
    ) {
        TextButton(onClick = onOpenHistory) {
            Text(text = stringResource(R.string.section_history))
        }

        if (history.isEmpty()) {
            EmptyState(text = stringResource(R.string.empty_history))
            return@Column
        }

        history.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = IronHabitSpacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatMonthDay(entry.epochDay),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---------------- 工具 ----------------

@StringRes
private fun categoryLabelRes(category: ExerciseCategory): Int = when (category) {
    ExerciseCategory.BODYWEIGHT -> R.string.category_bodyweight
    ExerciseCategory.STRENGTH -> R.string.category_strength
    ExerciseCategory.CARDIO -> R.string.category_cardio
    ExerciseCategory.CUSTOM -> R.string.category_custom
}

/** `epochDay` → 「M/D」（纯数字，无硬编码中文）。 */
private fun formatMonthDay(epochDay: Long): String {
    val date = LocalDate.fromEpochDays(epochDay.toInt())
    return "${date.monthNumber}/${date.dayOfMonth}"
}

private val WEEKDAY_SHORT_RES = listOf(
    R.string.weekday_short_mon,
    R.string.weekday_short_tue,
    R.string.weekday_short_wed,
    R.string.weekday_short_thu,
    R.string.weekday_short_fri,
    R.string.weekday_short_sat,
    R.string.weekday_short_sun,
)
