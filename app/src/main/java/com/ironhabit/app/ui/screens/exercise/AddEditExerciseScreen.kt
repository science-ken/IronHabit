package com.ironhabit.app.ui.screens.exercise

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
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 「新增 / 编辑动作」表单页。
 *
 * 复用 [com.ironhabit.app.ui.navigation.AppRoot] 提供的 `Scaffold`（顶栏 + Snackbar 宿主）。
 * 标题在内容区自绘（顶栏标题路由映射由 T04 负责）。字段校验错误就地提示，保存结果走 Snackbar。
 *
 * @param onSaved 保存成功回调（导航 `popBackStack()`）
 * @param onCancel 取消回调（导航 `popBackStack()`）
 */
@Composable
fun AddEditExerciseScreen(
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEditExerciseViewModel = hiltViewModel(),
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
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
    ) {
        Text(
            text = stringResource(
                if (uiState.isEditing) R.string.title_edit_exercise else R.string.title_add_exercise,
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
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text(text = stringResource(R.string.hint_exercise_name)) },
                    isError = uiState.nameErrorRes != null,
                    enabled = !uiState.isBuiltIn,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                uiState.nameErrorRes?.let { res ->
                    FieldError(text = stringResource(res))
                }

                Text(
                    text = stringResource(R.string.hint_category),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CategoryChips(
                    selected = uiState.category,
                    enabled = !uiState.isBuiltIn,
                    onSelect = viewModel::onCategoryChange,
                )

                Text(
                    text = stringResource(R.string.label_muscle_group_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MuscleGroupChips(
                    selected = uiState.muscleGroups,
                    onToggle = viewModel::onToggleMuscleGroup,
                )

                OutlinedTextField(
                    value = uiState.note,
                    onValueChange = viewModel::onNoteChange,
                    label = { Text(text = stringResource(R.string.hint_exercise_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = uiState.defaultSets,
                    onValueChange = viewModel::onDefaultSetsChange,
                    label = { Text(text = stringResource(R.string.hint_default_sets)) },
                    isError = uiState.numberErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = uiState.defaultReps,
                    onValueChange = viewModel::onDefaultRepsChange,
                    label = { Text(text = stringResource(R.string.hint_default_reps)) },
                    isError = uiState.numberErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = uiState.defaultDurationSec,
                    onValueChange = viewModel::onDefaultDurationChange,
                    label = { Text(text = stringResource(R.string.hint_default_duration_sec)) },
                    isError = uiState.numberErrorRes != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                uiState.numberErrorRes?.let { res ->
                    FieldError(text = stringResource(res))
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
                        modifier = Modifier.padding(start = IronHabitSpacing.sm),
                    ) {
                        Text(text = stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}

/** 分类选择：横向可滚动的 [FilterChip] 组。 */
@Composable
private fun CategoryChips(
    selected: ExerciseCategory,
    enabled: Boolean,
    onSelect: (ExerciseCategory) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        ExerciseCategory.entries.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(category) },
                enabled = enabled,
                label = { Text(text = stringResource(categoryLabelRes(category))) },
            )
        }
    }
}

/** 肌群多选：横向可滚动的 [FilterChip] 组，**选中顺序即主→辅**（首个 = 主肌群）。 */
@Composable
private fun MuscleGroupChips(
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        PRESET_MUSCLE_GROUPS.forEach { group ->
            FilterChip(
                selected = group in selected,
                onClick = { onToggle(group) },
                label = { Text(text = group) },
            )
        }
    }
}

/** 字段级错误提示。 */
@Composable
private fun FieldError(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/** 分类 → 文案资源。 */
private fun categoryLabelRes(category: ExerciseCategory): Int = when (category) {
    ExerciseCategory.BODYWEIGHT -> R.string.category_bodyweight
    ExerciseCategory.STRENGTH -> R.string.category_strength
    ExerciseCategory.CARDIO -> R.string.category_cardio
    ExerciseCategory.CUSTOM -> R.string.category_custom
}
