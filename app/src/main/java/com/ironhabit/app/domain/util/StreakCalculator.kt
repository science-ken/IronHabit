package com.ironhabit.app.domain.util

import com.ironhabit.app.domain.model.StreakInfo

/**
 * 连续打卡（streak）纯函数计算器。
 *
 * 输入必须是**降序**（最近的日期在前）的 epochDay 列表；函数不依赖任何 Android / IO，
 * 便于 JVM 单测（架构 §7.8、§4.2）。
 *
 * 规则（与 §4.2 链路②一致）：
 * 0. **忽略 `dateEpochDay > today` 的未来日**（统计侧加固：防御历史脏数据 / 跨时区边界），过滤后再计算。
 * 1. 过滤后列表为空 → `StreakInfo(0, 0, null)`。
 * 2. 最近一天（head）必须等于 `today` 或 `today - 1`，否则 `current = 0`
 *    （今天还没练但昨天练了 → 不立即断档）。
 * 3. 从 head 向下逐日比较：相邻差 = 1 → `current++`；差 > 1 → 中断。
 * 4. `best` = 全序列扫描出的**最长连续段**，与今天是否活跃无关，因此永久保留。
 */
object StreakCalculator {

    /**
     * @param sortedDescEpochDays 降序的活跃日期（可含重复，内部会去重再降序）
     * @param todayEpochDay 今天（由 `DateUtils.todayEpochDay` 提供）
     */
    fun calculate(sortedDescEpochDays: List<Long>, todayEpochDay: Long): StreakInfo {
        if (sortedDescEpochDays.isEmpty()) {
            return StreakInfo(current = 0, best = 0, lastActiveEpochDay = null)
        }

        // 去重 + **过滤未来日**（未来日不得进入 streak：防御「日期游标写到未来日」留下的脏数据
        // 把 head 顶成未来日、导致 current 归零；同时兜住跨时区 / 时钟偏差边界）
        // + 保证降序（防御性，输入约定已降序）
        val days = sortedDescEpochDays
            .filter { it <= todayEpochDay }
            .distinct()
            .sortedDescending()
        if (days.isEmpty()) {
            return StreakInfo(current = 0, best = 0, lastActiveEpochDay = null)
        }
        val head = days.first()

        // ---- current：仅当 head 是今天或昨天时才计 ----
        var current = 0
        if (head == todayEpochDay || head == todayEpochDay - 1L) {
            current = 1
            var previous = head
            for (index in 1 until days.size) {
                val day = days[index]
                if (previous - day == 1L) {
                    current++
                    previous = day
                } else {
                    break
                }
            }
        }

        // ---- best：全序列最长连续段（历史永久保留） ----
        var best = 0
        var run = 0
        var previousDay: Long? = null
        for (day in days) {
            run = if (previousDay != null && previousDay - day == 1L) run + 1 else 1
            if (run > best) best = run
            previousDay = day
        }

        return StreakInfo(
            current = current,
            best = best,
            lastActiveEpochDay = head,
        )
    }
}
