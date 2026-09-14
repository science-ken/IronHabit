package com.ironhabit.app.ui.screens.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import kotlinx.datetime.LocalDate

/**
 * 「动作详情」页：动作信息 + 打卡历史列表 + 编辑入口。
 *
 * 复用 [com.ironhabit.app.ui.navigation.AppRoot] 的 `Scaffold`。
 *
 * @param onEdit 点击「编辑」回调（导航到动作编辑页）
 */
@Composable
fun ExerciseDetailScreen(
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 普通本地 val：委托属性不支持智能转换，需先取出再在分支内使用。
        val errorRes: Int? = uiState.errorRes
        when {
            uiState.isLoading -> {
                LoadingSkeleton()
                LoadingSkeleton()
            }

            errorRes != null -> {
                EmptyState(text = stringResource(errorRes))
            }

            else -> {
                Text(
                    text = stringResource(R.string.title_exercise_detail),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = uiState.exerciseName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(categoryLabelRes(uiState.category)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val muscle = uiState.muscleGroup
                if (!muscle.isNullOrBlank()) {
                    Text(
                        text = muscle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                Text(
                    text = stringResource(R.string.section_history),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (uiState.history.isEmpty()) {
                    EmptyState(text = stringResource(R.string.empty_history))
                } else {
                    uiState.history.forEach { checkIn ->
                        CheckInHistoryRow(checkIn = checkIn)
                    }
                }

                Button(
                    onClick = { onEdit(uiState.exerciseId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.action_edit))
                }
            }
        }
    }
}

/** 单条历史：日期 + 组×次（+ 重量）。 */
@Composable
private fun CheckInHistoryRow(checkIn: CheckIn) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatMonthDay(checkIn.dateEpochDay),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val weightSuffix = checkIn.weightKg?.let { weight -> " · ${weight}kg" }.orEmpty()
        Text(
            text = "${checkIn.completedSets} × ${checkIn.completedReps}$weightSuffix",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** `epochDay` → 「M/D」（纯数字，无硬编码中文）。 */
private fun formatMonthDay(epochDay: Long): String {
    val date = LocalDate.fromEpochDays(epochDay.toInt())
    return "${date.monthNumber}/${date.dayOfMonth}"
}

/** 分类 → 文案资源。 */
private fun categoryLabelRes(category: ExerciseCategory): Int = when (category) {
    ExerciseCategory.BODYWEIGHT -> R.string.category_bodyweight
    ExerciseCategory.STRENGTH -> R.string.category_strength
    ExerciseCategory.CARDIO -> R.string.category_cardio
    ExerciseCategory.CUSTOM -> R.string.category_custom
}
