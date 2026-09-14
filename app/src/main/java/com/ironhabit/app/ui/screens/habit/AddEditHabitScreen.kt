package com.ironhabit.app.ui.screens.habit

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.HabitFrequency
import com.ironhabit.app.ui.components.EmptyState
import com.ironhabit.app.ui.components.LoadingSkeleton
import com.ironhabit.app.ui.components.LocalSnackbarHostState

/**
 * 「新增 / 编辑习惯」表单页：名称 / emoji / 主题色 / 频率 / 重复日 / 提醒。
 *
 * 提醒时间用系统 [TimePickerDialog]；保存后由 ViewModel 联动 `ReminderScheduler` 排期或取消。
 *
 * @param onSaved 保存成功回调（导航 `popBackStack()`）
 * @param onCancel 取消回调（导航 `popBackStack()`）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditHabitScreen(
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddEditHabitViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    val context = LocalContext.current

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
                if (uiState.isEditing) R.string.title_edit_habit else R.string.title_add_habit,
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
                    label = { Text(text = stringResource(R.string.hint_habit_name)) },
                    isError = uiState.nameErrorRes != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                uiState.nameErrorRes?.let { res ->
                    FieldError(text = stringResource(res))
                }

                OutlinedTextField(
                    value = uiState.emoji,
                    onValueChange = viewModel::onEmojiChange,
                    label = { Text(text = stringResource(R.string.hint_habit_emoji)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.hint_habit_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HabitColorPicker(
                    selectedHex = uiState.colorHex,
                    onSelect = viewModel::onColorChange,
                )

                Text(
                    text = stringResource(R.string.hint_frequency),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    HabitFrequency.entries.forEachIndexed { index, frequency ->
                        SegmentedButton(
                            selected = uiState.frequency == frequency,
                            onClick = { viewModel.onFrequencyChange(frequency) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = HabitFrequency.entries.size,
                            ),
                            label = { Text(text = stringResource(frequencyLabelRes(frequency))) },
                        )
                    }
                }

                if (uiState.frequency == HabitFrequency.WEEKLY) {
                    Text(
                        text = stringResource(R.string.hint_weekly_days),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    WeeklyDaysPicker(
                        mask = uiState.weeklyDaysMask,
                        onToggle = viewModel::onToggleWeekday,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.label_reminder_switch),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Switch(
                        checked = uiState.reminderEnabled,
                        onCheckedChange = viewModel::onReminderEnabledChange,
                    )
                }

                if (uiState.reminderEnabled) {
                    ReminderTimeRow(
                        hour = uiState.reminderHour,
                        minute = uiState.reminderMinute,
                        onClick = {
                            showTimePicker(context, uiState.reminderHour, uiState.reminderMinute) { h, m ->
                                viewModel.onReminderTimeChange(h, m)
                            }
                        },
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

/** 主题色选择：预设色板圆点（颜色取自数据 hex，非组件内硬编码色值）。 */
@Composable
private fun HabitColorPicker(
    selectedHex: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HABIT_COLOR_HEXES.forEach { hex ->
            val swatch: Color = remember(hex) {
                Color(android.graphics.Color.parseColor(hex))
            }
            val selected = selectedHex.equals(hex, ignoreCase = true)
            Box(
                modifier = Modifier
                    .size(SWATCH_SIZE)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        width = if (selected) SELECTED_BORDER else 0.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(hex) },
            )
        }
    }
}

/** 重复日选择：7 个 chip 读写 `weeklyDaysMask` 位。 */
@Composable
private fun WeeklyDaysPicker(
    mask: Int,
    onToggle: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WEEKDAY_RES.forEachIndexed { index, labelRes ->
            FilterChip(
                selected = (mask shr index) and 1 == 1,
                onClick = { onToggle(index) },
                label = { Text(text = stringResource(labelRes)) },
            )
        }
    }
}

/** 提醒时间行：显示 `HH:mm`，点击弹系统时间选择器。 */
@Composable
private fun ReminderTimeRow(
    hour: Int,
    minute: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.label_reminder_time),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = formatTime(hour, minute),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
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

/** 弹出系统时间选择器。 */
private fun showTimePicker(
    context: Context,
    hour: Int,
    minute: Int,
    onPicked: (Int, Int) -> Unit,
) {
    TimePickerDialog(
        context,
        { _, pickedHour, pickedMinute -> onPicked(pickedHour, pickedMinute) },
        hour,
        minute,
        true,
    ).show()
}

/** `HH:mm`（纯数字，无硬编码中文）。 */
private fun formatTime(hour: Int, minute: Int): String =
    "%02d:%02d".format(hour, minute)

/** 频率 → 文案资源。 */
private fun frequencyLabelRes(frequency: HabitFrequency): Int = when (frequency) {
    HabitFrequency.DAILY -> R.string.label_frequency_daily
    HabitFrequency.WEEKLY -> R.string.label_frequency_weekly
}

private val HABIT_COLOR_HEXES: List<String> = listOf(
    "#2196F3",
    "#4CAF50",
    "#FF9800",
    "#E91E63",
    "#9C27B0",
    "#009688",
)

private val WEEKDAY_RES: List<Int> = listOf(
    R.string.weekday_short_mon,
    R.string.weekday_short_tue,
    R.string.weekday_short_wed,
    R.string.weekday_short_thu,
    R.string.weekday_short_fri,
    R.string.weekday_short_sat,
    R.string.weekday_short_sun,
)

private val SWATCH_SIZE = 36.dp
private val SELECTED_BORDER = 3.dp
