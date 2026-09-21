package com.ironhabit.app.domain.util

import com.ironhabit.app.domain.model.StreakInfo

/**
 * 连续打卡（streak）纯函数计算器 —— **应做日（rest-day）感知版**。
 *
 * 输入必须是**降序**（最近的日期在前）的 epochDay 列表；函数不依赖任何 Android / IO，
 * 便于 JVM 单测（架构 §7.8、§4.2）。
 *
 * ## 为什么需要「应做日」
 * 旧版要求**日历日严格相邻**，而本 App 自己生成的计划固定排「周一 / 周三 / 周五」
 * （见 `domain/ai/LocalRuleAdvisor`），于是**老老实实按计划训练的人，连续天数永远只有 1**；
 * 「每周三」类习惯同理。现在由调用方给出「哪几天算应做日」：休息日既不打断也不计分，
 * 但与自己排期不符的缺卡仍会断档。
 *
 * ## 规则（按顺序）
 * 0. **忽略 `dateEpochDay > today` 的未来日**（统计侧加固：防御历史脏数据 / 跨时区边界），
 *    过滤后去重；过滤后为空 → `StreakInfo(0, 0, null)`。
 * 1. **应做日**：`expectedWeekdays` 给出「周几应做」，`1` = 周一 … `7` = 周日；
 *    `null` / 空集 / 覆盖全部 7 天 → **每天都应做**（等价于旧行为，既有用例一字不改）。
 * 2. **分段**：两个活跃日之间若**不存在应做日**，它们属于同一段（中间只隔休息日 → 不断档）；
 *    若中间夹着应做日却没打卡 → 断档，另起一段。
 * 3. `current` = 当前段（含最近活跃日的那一段）的**日历跨度** = 末端活跃日 − 起点活跃日 + 1。
 *    这样「连续 N 天」在本 App 的排期下依然是**天数**（而不是训练次数），且「每天应做」的场景
 *    与旧算法完全等价。
 *    宽限：**今天本身就是应做日但还没打卡 → 不立即断档**，向前顺延到上一个应做日继续判定
 *    （对应旧规则「今天没练但昨天练了 → 不归零」）；若上一个应做日也没打卡 → `current = 0`。
 * 4. `best` = 全历史各段跨度的**最大值**，与今天是否活跃无关，因此永久保留。
 * 5. `lastActiveEpochDay` = 过滤后最新的活跃日（`null` 仅当过滤后为空）。
 * 6. 在**非应做日**加练（例如计划周一三五、周二也练了）：该日计入所在段，**不打断**也不扣分。
 */
object StreakCalculator {

    /**
     * @param sortedDescEpochDays 降序的活跃日期（可含重复，内部会去重再升序）
     * @param todayEpochDay 今天（由 `DateUtils.todayEpochDay` 提供）
     * @param expectedWeekdays 「应做日」星期集合（`1` = 周一 … `7` = 周日）；
     *   `null` / 空集 / 全 7 天 = 每天都应做（旧行为）
     */
    fun calculate(
        sortedDescEpochDays: List<Long>,
        todayEpochDay: Long,
        expectedWeekdays: Set<Int>? = null,
    ): StreakInfo {
        if (sortedDescEpochDays.isEmpty()) {
            return StreakInfo(current = 0, best = 0, lastActiveEpochDay = null)
        }

        // 去重 + **过滤未来日**（未来日不得进入 streak：防御「日期游标写到未来日」留下的脏数据
        // 把 head 顶成未来日、导致 current 归零；同时兜住跨时区 / 时钟偏差边界）+ 升序。
        val days: List<Long> = sortedDescEpochDays
            .filter { it <= todayEpochDay }
            .distinct()
            .sorted()
        if (days.isEmpty()) {
            return StreakInfo(current = 0, best = 0, lastActiveEpochDay = null)
        }

        val expected: Set<Int> = expectedWeekdays
            ?.filter { it in MIN_WEEKDAY..MAX_WEEKDAY }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: ALL_WEEKDAYS
        val activeDays: Set<Long> = days.toHashSet()
        val lastActive: Long = days.last()

        // ---- 分段 + best ----
        val runs: MutableList<RunSpan> = ArrayList()
        for (day in days) {
            val open: RunSpan? = runs.lastOrNull()
            if (open != null && !hasExpectedBetween(open.last, day, expected)) {
                open.last = day
            } else {
                runs.add(RunSpan(first = day, last = day))
            }
        }
        var best = 0
        for (run in runs) {
            val span = (run.last - run.first + 1).toInt()
            if (span > best) best = span
        }

        // ---- current：最近活跃日必须落在「最近的应做日」上（今天还没打卡则宽限到上一个应做日）----
        val latestExpected: Long = latestExpectedOnOrBefore(todayEpochDay, expected)
        val anchor: Long? = when {
            activeDays.contains(latestExpected) -> latestExpected
            // 今天本身是应做日且尚未打卡 → 宽限（不立即断档）
            latestExpected == todayEpochDay -> previousExpectedBefore(latestExpected, expected)
            // 应做日已过且未打卡（或今天休息、上一个应做日缺卡）→ 断档
            else -> null
        }
        if (anchor == null || !activeDays.contains(anchor)) {
            return StreakInfo(current = 0, best = best, lastActiveEpochDay = lastActive)
        }

        // anchor 本身是活跃日，必然落在某一段内
        val currentRun: RunSpan = runs.last { run -> run.first <= anchor && anchor <= run.last }
        val current = (currentRun.last - currentRun.first + 1).toInt()
        return StreakInfo(
            current = current,
            best = maxOf(best, current),
            lastActiveEpochDay = lastActive,
        )
    }

    /** `day` 当天或之前**最近**的一个应做日（任何 7 天窗口必含全部星期，故循环必有命中）。 */
    private fun latestExpectedOnOrBefore(day: Long, expected: Set<Int>): Long {
        var cursor = day
        repeat(WEEK_DAYS) {
            if (DateUtils.weekdayMon1(cursor) in expected) return cursor
            cursor--
        }
        return day
    }

    /** 严格早于 `day` 的最近应做日；`expected` 非空时必有命中。 */
    private fun previousExpectedBefore(day: Long, expected: Set<Int>): Long? {
        var cursor = day - 1
        repeat(WEEK_DAYS) {
            if (DateUtils.weekdayMon1(cursor) in expected) return cursor
            cursor--
        }
        return null
    }

    /**
     * `from`（不含）与 `to`（不含）之间是否存在应做日 —— 用于判定两次活跃是否「同段」。
     *
     * 跨度 > 7 天时中间必然包含全部 7 个星期，直接返回 `true`（同时避免长间隔的线性扫描）。
     */
    private fun hasExpectedBetween(from: Long, to: Long, expected: Set<Int>): Boolean {
        val gap = to - from
        if (gap <= 1L) return false
        if (gap > WEEK_DAYS) return true
        var cursor = from + 1
        while (cursor < to) {
            if (DateUtils.weekdayMon1(cursor) in expected) return true
            cursor++
        }
        return false
    }

    /** 内部用的「未中断段」；`last` 可变以避免逐日重新分配。 */
    private class RunSpan(var first: Long, var last: Long)

    private const val WEEK_DAYS = 7
    private const val MIN_WEEKDAY = 1
    private const val MAX_WEEKDAY = 7

    /** 每天都应做（`expectedWeekdays` 未给出时的默认）。 */
    private val ALL_WEEKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7)
}

/**
 * 计划行里的 `dayOfWeek` 列表 → [StreakCalculator] 要的「应做日」集合；
 * 一个都没有（没排过计划）→ `null`，让计算器走它文档里的默认口径（每天都应做），
 * 不给没排过计划的用户换一套规则。
 *
 * ⚠️ 收在这里而不是各调用方自己写一遍：今日页与教练页算的是**同一个**「连续 N 天」，
 * 两边各自归一化迟早会漂（历史上就漂过一次，教练侧直接传了 `null`，
 * 于是同一份数据一屏写 6 天、另一屏写 0 天）。
 * 取哪些行进这个列表（哪几周、要不要模板行）仍由调用方决定 —— 这里只管归一化。
 */
fun List<Int>.toExpectedWeekdaysOrNull(): Set<Int>? =
    filter { it in 1..7 }.toSet().takeIf { it.isNotEmpty() }
