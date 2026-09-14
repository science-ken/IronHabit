package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.CategoryShare
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.model.TrendPoint

/**
 * 统计仓库接口（把底层 raw DTO 组装为 domain 统计模型）。
 */
interface StatsRepository {

    /** 近 [days] 天的每日打卡次数趋势（含无打卡的日期补 0）。 */
    suspend fun trendPoints(days: Int): List<TrendPoint>

    /** 各动作分类的打卡占比。 */
    suspend fun categoryShare(): List<CategoryShare>

    /** 近 [days] 天的热力图数据（含无打卡的日期补 0）。 */
    suspend fun heatmap(days: Int): List<HeatmapCell>

    /**
     * 区间完成率（百分比 `0f..100f`）。
     * 定义：区间内有 ≥1 条打卡的天数 / 区间总天数 × 100。
     */
    suspend fun completionRate(startEpochDay: Long, endEpochDay: Long): Float
}
