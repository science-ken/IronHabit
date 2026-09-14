package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.repository.StatsRepository
import javax.inject.Inject

/**
 * 取近 N 天打卡热力图数据用例（自律 / 历史页）。
 *
 * 直接委托 [StatsRepository.heatmap]（内部含无打卡日期补 0 与密度分级）。
 */
class GetHeatmapUseCase @Inject constructor(
    private val statsRepository: StatsRepository,
) {

    suspend operator fun invoke(days: Int = DEFAULT_DAYS): List<HeatmapCell> =
        statsRepository.heatmap(days.coerceAtLeast(1))

    companion object {
        /** 默认展示近 90 天。 */
        const val DEFAULT_DAYS: Int = 90
    }
}
