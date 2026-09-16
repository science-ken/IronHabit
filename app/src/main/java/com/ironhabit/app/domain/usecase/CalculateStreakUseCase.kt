package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.StreakInfo
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.domain.util.StreakCalculator
import javax.inject.Inject
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * 连续打卡计算用例。
 *
 * 包裹纯函数 [StreakCalculator]：注入 `Clock` / `TimeZone` 取得「今天」，
 * 保证单测可用固定时钟复现（架构 §7.3、§7.8）。
 *
 * v1.10 起支持**应做日（rest-day）感知**：调用方传入「周几应做」，
 * 休息日不再打断连续记录（详见 [StreakCalculator] 的规则说明）。
 */
class CalculateStreakUseCase @Inject constructor(
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    /**
     * @param epochDays 活跃日期列表（约定降序；内部会去重再排序）
     * @param expectedWeekdays 「应做日」星期集合（`1` = 周一 … `7` = 周日）；
     *   `null` / 空集 = 每天都应做（旧行为）
     * @param asOfEpochDay **统计口径基准日**（修复 C5）：今日页可切换到历史某天，
     *   此时 streak 必须**按所选日**（游标日）计算，而不是"真实今天"。
     *   `null`（默认）= 用注入时钟的"真实今天"（旧行为，既有调用点一字不改）。
     *
     * 说明：[StreakCalculator.calculate] 内部已过滤 `> todayEpochDay` 的未来日，
     * 因此这里只需把 **游标日** 作为 `todayEpochDay` 传入即可 —— 无需改动计算器语义。
     */
    operator fun invoke(
        epochDays: List<Long>,
        expectedWeekdays: Set<Int>? = null,
        asOfEpochDay: Long? = null,
    ): StreakInfo =
        StreakCalculator.calculate(
            sortedDescEpochDays = epochDays,
            todayEpochDay = asOfEpochDay ?: DateUtils.todayEpochDay(clock, timeZone),
            expectedWeekdays = expectedWeekdays,
        )
}
