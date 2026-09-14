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
 */
class CalculateStreakUseCase @Inject constructor(
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    /**
     * @param epochDays 活跃日期列表（约定降序；内部会去重再排序）
     */
    operator fun invoke(epochDays: List<Long>): StreakInfo =
        StreakCalculator.calculate(
            sortedDescEpochDays = epochDays,
            todayEpochDay = DateUtils.todayEpochDay(clock, timeZone),
        )
}
