package com.ironhabit.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [planGoalSpec] 纯函数单测 —— 锁死「计划目标文案」的判定口径（**零 Android / 零 Compose**）。
 *
 * 重点覆盖**修复 D3**：有氧动作（`sets ≤ 1 && reps ≤ 1` 且无重量）**只**显示时长，
 * 不再拼「1 × 1」/「1组 × 1次」前缀。
 */
class PlanGoalSpecTest {

    @Test
    fun cardioWithUnitSetsAndReps_isDurationOnly() {
        // 修复 D3 主用例：有氧动作默认 sets = 1 / reps = 1 → 只显示「约30分钟」。
        assertEquals(
            PlanGoalSpec.DurationOnly(30),
            planGoalSpec(sets = 1, reps = 1, weightKg = null, durationMin = 30),
        )
    }

    @Test
    fun nonPositiveSetsOrReps_isAlsoDurationOnly() {
        // 防御脏数据：组次为 0 / 负时同样只显示时长（避免渲染出「0 × 1」）。
        assertEquals(
            PlanGoalSpec.DurationOnly(20),
            planGoalSpec(sets = 0, reps = 1, weightKg = null, durationMin = 20),
        )
        assertEquals(
            PlanGoalSpec.DurationOnly(20),
            planGoalSpec(sets = 1, reps = 0, weightKg = null, durationMin = 20),
        )
    }

    @Test
    fun weightedStrength_isWithWeight() {
        assertEquals(
            PlanGoalSpec.WithWeight(4, 8, 60f),
            planGoalSpec(sets = 4, reps = 8, weightKg = 60f, durationMin = null),
        )
    }

    @Test
    fun multiSetsWithDuration_isWithDuration() {
        assertEquals(
            PlanGoalSpec.WithDuration(3, 12, 20),
            planGoalSpec(sets = 3, reps = 12, weightKg = null, durationMin = 20),
        )
    }

    @Test
    fun bodyweightWithoutWeightOrDuration_isSetsReps() {
        assertEquals(
            PlanGoalSpec.SetsReps(3, 15),
            planGoalSpec(sets = 3, reps = 15, weightKg = null, durationMin = null),
        )
    }

    @Test
    fun weightTakesPrecedenceOverDuration() {
        // 重量与时长并存（罕见）时重量优先；且不会被「只显示时长」规则吞掉。
        assertEquals(
            PlanGoalSpec.WithWeight(1, 1, 40f),
            planGoalSpec(sets = 1, reps = 1, weightKg = 40f, durationMin = 30),
        )
    }

    @Test
    fun unitSetsAndRepsWithoutDuration_isSetsReps() {
        // 无时长时**不得**走 DurationOnly 分支（否则会渲染空串 / 崩溃）。
        assertEquals(
            PlanGoalSpec.SetsReps(1, 1),
            planGoalSpec(sets = 1, reps = 1, weightKg = null, durationMin = null),
        )
    }
}
