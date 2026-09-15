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
     */
    operator fun invoke(
        epochDays: List<Long>,
        expectedWeekdays: Set<Int>? = null,
    ): StreakInfo =
        StreakCalculator.calculate(
            sortedDescEpochDays = epochDays,
            todayEpochDay = DateUtils.todayEpochDay(clock, timeZone),
            expectedWeekdays = expectedWeekdays,
        )
}
