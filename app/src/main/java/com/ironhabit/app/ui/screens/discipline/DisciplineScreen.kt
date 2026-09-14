package com.ironhabit.app.ui.screens.discipline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.HabitRow
import com.ironhabit.app.ui.components.HeatmapGrid
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState
import kotlin.math.roundToInt

/**
 * Tab3「自律」页面：习惯列表（含每条连续天数）+ 打卡热力图 + 本月小结卡片。
 *
 * @param onCreateHabit 无习惯时「去创建」回调
 * @param onEditHabit 习惯行「编辑」回调
 */
@Composable
fun DisciplineScreen(
    onCreateHabit: () -> Unit = {},
    onEditHabit: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DisciplineViewModel = hiltViewModel(),
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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

            else -> {
                SectionTitle(text = stringResource(R.string.title_habits))
                if (uiState.habits.isEmpty()) {
                    EmptyState(
                        text = stringResource(R.string.empty_habit),
                        actionText = stringResource(R.string.action_create),
                        onAction = onCreateHabit,
                    )
                } else {
                    uiState.habits.forEach { item ->
                        HabitRow(
                            item = item,
                            onToggle = { done ->
                                viewModel.onToggle(
                                    habitId = item.habit.id,
                                    epochDay = uiState.dateEpochDay,
                                    done = done,
                                )
                            },
                            onEdit = { onEditHabit(item.habit.id) },
                            onDelete = { viewModel.onDeleteHabit(item.habit.id) },
                        )
                    }
                }

                SectionTitle(text = stringResource(R.string.title_heatmap))
                HeatmapGrid(cells = uiState.heatmap)

                MonthSummaryCard(completionRate = uiState.monthCompletionRate)
            }
        }
    }
}

@Composable
private fun MonthSummaryCard(completionRate: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.title_discipline_summary),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${completionRate.roundToInt()}%",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}
