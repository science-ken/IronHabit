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
import com.ironhabit.app.domain.model.InputLimits
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

    // 初值是「已勾了几组」而不是「目标几组」：字段标签写的是已完成组数，填目标值会让用户
    // 什么都不改、只点保存就把没勾的组凭空标成已完成（连已完成数一起抬高）。
    // 只有"这条今天一条都没勾上（或还没有记录）"时才回落到目标组数，避免出现打不开保存的 `0`。
    val completedSetsSoFar = item.checkIn?.completedSets ?: 0
    val initialSets = if (completedSetsSoFar > 0) completedSetsSoFar.toString() else item.plan.targetSets.toString()

    var setsText by remember { mutableStateOf(initialSets) }
    var repsText by remember { mutableStateOf(item.plan.targetReps.toString()) }
    var weightText by remember { mutableStateOf(item.plan.targetWeightKg?.toString().orEmpty()) }
    var durationText by remember { mutableStateOf(item.plan.targetDurationMin?.toString().orEmpty()) }
    var notesText by remember { mutableStateOf(item.checkIn?.notes.orEmpty()) }

    // 判据与「计划表单」(`AddEditPlanViewModel.onSave`) 用同一组 `InputLimits.isValid*`，
    // 连「空 = 未填」的口径也一致。此前这里只判"能不能 parse"+「> 0」，
    // 于是 99999 组、负重量、1e6 次都能一路写进库，把容量与趋势全部拉歪。
    val form: CheckInFormValidity = checkInFormValidity(setsText, repsText, weightText, durationText)
    val setsValid: Boolean = form.setsValid
    val repsValid: Boolean = form.repsValid
    val weightValid: Boolean = form.weightValid
    val durationValid: Boolean = form.durationValid
    val formValid: Boolean = form.formValid

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // B-4：小屏 + 键盘弹起时，5 个输入框会把「保存」顶出屏幕外点不到 → 必须可滚动 + 让位键盘。
                // ⚠️ 顺序与 `MealEditSheet` / `FoodLibrarySheet`（`verticalScroll().imePadding()`）相反，
                // 这是**刻意的两种等价写法**，不是谁漏修：本写法把键盘高度算进视口（视口变矮，按钮不用滚），
                // 那种把键盘高度算进滚动内容末尾（视口不变，滚到底就露出按钮）。
                // 2026-09-22 真机在 1080×1920 上量过 `verticalScroll().imePadding()` 那一侧：
                // 开着键盘滚到底，「保存」照样点得到。所以别再"统一顺序"把另一侧改坏。
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
                        val validSets: Int? = form.sets
                        val validReps: Int? = form.reps
                        if (formValid && validSets != null && validReps != null) {
                            onSubmit(
                                validSets,
                                validReps,
                                form.weightKg,
                                form.durationMinutes,
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

/**
 * 打卡表单四个输入格的解析结果与合法性。
 *
 * 抽成顶层纯函数**只为了能单测**：这段判据以前直接长在 composable 体内，于是"把打卡表单
 * 也改成走 [InputLimits]"这件事本身一条测试都没有 —— 另外三个同类表单各有一份
 * `*InputLimitsTest`，唯独这里连测试目录都没有。2026-09-20 那一轮收的正是"约定靠自觉、
 * 一个一个调用点漏"这条根因，结果洞留在了它自己改的那个文件上。
 */
internal data class CheckInFormValidity(
    val sets: Int?,
    val reps: Int?,
    val weightKg: Float?,
    val durationMinutes: Int?,
    val setsValid: Boolean,
    val repsValid: Boolean,
    val weightValid: Boolean,
    val durationValid: Boolean,
) {
    val formValid: Boolean get() = setsValid && repsValid && weightValid && durationValid
}

/**
 * 「空 = 未填」的口径与计划表单一致：负重、时长留空合法（存 `null`），
 * 组数与次数是必填项，留空即非法（否则会把 `null` 当 0 组写进去）。
 */
internal fun checkInFormValidity(
    setsText: String,
    repsText: String,
    weightText: String,
    durationText: String,
): CheckInFormValidity {
    val sets: Int? = setsText.trim().toIntOrNull()
    val reps: Int? = repsText.trim().toIntOrNull()
    val weight: Float? = weightText.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
    val duration: Int? = durationText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    return CheckInFormValidity(
        sets = sets,
        reps = reps,
        weightKg = weight,
        durationMinutes = duration,
        setsValid = sets != null && InputLimits.isValidSets(sets),
        repsValid = reps != null && InputLimits.isValidReps(reps),
        weightValid = weightText.isBlank() || (weight != null && InputLimits.isValidWeightKg(weight)),
        durationValid = durationText.isBlank() || (duration != null && InputLimits.isValidDurationMin(duration)),
    )
}
