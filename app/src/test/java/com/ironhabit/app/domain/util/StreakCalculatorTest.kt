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

    // ------------------------------------------------------------------
    // 应做日（rest-day）感知：v1.10 新增
    //
    // 星期参照（epochDay 0 = 1970-01-01 周四）：
    //   20_500 = 周一，20_497 = 周五，20_496 = 周四，20_495 = 周三，
    //   20_493 = 周一，20_492 = 周日，20_490 = 周五，20_488 = 周三，20_486 = 周一
    // ------------------------------------------------------------------

    /** 周一 / 周三 / 周五排期：休息日不打断，连续期按**日历跨度**计天数。 */
    @Test
    fun monWedFriSchedule_restDaysDoNotBreakStreak() {
        val monWedFri = setOf(MONDAY, WEDNESDAY, FRIDAY)
        // 两周完整出勤（周一 20486 → 周一 20500），中间只有休息日
        val activeDays = listOf(20_500L, 20_497L, 20_495L, 20_493L, 20_490L, 20_488L, 20_486L)

        assertEquals(
            // 20_486 → 20_500 共 15 天，期间没有漏掉任何应做日
            StreakInfo(current = 15, best = 15, lastActiveEpochDay = 20_500L),
            StreakCalculator.calculate(activeDays, todayEpochDay = 20_500L, expectedWeekdays = monWedFri),
        )
    }

    /** 漏掉一个应做日 → 断档（旧实现的 bug 是「按计划练也永远只有 1 天」，这里是反向保证）。 */
    @Test
    fun monWedFriSchedule_missedExpectedDayInPast_breaksCurrent() {
        val monWedFri = setOf(MONDAY, WEDNESDAY, FRIDAY)
        // 今天 = 周四 20_496；应做日周三 20_495 没打卡（且已过）→ current 归零
        assertEquals(
            StreakInfo(current = 0, best = 1, lastActiveEpochDay = 20_493L),
            StreakCalculator.calculate(
                listOf(20_493L),
                todayEpochDay = 20_496L,
                expectedWeekdays = monWedFri,
            ),
        )
    }

    /** 今天的应做日还没打卡 → 宽限到上一个应做日（等价于旧的「昨天练了就没断」）。 */
    @Test
    fun todayIsExpectedButNotDone_grantsGraceToPreviousExpectedDay() {
        val monWedFri = setOf(MONDAY, WEDNESDAY, FRIDAY)
        // 今天 = 周五 20_497 还没练；周三 20_495 与周一 20_493 都练了 → 段跨度 = 3
        assertEquals(
            StreakInfo(current = 3, best = 3, lastActiveEpochDay = 20_495L),
            StreakCalculator.calculate(
                listOf(20_495L, 20_493L),
                todayEpochDay = 20_497L,
                expectedWeekdays = monWedFri,
            ),
        )
    }

    /** 每周一次的习惯（只勾周一）：只按周一判断连续性，跨度仍是日历天数。 */
    @Test
    fun weeklyHabit_countsOnlyItsOwnWeekday() {
        // 连续 3 个周一（20_486 / 20_493 / 20_500）都完成
        assertEquals(
            StreakInfo(current = 15, best = 15, lastActiveEpochDay = 20_500L),
            StreakCalculator.calculate(
                listOf(20_500L, 20_493L, 20_486L),
                todayEpochDay = 20_500L,
                expectedWeekdays = setOf(MONDAY),
            ),
        )
    }

    /** 在非应做日加练（计划周一三五，周四也练了）：计入当前段，不打断、不扣分。 */
    @Test
    fun extraSessionOnRestDay_doesNotBreakStreak() {
        val monWedFri = setOf(MONDAY, WEDNESDAY, FRIDAY)
        // 今天 = 周四 20_496（休息日，加练）；周一 20_493 + 周三 20_495 也练了
        assertEquals(
            StreakInfo(current = 4, best = 4, lastActiveEpochDay = 20_496L),
            StreakCalculator.calculate(
                listOf(20_496L, 20_495L, 20_493L),
                todayEpochDay = 20_496L,
                expectedWeekdays = monWedFri,
            ),
        )
    }

    /** 兼容性：`expectedWeekdays` 为空集 / 全 7 天时，行为与旧的「每天都应做」完全一致。 */
    @Test
    fun emptyOrAllWeekdays_matchesLegacyDailyBehaviour() {
        val samples = listOf(
            listOf(20_500L, 20_499L, 20_498L),
            listOf(20_499L, 20_498L),
            listOf(20_497L, 20_496L),
            listOf(20_500L, 20_499L, 20_496L, 20_495L, 20_494L, 20_493L),
        )
        for (days in samples) {
            val legacy = StreakCalculator.calculate(days, today)
            assertEquals(legacy, StreakCalculator.calculate(days, today, expectedWeekdays = emptySet()))
            assertEquals(legacy, StreakCalculator.calculate(days, today, expectedWeekdays = (1..7).toSet()))
        }
    }

    private companion object {
        const val MONDAY = 1
        const val WEDNESDAY = 3
        const val FRIDAY = 5
    }
}
