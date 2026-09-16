package com.ironhabit.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ironhabit.app.R

/**
 * 计划目标文案（今日页训练卡片 + 训练页计划行**共用**，保证两处口径一致）。
 *
 * 形如 `3 × 12`；当计划带目标重量时追加 `· 20kg` → `3 × 12 · 20kg`；
 * 有氧动作（无重量、带时长）追加 `· 约20分钟` → `3 × 12 · 约20分钟`。
 *
 * - `weightKg == null` 且 `durationMin == null`（自重类动作）→ 仅返回 `3 × 12`，**不**显示 `0kg` / 空白。
 * - 重量优先于时长显示（一条计划通常二者不并存）。
 * - 重量整数去掉小数点尾巴（`20.0f → "20"`），小数原样保留（`22.5f → "22.5"`）。
 * - 单位文案取自 `strings.xml`（`label_weight_kg` / `label_duration_min`），**不在 Kotlin 里硬编码**。
 *
 * @param sets 目标组数
 * @param reps 目标每组次数
 * @param weightKg 目标重量（kg）；`null` = 无重量（自重）
 * @param durationMin 目标时长（分钟）；`null` = 无时长（修复 C3：有氧动作生成时带入）
 */
@Composable
fun planGoalText(sets: Int, reps: Int, weightKg: Float?, durationMin: Int? = null): String {
    val setsReps = "$sets × $reps"
    return when {
        weightKg != null -> {
            val weightText = stringResource(R.string.label_weight_kg, formatWeight(weightKg))
            "$setsReps · $weightText"
        }

        durationMin != null -> {
            val durationText = stringResource(R.string.label_duration_min, durationMin)
            "$setsReps · $durationText"
        }

        else -> setsReps
    }
}

/**
 * `Float` 重量 → 展示数字：整数去掉小数点尾巴（`20.0f → "20"`），
 * 小数原样保留（`22.5f → "22.5"`）。与 `AddEditHabitViewModel` 对 `targetValue` 的整数化口径一致。
 */
private fun formatWeight(weightKg: Float): String =
    if (weightKg % 1f == 0f) weightKg.toLong().toString() else weightKg.toString()
