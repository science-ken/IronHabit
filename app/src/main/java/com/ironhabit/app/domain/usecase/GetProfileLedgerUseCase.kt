package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckInTally
import com.ironhabit.app.domain.model.DietTally
import com.ironhabit.app.domain.model.HabitTally
import com.ironhabit.app.domain.model.ProfileLedger
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.StatsRepository
import javax.inject.Inject

/**
 * 取「我的」页记录台账：训练 / 身体数据 / 饮食 / 习惯四类各自"记了多少、最后一次是什么时候"。
 *
 * 四条聚合各自回自己的仓库，这里只做拼装 —— 每类的 JOIN 口径不一样
 * （饮食要卡"已过去"、习惯的历史不能跟着软删一起消失、身体数据要跨 7 种指标），
 * 合成一条大 SQL 反而会把那几条注释里的例外抹平。
 *
 * @param todayEpochDay 今天（epochDay 口径）。饮食的分母要用它砍掉未来几天。
 * @param activeDays 有打卡的日期列表（`CheckInRepository.observeActiveDaysSince(0)` 的输出，
 *   已经滤过未来日）。传进来而不是在这里再读一遍：调用方那一份同时还在算连续与本周。
 */
class GetProfileLedgerUseCase @Inject constructor(
    private val statsRepository: StatsRepository,
    private val bodyMetricRepository: BodyMetricRepository,
    private val mealRepository: MealRepository,
    private val habitRepository: HabitRepository,
) {

    suspend operator fun invoke(todayEpochDay: Long, activeDays: List<Long>): ProfileLedger {
        val trainingBase: CheckInTally = statsRepository.checkInTally(ALL_TIME_START_EPOCH_DAY, todayEpochDay)
        return ProfileLedger(
            training = trainingBase.copy(
                activeDayCount = activeDays.size,
                lastEpochDay = activeDays.maxOrNull(),
            ),
            body = bodyMetricRepository.tally(),
            diet = mealRepository.dietTally(todayEpochDay),
            habits = habitRepository.tally(),
        )
    }

    companion object {
        /** 1970-01-01：早于任何可能的打卡日，等价于"全历史"。 */
        const val ALL_TIME_START_EPOCH_DAY: Long = 0L
    }
}
