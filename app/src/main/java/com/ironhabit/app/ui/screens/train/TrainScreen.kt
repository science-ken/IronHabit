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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    onAction = { /* 数据流为 Room 响应式，重进页面即刷新 */ },
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
                onToggleActive = viewModel::onToggleExerciseActive,
                onAddExercise = onAddExercise,
                onOpenExercise = onOpenExercise,
            )

            else -> HistorySection(
                history = uiState.history,
                onOpenHistory = onOpenHistory,
            )
        }
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                text = "${plan.targetSets} × ${plan.targetReps}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (plan.isUserEdited) {
            TextButton(onClick = onReset) {
                Text(text = stringResource(R.string.action_reset_recommended))
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
            )
        }
    }
}

// ---------------- 动作库 ----------------

@Composable
private fun LibrarySection(
    uiState: TrainUiState,
    onToggleActive: (Long, Boolean) -> Unit,
    onAddExercise: () -> Unit,
    onOpenExercise: (Long) -> Unit,
) {
    if (uiState.exercises.isEmpty()) {
        EmptyState(
            text = stringResource(R.string.empty_exercise),
            actionText = stringResource(R.string.action_create),
            onAction = onAddExercise,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExerciseCategory.entries.forEach { category ->
            val exercises = uiState.exercises.filter { it.category == category }
            if (exercises.isNotEmpty()) {
                CategoryHeader(text = stringResource(categoryLabelRes(category)))
                exercises.forEach { exercise ->
                    ExerciseRow(
                        exercise = exercise,
                        onClick = { onOpenExercise(exercise.id) },
                        onToggleActive = { active -> onToggleActive(exercise.id, active) },
                    )
                }
            }
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
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ExerciseRow(
    exercise: Exercise,
    onClick: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val muscle = exercise.primaryMuscleGroup
            if (!muscle.isNullOrBlank()) {
                Text(
                    text = muscle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = exercise.isActive,
            onCheckedChange = onToggleActive,
        )
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    .padding(vertical = 6.dp),
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
