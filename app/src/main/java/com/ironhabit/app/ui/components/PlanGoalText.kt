package com.ironhabit.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ironhabit.app.R

/**
 * 计划目标文案（今日页训练卡片 + 训练页计划行**共用**，保证两处口径一致）。
 *
 * 形如 `3 × 12`；当计划带目标重量时追加 `· 20kg` → `3 × 12 · 20kg`。
 *
 * - `weightKg == null`（自重类动作）→ 仅返回 `3 × 12`，**不**显示 `0kg` / 空白。
 * - 重量整数去掉小数点尾巴（`20.0f → "20"`），小数原样保留（`22.5f → "22.5"`）。
 * - 单位文案取自 `strings.xml`（`label_weight_kg`），**不在 Kotlin 里硬编码** `kg`。
 *
 * @param sets 目标组数
 * @param reps 目标每组次数
 * @param weightKg 目标重量（kg）；`null` = 无重量（自重）
 */
@Composable
fun planGoalText(sets: Int, reps: Int, weightKg: Float?): String {
    val setsReps = "$sets × $reps"
    if (weightKg == null) return setsReps
    val weightText = stringResource(R.string.label_weight_kg, formatWeight(weightKg))
    return "$setsReps · $weightText"
}

/**
 * `Float` 重量 → 展示数字：整数去掉小数点尾巴（`20.0f → "20"`），
 * 小数原样保留（`22.5f → "22.5"`）。与 `AddEditHabitViewModel` 对 `targetValue` 的整数化口径一致。
 */
private fun formatWeight(weightKg: Float): String =
    if (weightKg % 1f == 0f) weightKg.toLong().toString() else weightKg.toString()
