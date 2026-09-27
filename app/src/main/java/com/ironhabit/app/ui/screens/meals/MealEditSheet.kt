package com.ironhabit.app.ui.screens.meals

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.usecase.MealSlotState
import com.ironhabit.app.ui.components.mealTypeLabelRes
import com.ironhabit.app.ui.theme.IronHabitSpacing

/**
 * 「编辑这一餐」底部弹层（无状态组件，**不持有 ViewModel**，由 `TodayViewModel` 消费提交结果）。
 *
 * 对应产品基线里的「编辑一餐内容」入口：吃完发现规则生成的分量与实物不符时，
 * 直接改条目 / 热量 / 蛋白质——改动会置 `isUserEdited = true`，**重新生成时整行跳过**
 * （见 [com.ironhabit.app.domain.usecase.UpsertMealUseCase]）。
 *
 * 校验（与其余表单同一套口径）：
 * - 条目：**至少一条非空**（空行自动丢弃，与 `MealMapper` 同口径）；
 * - 热量 / 蛋白质：必须能解析，且落在 [InputLimits] 的区间内（不合法则禁用保存并提示）。
 *
 * @param meal 正在编辑的那一餐（打开弹层时的快照）
 * @param slotStates 该日各餐次槽位的占用情况（决定哪颗 chip 能点，见 [MealSlotState]）。
 *        缺省全 `FREE` 只用于预览/测试 —— 真实调用方必须给，否则四颗又全可点，
 *        等于把"点了必然报错"那条路原样留着。
 * @param onSubmit 提交：餐次 / 已清洗条目 / 热量 / 蛋白质
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealEditSheet(
    meal: Meal,
    slotStates: Map<MealType, MealSlotState>,
    onDismissRequest: () -> Unit,
    onSubmit: (mealType: MealType, items: List<String>, kcal: Int, proteinG: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState()

    var mealType by remember { mutableStateOf(meal.mealType) }
    var itemsText by remember { mutableStateOf(meal.items.joinToString(separator = "\n")) }
    var kcalText by remember { mutableStateOf(meal.kcal.toString()) }
    var proteinText by remember {
        mutableStateOf(if (meal.proteinG <= 0.0) "" else formatProtein(meal.proteinG))
    }

    // 条目清洗口径与 MealMapper / UseCase 一致：去空白、丢空行。
    val items: List<String> = itemsText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val kcal: Int? = kcalText.trim().toIntOrNull()
    val protein: Double? = proteinText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    val itemsValid: Boolean = items.isNotEmpty()
    val kcalValid: Boolean = kcal != null && InputLimits.isValidMealKcal(kcal)
    // 蛋白质可留空（= 0），留空合法；填了就必须能解析且落在区间内。
    val proteinValid: Boolean = proteinText.isBlank() ||
        (protein != null && InputLimits.isValidMealProteinG(protein))
    val formValid: Boolean = itemsValid && kcalValid && proteinValid

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ 真机验证发现（1080x1920）：本弹层字段比「补录详情」多（餐次 chips + 三行条目 +
                // 两个数字 + 错误提示 + 按钮），内容高度超过弹层可用高度时会被**裁掉**——
                // 「保存 / 取消」直接够不到（uiautomator 层级里压根没有这两个节点）。
                // 故这里必须可滚动，并加 imePadding 让键盘弹出时按钮仍能滚出来。
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = IronHabitSpacing.xl, vertical = IronHabitSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(IronHabitSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.title_edit_meal),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = stringResource(R.string.label_meal_type),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IronHabitSpacing.sm),
            ) {
                MealType.entries.forEach { type ->
                    FilterChip(
                        selected = mealType == type,
                        // 被另一条活着的餐占着的槽位**不给点**：一天四餐齐全时点它必然撞
                        // `UNIQUE(date_epoch_day, meal_type)`，以前的形状是"让用户点一下,
                        // 再回头读一句报错"（审查报告 P2-1）。
                        enabled = slotStates[type] != MealSlotState.TAKEN,
                        onClick = { mealType = type },
                        label = { Text(text = stringResource(mealTypeLabelRes(type))) },
                    )
                }
            }

            // 灰掉的那颗必须说清为什么灰：只有灰 chip 没有解释，用户读到的是"这 App 点不动"。
            // 标签先整体取一张表：`joinToString` 的 transform 不是 inline，
            // 直接把 `stringResource` 写进去编译不过（Compose 调用只能在 inline/Composable 上下文里）。
            val slotLabels: Map<MealType, String> =
                MealType.entries.associateWith { type -> stringResource(mealTypeLabelRes(type)) }
            val takenLabels: String = MealType.entries
                .filter { type -> slotStates[type] == MealSlotState.TAKEN }
                .joinToString(separator = "、") { type -> slotLabels.getValue(type) }
            if (takenLabels.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.meal_slot_taken_hint, takenLabels),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 软删行也占槽，而它在列表里**看不见** —— 这一格允许点，但要点完当场说清后果：
            // 改过来会把那顿被删的餐恢复并换成现在的内容（`MealDao.upsertUser` 里那一段兑现它）。
            if (slotStates[mealType] == MealSlotState.DELETED) {
                Text(
                    text = stringResource(R.string.meal_slot_deleted_hint, slotLabels.getValue(mealType)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            OutlinedTextField(
                value = itemsText,
                onValueChange = { itemsText = it },
                label = { Text(text = stringResource(R.string.hint_meal_items)) },
                isError = !itemsValid,
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = kcalText,
                onValueChange = { kcalText = it },
                label = { Text(text = stringResource(R.string.hint_meal_kcal)) },
                isError = !kcalValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = proteinText,
                onValueChange = { proteinText = it },
                label = { Text(text = stringResource(R.string.hint_meal_protein)) },
                isError = !proteinValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!formValid) {
                Text(
                    text = stringResource(
                        if (!itemsValid) R.string.error_meal_items_empty else R.string.error_invalid_number,
                    ),
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
                        val validKcal = kcal
                        if (formValid && validKcal != null) {
                            onSubmit(mealType, items, validKcal, protein ?: 0.0)
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

/** 蛋白质回填文本：整数不带小数点（`120.0` → `120`），避免用户看到多余的小数位。 */
private fun formatProtein(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
