package com.ironhabit.app.domain.util

import com.ironhabit.app.domain.model.StreakInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StreakCalculator] 边界单测（架构 §7.8，要求 ≥ 6 个用例）。
 *
 * 覆盖：跨天连续、今天未打卡但昨天打了、隔两天断档、补卡后回升、撤销归零、best 永久保留。
 * 全部用固定 `today`，保证可复现。
 */
class StreakCalculatorTest {

    private val today = 20_500L

    @Test
    fun emptyList_returnsZeroStreak() {
        assertEquals(
            StreakInfo(0, 0, null),
            StreakCalculator.calculate(emptyList(), today),
        )
    }

    @Test
    fun consecutiveDays_incrementsCurrent() {
        // 20500/20499/20498 连续三天
        assertEquals(
            StreakInfo(3, 3, 20_500L),
            StreakCalculator.calculate(listOf(20_500L, 20_499L, 20_498L), today),
        )
    }

    @Test
    fun notCheckedInTodayButYesterday_keepsStreak() {
        // head == today - 1 → 不立即断档
        assertEquals(
            StreakInfo(2, 2, 20_499L),
            StreakCalculator.calculate(listOf(20_499L, 20_498L), today),
        )
    }

    @Test
    fun missedTwoDays_resetsCurrentButKeepsBest() {
        // head == 20497 < today-1 → current 归零；best 保留 2
        assertEquals(
            StreakInfo(0, 2, 20_497L),
            StreakCalculator.calculate(listOf(20_497L, 20_496L), today),
        )
    }

    @Test
    fun backfillFilledGap_liftsCurrent() {
        // 补卡前：20500、20499 → current = 2
        assertEquals(
            StreakInfo(2, 2, 20_500L),
            StreakCalculator.calculate(listOf(20_500L, 20_499L), today),
        )
        // 对 20498 补卡后：序列补全 → current 回升到 3
        assertEquals(
            StreakInfo(3, 3, 20_500L),
            StreakCalculator.calculate(listOf(20_500L, 20_499L, 20_498L), today),
        )
    }

    @Test
    fun undoOnlyCheckIn_resetsToZero() {
        // 撤销唯一一次打卡 → 序列为空 → current = 0
        assertEquals(
            StreakInfo(0, 0, null),
            StreakCalculator.calculate(emptyList(), today),
        )
        // 撤销后仅剩昨天 → current = 1
        assertEquals(
            StreakInfo(1, 1, 20_499L),
            StreakCalculator.calculate(listOf(20_499L), today),
        )
    }

    @Test
    fun bestIsKeptForever_acrossGap() {
        // 近段 20500/20499（2 天），历史段 20496..20493（4 天）
        assertEquals(
            StreakInfo(2, 4, 20_500L),
            StreakCalculator.calculate(
                listOf(20_500L, 20_499L, 20_496L, 20_495L, 20_494L, 20_493L),
                today,
            ),
        )
    }
}
