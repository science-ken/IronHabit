package com.ironhabit.app.ui.screens.discipline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitItem
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.HabitRow
import com.ironhabit.app.ui.components.HeatmapGrid
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState
import com.ironhabit.app.ui.theme.IronHabitSpacing
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

    // 删除走软删（`is_active = 0`，`habit_logs` 保留），但界面上没有任何地方能再看见它
    // （所有消费方都过 `is_active = 1`）—— 所以按"不可逆"对待：点垃圾桶只把待删项挑出来，
    // 真删要等确认框的「删除」。形状与「数据备份」页的导入确认框一致。
    var habitToDelete: HabitItem? by remember { mutableStateOf(null) }

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
            .padding(IronHabitSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.lg),
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
                    // 真重试：重订阅 Room 冷流（旧实现这里是空 lambda，点了没有任何反应）
                    onAction = viewModel::onRetry,
                )
            }

            else -> {
                // 标题右侧常驻一个「+」：以前 onCreateHabit 只在空态的 EmptyState 里被调用一次，
                // 于是建完第一个习惯之后整页再没有任何添加入口 —— 而空态文案还写着
                // "点右上角「+」"，那个 + 根本不存在。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.title_habits),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onCreateHabit) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.action_add),
                        )
                    }
                }
                if (uiState.nothingToShow) {
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
                            onDelete = { habitToDelete = item },
                        )
                    }
                }

                // 已删除的习惯：软删的行一直在库里，以前只是没有任何地方能再看见它们
                // （删掉重建一条同名的会拿到新 id，老日志就此断开）。chip 在空态之外，
                // 所以"只剩已删除的"那种状态下它照样出现 —— 否则又是一次单向门。
                if (uiState.deletedHabits.isNotEmpty()) {
                    FilterChip(
                        selected = uiState.showDeleted,
                        onClick = { viewModel.onToggleDeleted() },
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.chip_habit_deleted_count,
                                    uiState.deletedHabits.size,
                                ),
                            )
                        },
                    )
                    if (uiState.showDeleted) {
                        uiState.deletedHabits.forEach { habit ->
                            HabitDeletedRow(
                                habit = habit,
                                onRestore = { viewModel.onRestoreHabit(habit.id) },
                            )
                        }
                    }
                }

                SectionTitle(text = stringResource(R.string.title_heatmap))
                HeatmapGrid(cells = uiState.heatmap)

                MonthSummaryCard(
                    completionRate = uiState.monthCompletionRate,
                    hasAnyCheckIn = uiState.hasAnyCheckIn,
                )
            }
        }
    }

    val pending: HabitItem? = habitToDelete
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { habitToDelete = null },
            title = { Text(text = stringResource(R.string.dialog_delete_habit_title)) },
            text = {
                // 报出习惯名是这条确认框的全部价值：让用户看见"我按的是哪一行的删除"。
                Text(text = stringResource(R.string.dialog_delete_habit_message, pending.habit.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        habitToDelete = null
                        viewModel.onDeleteHabit(pending.habit.id)
                    },
                ) {
                    Text(text = stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { habitToDelete = null }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun MonthSummaryCard(completionRate: Float, hasAnyCheckIn: Boolean) {
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
                .padding(IronHabitSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.xs),
        ) {
            Text(
                text = stringResource(R.string.title_discipline_summary),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!hasAnyCheckIn) {
                // 「0%」和"还没开始记"是两件事。后者用一个大号 0% 说话会被读成一个判决，
                // 而本仓库在 `WeeklyReview` 等处立过规矩：不用 0 冒充 null。
                Text(
                    text = stringResource(R.string.label_no_data),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "${completionRate.roundToInt()}%",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 已删除习惯的一行：名字压暗、右侧只留一个「恢复」。
 *
 * 不给编辑、不给勾选：这条已经不在任何统计里了，这一行要回答的只有"它还能不能回来"。
 * 恢复走 `is_active = 1`，日志一行都没动，所以连续天数与热力图原样接上。
 */
@Composable
private fun HabitDeletedRow(
    habit: Habit,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = IronHabitSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
    ) {
        Text(
            text = habit.emoji,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = habit.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.label_habit_deleted_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRestore) {
            Text(text = stringResource(R.string.action_restore))
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
