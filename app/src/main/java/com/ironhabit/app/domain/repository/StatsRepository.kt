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

    /**
     * 近 [days] 天各动作分类的打卡占比。
     *
     * 区间参数与 [trendPoints] 对齐：同一屏的两张图必须说同一段时间（以前饼图是全历史累计）。
     */
    suspend fun categoryShare(days: Int): List<CategoryShare>

    /** 近 [days] 天的热力图数据（含无打卡的日期补 0）。 */
    suspend fun heatmap(days: Int): List<HeatmapCell>

    /**
     * 指定**闭区间**的热力图数据（含无打卡的日期补 0）。
     *
     * [heatmap] 只能表达"近 N 天到今天"，而今日页的热力条跟着日期游标翻周 ——
     * 上周、下周都落在今天之外，必须按区间取。
     */
    suspend fun heatmapRange(startEpochDay: Long, endEpochDay: Long): List<HeatmapCell>

    /**
     * 区间完成率（百分比 `0f..100f`）。
     * 定义：区间内有 ≥1 条打卡的天数 / 区间总天数 × 100。
     */
    suspend fun completionRate(startEpochDay: Long, endEpochDay: Long): Float
}
