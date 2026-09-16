package com.ironhabit.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ironhabit.app.R

/**
 * 计划目标文案（今日页训练卡片 + 训练页计划行共用）。
 *
 * 判定逻辑抽到纯函数 [planGoalSpec]（JVM 可测），本组合函数只负责把规格渲染成文案：
 * - [PlanGoalSpec.SetsReps]     → `3 × 12`
 * - [PlanGoalSpec.WithWeight]   → `3 × 12 · 20kg`
 * - [PlanGoalSpec.WithDuration] → `3 × 12 · 约20分钟`
 * - [PlanGoalSpec.DurationOnly] → `约30分钟`（**修复 D3**：有氧动作不再显示「1 × 1」前缀）
 *
 * - 自重类动作（无重量、无时长）→ 仅 `3 × 12`，**不**显示 `0kg` / 空白。
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
fun planGoalText(sets: Int, reps: Int, weightKg: Float?, durationMin: Int? = null): String =
    when (val spec = planGoalSpec(sets, reps, weightKg, durationMin)) {
        is PlanGoalSpec.SetsReps -> "${spec.sets} × ${spec.reps}"

        is PlanGoalSpec.WithWeight -> {
            val setsReps = "${spec.sets} × ${spec.reps}"
            val weightText = stringResource(R.string.label_weight_kg, formatWeight(spec.weightKg))
            "$setsReps · $weightText"
        }

        is PlanGoalSpec.WithDuration -> {
            val setsReps = "${spec.sets} × ${spec.reps}"
            val durationText = stringResource(R.string.label_duration_min, spec.durationMin)
            "$setsReps · $durationText"
        }

        is PlanGoalSpec.DurationOnly ->
            stringResource(R.string.label_duration_min, spec.durationMin)
    }

/**
 * **AI 教练计划卡片专用**渲染（沿用 v1.8 起的「N组 × M次」措辞）。
 *
 * 与 [planGoalText] **共用同一个纯判定函数** [planGoalSpec]，只有"组次"的措辞不同
 * （AI 卡片用 `3组 × 12次` 更明确）；因此「有氧只显示时长」这条规则在两侧天然一致，
 * 不会出现两套判定各自漂移。改动其一必须同步另一处。
 */
@Composable
fun aiPlanGoalText(sets: Int, reps: Int, weightKg: Float?, durationMin: Int? = null): String =
    when (val spec = planGoalSpec(sets, reps, weightKg, durationMin)) {
        is PlanGoalSpec.SetsReps ->
            stringResource(R.string.plan_goal_format, spec.sets, spec.reps)

        is PlanGoalSpec.WithWeight -> {
            val setsReps = stringResource(R.string.plan_goal_format, spec.sets, spec.reps)
            val weightText = stringResource(R.string.plan_goal_weight, formatWeight(spec.weightKg))
            "$setsReps$weightText"
        }

        is PlanGoalSpec.WithDuration -> {
            val setsReps = stringResource(R.string.plan_goal_format, spec.sets, spec.reps)
            val durationText = stringResource(R.string.plan_goal_duration, spec.durationMin)
            "$setsReps$durationText"
        }

        is PlanGoalSpec.DurationOnly ->
            stringResource(R.string.label_duration_min, spec.durationMin)
    }

/**
 * `Float` 重量 → 展示数字：整数去掉小数点尾巴（`20.0f → "20"`），
 * 小数原样保留（`22.5f → "22.5"`）。与 `AddEditHabitViewModel` 对 `targetValue` 的整数化口径一致。
 */
private fun formatWeight(weightKg: Float): String =
    if (weightKg % 1f == 0f) weightKg.toLong().toString() else weightKg.toString()
