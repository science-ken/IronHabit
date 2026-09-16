package com.ironhabit.app.ui.components

/**
 * 计划目标文案的**结构化规格**（纯数据 + 纯函数，**零 Android / 零 Compose / 零 IO**）。
 *
 * 把「该显示成什么样」的**判定**从 Compose 组合里抽出来（[planGoalText] 只负责把规格渲染成文案），
 * 于是「有氧只显示时长」这类规则可以被普通 JVM 单测锁死，而不再依赖设备 / UI 测试。
 */
sealed interface PlanGoalSpec {

    /** 仅「N × M」。 */
    data class SetsReps(val sets: Int, val reps: Int) : PlanGoalSpec

    /** 「N × M · Xkg」。 */
    data class WithWeight(val sets: Int, val reps: Int, val weightKg: Float) : PlanGoalSpec

    /** 「N × M · 约D分钟」。 */
    data class WithDuration(val sets: Int, val reps: Int, val durationMin: Int) : PlanGoalSpec

    /** **只**显示时长「约D分钟」（修复 D3：有氧动作 `sets ≤ 1` 且 `reps ≤ 1` 时不再出现「1 × 1」）。 */
    data class DurationOnly(val durationMin: Int) : PlanGoalSpec
}

/**
 * 计划目标文案的**纯判定函数**（无副作用、同输入同输出）。
 *
 * 规则（优先级自上而下）：
 * 1. `durationMin != null && weightKg == null && sets <= 1 && reps <= 1` → [PlanGoalSpec.DurationOnly]
 *    （**修复 D3**：有氧动作默认 `sets = 1 / reps = 1`，旧实现会渲染成「1 × 1 · 约30分钟」，
 *    「1 × 1」噪声大且无信息量；现**只**显示「约30分钟」。`weightKg == null` 保证有重量的
 *    力量动作仍优先显示重量）；
 * 2. `weightKg != null` → [PlanGoalSpec.WithWeight]（重量优先于时长）；
 * 3. `durationMin != null` → [PlanGoalSpec.WithDuration]（既非纯时长、又无重量，如组次 + 时长）；
 * 4. 否则 → [PlanGoalSpec.SetsReps]（自重类动作，**不**显示 `0kg` / 空白）。
 *
 * @param sets 目标组数
 * @param reps 目标每组次数
 * @param weightKg 目标重量（kg）；`null` = 无重量（自重）
 * @param durationMin 目标时长（分钟）；`null` = 无时长
 */
fun planGoalSpec(sets: Int, reps: Int, weightKg: Float?, durationMin: Int?): PlanGoalSpec = when {
    durationMin != null && weightKg == null && sets <= 1 && reps <= 1 ->
        PlanGoalSpec.DurationOnly(durationMin)

    weightKg != null -> PlanGoalSpec.WithWeight(sets, reps, weightKg)

    durationMin != null -> PlanGoalSpec.WithDuration(sets, reps, durationMin)

    else -> PlanGoalSpec.SetsReps(sets, reps)
}
