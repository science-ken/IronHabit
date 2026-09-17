package com.ironhabit.app.ui.screens.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.TodayPlanItem
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 「补录详情」底部弹层（无状态组件，**不持有 ViewModel**，由 `TodayViewModel` 消费提交结果）。
 *
 * 字段：实际组数 / 实际次数（必填整数）、重量（可空）、时长分钟（可空）、备注（可空）。
 * 数字输入使用 [KeyboardOptions] = [KeyboardType.Number]；非法输入给出 `error_invalid_number` 并禁用保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInSheet(
    item: TodayPlanItem,
    onDismissRequest: () -> Unit,
    onSubmit: (sets: Int, reps: Int, weightKg: Float?, durationMinutes: Int?, notes: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()

    var setsText by remember { mutableStateOf(item.plan.targetSets.toString()) }
    var repsText by remember { mutableStateOf(item.plan.targetReps.toString()) }
    var weightText by remember { mutableStateOf(item.plan.targetWeightKg?.toString().orEmpty()) }
    var durationText by remember { mutableStateOf(item.plan.targetDurationMin?.toString().orEmpty()) }
    var notesText by remember { mutableStateOf(item.checkIn?.notes.orEmpty()) }

    val sets: Int? = setsText.trim().toIntOrNull()
    val reps: Int? = repsText.trim().toIntOrNull()
    val weight: Float? = weightText.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
    val duration: Int? = durationText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

    val setsValid: Boolean = sets != null && sets > 0
    val repsValid: Boolean = reps != null && reps > 0
    val weightValid: Boolean = weightText.isBlank() || weight != null
    val durationValid: Boolean = durationText.isBlank() || duration != null
    val formValid: Boolean = setsValid && repsValid && weightValid && durationValid

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // B-4：小屏 + 键盘弹起时，5 个输入框会把「保存」顶出屏幕外点不到。
                // `verticalScroll` 让内容可滚动，`imePadding` 给键盘让位（其他弹层均已修，唯独这里漏了）。
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = IronHabitSpacing.xl, vertical = IronHabitSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            Text(
                text = item.exercise.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            OutlinedTextField(
                value = setsText,
                onValueChange = { setsText = it },
                label = { Text(text = stringResource(R.string.hint_completed_sets)) },
                isError = !setsValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = repsText,
                onValueChange = { repsText = it },
                label = { Text(text = stringResource(R.string.hint_completed_reps)) },
                isError = !repsValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = weightText,
                onValueChange = { weightText = it },
                label = { Text(text = stringResource(R.string.hint_weight_kg)) },
                isError = !weightValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = durationText,
                onValueChange = { durationText = it },
                label = { Text(text = stringResource(R.string.hint_duration_minutes)) },
                isError = !durationValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = notesText,
                onValueChange = { notesText = it },
                label = { Text(text = stringResource(R.string.hint_notes)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!formValid) {
                Text(
                    text = stringResource(R.string.error_invalid_number),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = IronHabitSpacing.lg),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val validSets = sets
                        val validReps = reps
                        if (formValid && validSets != null && validReps != null) {
                            onSubmit(
                                validSets,
                                validReps,
                                weight,
                                duration,
                                notesText.trim().takeIf { it.isNotEmpty() },
                            )
                        }
                    },
                    enabled = formValid,
                    modifier = Modifier.padding(start = IronHabitSpacing.sm),
                ) {
                    Text(text = stringResource(R.string.action_save))
                }
            }
        }
    }
}
