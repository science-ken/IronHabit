package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CategoryShare
import com.ironhabit.app.domain.model.PeriodComparison
import com.ironhabit.app.domain.model.TrendPoint
import com.ironhabit.app.domain.repository.StatsRepository
import javax.inject.Inject

/**
 * 统计聚合结果（供「我的」页 2 张图 + 周期对比使用）。
 *
 * @property trend 近 N 天趋势（含无打卡日期补 0）
 * @property categoryShare 各分类打卡占比
 * @property comparison 与上一个等长区间的对比
 */
data class StatsBundle(
    val trend: List<TrendPoint> = emptyList(),
    val categoryShare: List<CategoryShare> = emptyList(),
    val comparison: PeriodComparison = PeriodComparison(),
)

/**
 * 取统计数据用例。
 *
 * 组合 [StatsRepository] 的趋势 / 分类占比，并**由趋势序列自算**「当前区间 vs 上一区间」对比，
 * 避免额外给仓库接口增加计数方法。
 */
class GetStatsUseCase @Inject constructor(
    private val statsRepository: StatsRepository,
) {

    suspend operator fun invoke(days: Int = DEFAULT_DAYS): StatsBundle {
        val span = days.coerceAtLeast(1)

        // 取 2 倍区间：后 span 天为当前，前 span 天为上一区间
        val allPoints = statsRepository.trendPoints(span * 2)
        val currentTotal = allPoints.takeLast(span).sumOf { it.count }
        val previousTotal = allPoints.dropLast(span).sumOf { it.count }
        val deltaPercent = when {
            previousTotal == 0 -> if (currentTotal == 0) 0f else 100f
            else -> (currentTotal - previousTotal).toFloat() / previousTotal.toFloat() * PERCENT_SCALE
        }

        return StatsBundle(
            trend = statsRepository.trendPoints(span),
            categoryShare = statsRepository.categoryShare(),
            comparison = PeriodComparison(
                currentTotal = currentTotal,
                previousTotal = previousTotal,
                deltaPercent = deltaPercent,
            ),
        )
    }

    companion object {
        /** 默认展示近 30 天。 */
        const val DEFAULT_DAYS: Int = 30
        private const val PERCENT_SCALE: Float = 100f
    }
}
