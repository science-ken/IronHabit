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
import com.ironhabit.app.ui.components.mealTypeLabelRes

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
 * @param onSubmit 提交：餐次 / 已清洗条目 / 热量 / 蛋白质
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealEditSheet(
    meal: Meal,
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MealType.entries.forEach { type ->
                    FilterChip(
                        selected = mealType == type,
                        onClick = { mealType = type },
                        label = { Text(text = stringResource(mealTypeLabelRes(type))) },
                    )
                }
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
                    .padding(bottom = 16.dp),
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
                    modifier = Modifier.padding(start = 8.dp),
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
