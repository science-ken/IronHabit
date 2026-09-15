package com.ironhabit.app.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 未来日对 streak 的影响 —— **Round-3 修复后**的正确行为取证。
 *
 * 背景：旧版本 `TodayViewModel` 的写操作（逐组/RPE/撤销/习惯/补录）落到**所选日**，
 * 若所选日是未来日，`check_ins` 里会留下未来日期活跃日；而统计侧
 * `GetTodayOverviewUseCase` 用 `observeActiveDaysSince(0)`（含未来）喂给计算器，
 * 导致 head 被顶成未来日、`current` 被误判为断档归零。
 *
 * 修复（两层防御）：
 * 1. `StreakCalculator.calculate` 先 `filter { it <= todayEpochDay }` 忽略未来日；
 * 2. `CheckInRepositoryImpl.observeActiveDaysSince` 亦过滤 `<= today`（脏数据入库侧兜底）。
 * 因此**历史脏数据中的未来打卡不再破坏 current streak**。
 *
 * 下面两个测试原本是 **characterization（记录缺陷行为）**，Round-3 已反转为断言**新的正确行为**。
 */
class StreakFutureDayImpactTest {

    private val today = 20_500L

    /**
     * ⚠️ characterization 反转：原断言「未来日成 head → current 归零」记录的是**缺陷**。
     * 修复后：未来日被过滤，head 落回今天，current 正常累计（今天+昨天 = 2）。
     */
    @Test
    fun futureDayIsFilteredSoCurrentStreakSurvives() {
        // 用户回今天看：活跃日集合里混入一个未来日（历史脏数据）
        val days = listOf(today + 7, today, today - 1) // 降序

        val info = StreakCalculator.calculate(days, todayEpochDay = today)

        // 未来日被 filter 掉 → 有效序列 [today, today-1] → head=today → current=2
        assertEquals("未来日不得破坏 current（修复：统计侧过滤 future）", 2, info.current)
        assertEquals("lastActive 落回真实最近活跃日（今天），而非未来日", today, info.lastActiveEpochDay)
    }

    /**
     * ⚠️ characterization 反转：原先断言 current=0 / best=1（把未来日当作有效活跃日）。
     * 修复后：唯一活跃日是未来日 → 过滤后为空 → 空 streak（current=0, best=0, lastActive=null）。
     */
    @Test
    fun onlyFutureDayPresentYieldsEmptyStreak() {
        val info = StreakCalculator.calculate(listOf(today + 7), todayEpochDay = today)

        assertEquals("过滤未来日后无有效活跃日 → current=0", 0, info.current)
        assertEquals("未来日不计入 best", 0, info.best)
        assertNull("无有效活跃日 → lastActive 为 null", info.lastActiveEpochDay)
    }

    /** 对照组：没有未来日时行为保持正常（今天+昨天+前天 = 3）。 */
    @Test
    fun normalTodayAndYesterdayKeepsStreak() {
        val info = StreakCalculator.calculate(listOf(today, today - 1, today - 2), todayEpochDay = today)

        assertEquals(3, info.current)
    }

    /** 防御性：全空输入 → 空 streak（不得抛异常）。 */
    @Test
    fun emptyInputYieldsEmptyStreak() {
        val info = StreakCalculator.calculate(emptyList(), todayEpochDay = today)

        assertEquals(0, info.current)
        assertEquals(0, info.best)
        assertNull(info.lastActiveEpochDay)
    }
}
