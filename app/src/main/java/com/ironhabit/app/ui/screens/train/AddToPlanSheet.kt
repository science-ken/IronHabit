package com.ironhabit.app.ui.screens.train

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.InputLimits

/** 一周 7 天的短标签资源（周一 → 周日，`1..7`）。 */
private val WEEKDAY_SHORT_RES = listOf(
    R.string.weekday_short_mon,
    R.string.weekday_short_tue,
    R.string.weekday_short_wed,
    R.string.weekday_short_thu,
    R.string.weekday_short_fri,
    R.string.weekday_short_sat,
    R.string.weekday_short_sun,
)

/**
 * 「加入每周训练计划」底部弹层（动作库行尾 `+` / `✓` 的下一步，无状态组件）。
 *
 * - **星期多选**（`1..7`）：勾选 = 目标态；[initialDays] 为该动作当前已排的天（打开时预勾）。
 * - **组数 / 次数**：默认 3 × 12；范围由 [InputLimits] 口径把守，越界禁用保存。
 * - **每周都加**：勾选后把同样的目标态同步写进「每周相同」那份
 *   （以后没单独排计划的周也会有它；[initialRepeatWeekly] = 该动作已在那份里时预勾）。
 * - 写操作结果由页面级 Snackbar / 错误态承载（坑 3：弹层内的操作反馈会被 `ModalBottomSheet` 盖住）。
 * - 内容套 `heightIn` 上限 + 滚动（坑 4：小屏裁切，1080×1920 上按钮点不到）。
 *
 * @param exercise 目标动作
 * @param initialDays 该动作当前已排的星期（本周生效计划 ∪ 「每周相同」，预勾）
 * @param initialRepeatWeekly 「每周都加」复选框初值
 * @param onSubmit 提交（参数：勾选天、组数、次数、是否同步「每周相同」）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlanSheet(
    exercise: Exercise,
    initialDays: Set<Int>,
    initialRepeatWeekly: Boolean,
    onDismissRequest: () -> Unit,
    onSubmit: (days: Set<Int>, sets: Int, reps: Int, alsoRepeatWeekly: Boolean) -> Unit,
    isSubmitting: Boolean,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()

    var selectedDays by remember { mutableStateOf(initialDays) }
    var repeatWeekly by remember { mutableStateOf(initialRepeatWeekly) }
    var setsText by remember { mutableStateOf(DEFAULT_SETS.toString()) }
    var repsText by remember { mutableStateOf(DEFAULT_REPS.toString()) }

    val sets: Int? = setsText.trim().toIntOrNull()
    val reps: Int? = repsText.trim().toIntOrNull()
    val setsValid: Boolean = sets != null && sets in InputLimits.MIN_SETS..InputLimits.MAX_SETS
    val repsValid: Boolean = reps != null && reps in InputLimits.MIN_REPS..InputLimits.MAX_REPS
    val formValid: Boolean = setsValid && repsValid && selectedDays.isNotEmpty() && !isSubmitting

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 坑 4：小屏（1080×1920）上内容会超出弹层高度 → 整层可滚动，保证「确定」永远可达。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = exercise.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.title_add_to_plan),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.label_pick_days),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WEEKDAY_SHORT_RES.forEachIndexed { index, labelRes ->
                    val day = index + 1
                    FilterChip(
                        selected = day in selectedDays,
                        onClick = {
                            selectedDays = if (day in selectedDays) {
                                selectedDays - day
                            } else {
                                selectedDays + day
                            }
                        },
                        label = { Text(text = stringResource(labelRes)) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = setsText,
                    onValueChange = { setsText = it },
                    label = { Text(text = stringResource(R.string.hint_target_sets)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = sets != null && !setsValid,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { repsText = it },
                    label = { Text(text = stringResource(R.string.hint_target_reps)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = reps != null && !repsValid,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.label_also_repeat_weekly),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.hint_also_repeat_weekly),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Checkbox(
                    checked = repeatWeekly,
                    onCheckedChange = { repeatWeekly = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismissRequest,
                    enabled = !isSubmitting,
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val finalSets: Int = sets ?: return@Button
                        val finalReps: Int = reps ?: return@Button
                        onSubmit(selectedDays, finalSets, finalReps, repeatWeekly)
                    },
                    enabled = formValid,
                ) {
                    Text(
                        text = stringResource(
                            if (isSubmitting) R.string.msg_plan_creating else R.string.action_confirm,
                        ),
                    )
                }
            }
        }
    }
}

/** 组数默认值（弹层初值；与既有计划默认口径一致）。 */
private const val DEFAULT_SETS: Int = 3

/** 次数默认值。 */
private const val DEFAULT_REPS: Int = 12
