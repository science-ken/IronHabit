package com.ironhabit.app.ui.screens.plan

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState

/**
 * 「新增 / 编辑计划条目」表单页。
 *
 * 字段：选择动作、星期、目标组数 / 次数 / 重量 / 时长。复用 `AppRoot` 的 `Scaffold`。
 *
 * @param onSaved 保存成功回调（导航 `popBackStack()`）
 * @param onCancel 取消回调（导航 `popBackStack()`）
 */
@Composable
fun AddEditPlanScreen(
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEditPlanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current

    val snackbarText: String? = uiState.snackbarRes?.let { res -> stringResource(res) }
    LaunchedEffect(snackbarText) {
        if (snackbarText != null) {
            snackbarHostState.showSnackbar(snackbarText)
            viewModel.onConsumeSnackbar()
        }
    }
    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            onSaved()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(
                if (uiState.isEditing) R.string.title_edit_plan else R.string.title_add_plan,
            ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 普通本地 val：委托属性不支持智能转换，需先取出再在分支内使用。
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

            else -> {
                ExercisePicker(
                    exercises = uiState.exercises,
                    selectedId = uiState.selectedExerciseId,
                    onSelect = viewModel::onSelectExercise,
                )
                uiState.exerciseErrorRes?.let { res ->
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                DayOfWeekPicker(
                    selected = uiState.dayOfWeek,
                    onSelect = viewModel::onSelectDay,
                )

                OutlinedTextField(
                    value = uiState.targetSets,
                    onValueChange = viewModel::onTargetSetsChange,
                    label = { Text(text = stringResource(R.string.hint_target_sets)) },
                    isError = uiState.numberErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.targetReps,
                    onValueChange = viewModel::onTargetRepsChange,
                    label = { Text(text = stringResource(R.string.hint_target_reps)) },
                    isError = uiState.numberErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.targetWeightKg,
                    onValueChange = viewModel::onTargetWeightChange,
                    label = { Text(text = stringResource(R.string.hint_target_weight_kg)) },
                    isError = uiState.numberErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.targetDurationMin,
                    onValueChange = viewModel::onTargetDurationChange,
                    label = { Text(text = stringResource(R.string.hint_target_duration_min)) },
                    isError = uiState.numberErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                uiState.numberErrorRes?.let { res ->
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) {
                        Text(text = stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = viewModel::onSave,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

/** 动作选择：标题 + 可选动作 chips。 */
@Composable
private fun ExercisePicker(
    exercises: List<Exercise>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
) {
    Text(
        text = stringResource(R.string.hint_pick_exercise),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        exercises.forEach { exercise ->
            FilterChip(
                selected = selectedId == exercise.id,
                onClick = { onSelect(exercise.id) },
                label = { Text(text = exercise.name) },
            )
        }
    }
}

/** 星期选择：周一..周日 chips。 */
@Composable
private fun DayOfWeekPicker(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WEEKDAY_RES.forEachIndexed { index, labelRes ->
            val day = index + 1
            FilterChip(
                selected = selected == day,
                onClick = { onSelect(day) },
                label = { Text(text = stringResource(labelRes)) },
            )
        }
    }
}

private val WEEKDAY_RES: List<Int> = listOf(
    R.string.weekday_mon,
    R.string.weekday_tue,
    R.string.weekday_wed,
    R.string.weekday_thu,
    R.string.weekday_fri,
    R.string.weekday_sat,
    R.string.weekday_sun,
)
