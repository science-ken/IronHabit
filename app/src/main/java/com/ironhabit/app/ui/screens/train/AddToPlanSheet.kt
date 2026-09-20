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
import com.ironhabit.app.ui.theme.IronHabitSpacing

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
 * - **组数 / 次数 / 时长**：初值取**动作自带的默认值**（`Exercise.defaultSets/Reps/DurationSec`），
 *   动作没给默认值才回落到 3 × 12。此前这里无条件写死 3 × 12 —— 于是「硬拉 4×6」和
 *   「游泳 40 分钟」被加进计划时都会变成 3×12 且没有时长，等于把动作库里的默认值白存了。
 *   范围由 [InputLimits] 口径把守，越界禁用保存。
 * - **每周都加**：勾选后把同样的目标态同步写进「每周相同」那份
 *   （以后没单独排计划的周也会有它；[initialRepeatWeekly] = 该动作已在那份里时预勾）。
 * - 写操作结果由页面级 Snackbar / 错误态承载（坑 3：弹层内的操作反馈会被 `ModalBottomSheet` 盖住）。
 * - 内容套 `heightIn` 上限 + 滚动（坑 4：小屏裁切，1080×1920 上按钮点不到）。
 *
 * ⚠️ 确认提交会按当前三个输入框**整行覆盖**该槽位的目标值（含把时长清空）—— 与组数/次数原有
 * 语义一致，不是本次新引入的行为。
 *
 * @param exercise 目标动作（其默认组次/时长作为表单初值）
 * @param initialDays 该动作当前已排的星期（本周生效计划 ∪ 「每周相同」，预勾）
 * @param initialRepeatWeekly 「每周都加」复选框初值
 * @param onSubmit 提交（参数：勾选天、组数、次数、目标时长分钟（`null` = 不设）、是否同步「每周相同」）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlanSheet(
    exercise: Exercise,
    initialDays: Set<Int>,
    initialRepeatWeekly: Boolean,
    onDismissRequest: () -> Unit,
    onSubmit: (days: Set<Int>, sets: Int, reps: Int, durationMin: Int?, alsoRepeatWeekly: Boolean) -> Unit,
    isSubmitting: Boolean,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()

    var selectedDays by remember { mutableStateOf(initialDays) }
    var repeatWeekly by remember { mutableStateOf(initialRepeatWeekly) }
    // 键上动作 id：换一个动作重开弹层时初值必须重算，否则留着上一个动作的数字。
    var setsText by remember(exercise.id) {
        mutableStateOf((exercise.defaultSets ?: DEFAULT_SETS).toString())
    }
    var repsText by remember(exercise.id) {
        mutableStateOf((exercise.defaultReps ?: DEFAULT_REPS).toString())
    }
    // 动作没有默认时长（力量/自重动作通常是 `null`）→ 留空 = 不设目标时长，落 `null` 而非 0。
    var durationText by remember(exercise.id) { mutableStateOf(initialDurationText(exercise)) }

    val sets: Int? = setsText.trim().toIntOrNull()
    val reps: Int? = repsText.trim().toIntOrNull()
    val durationInput: String = durationText.trim()
    val duration: Int? = durationInput.toIntOrNull()
    val setsValid: Boolean = sets != null && sets in InputLimits.MIN_SETS..InputLimits.MAX_SETS
    val repsValid: Boolean = reps != null && reps in InputLimits.MIN_REPS..InputLimits.MAX_REPS
    // 与 [InputLimits] 的「空 = 未填」口径一致：填了就必须落在分钟合法区间。
    val durationValid: Boolean =
        durationInput.isEmpty() || (duration != null && duration in InputLimits.MIN_DURATION_MIN..InputLimits.MAX_DURATION_MIN)
    val formValid: Boolean =
        setsValid && repsValid && durationValid && selectedDays.isNotEmpty() && !isSubmitting

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
                .padding(horizontal = IronHabitSpacing.xl, vertical = IronHabitSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
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
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
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
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
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

            OutlinedTextField(
                value = durationText,
                onValueChange = { durationText = it },
                label = { Text(text = stringResource(R.string.hint_duration_minutes)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = duration != null && !durationValid,
                modifier = Modifier.fillMaxWidth(),
            )

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
                        onSubmit(selectedDays, finalSets, finalReps, duration, repeatWeekly)
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

/**
 * 「时长（分钟）」输入框的初值。
 *
 * 只有 **≥ 1 分钟** 的动作默认时长才拿来预填：计划里的 `target_duration_min` 是"这次有氧做多久"，
 * 而「侧平板支撑 / 登山跑 / 高抬腿」的 `defaultDurationSec = 45` 是**一组维持多久**，两者不是一个口径。
 * 若照 `45 / 60 = 0` 预填，`0` 落在 [InputLimits] 的合法区间（`1..600`）之外 →
 * 表单永久判为非法 → **保存键被禁用**，这几个动作反而加不进计划。不足一分钟一律留空。
 * 与 `LocalRuleAdvisor` 的"修复 C3"（有氧时长 `< MIN_DURATION_MIN` 记 `null` 不记 `0`）同口径。
 *
 * `internal` 便于 JVM 单测直接钉住上述边界，不必起 Compose。
 */
internal fun initialDurationText(exercise: Exercise): String =
    exercise.defaultDurationSec
        ?.takeIf { it >= SECONDS_PER_MINUTE }
        ?.let { (it / SECONDS_PER_MINUTE).toString() }
        .orEmpty()

/** 组数兜底值（仅当动作**没有**默认组数时用）。 */
private const val DEFAULT_SETS: Int = 3

/** 次数兜底值（仅当动作没有默认次数时用）。 */
private const val DEFAULT_REPS: Int = 12

/** 秒 → 分换算基数（`Exercise.defaultDurationSec` 是秒口径，计划目标是分钟口径）。 */
private const val SECONDS_PER_MINUTE: Int = 60
