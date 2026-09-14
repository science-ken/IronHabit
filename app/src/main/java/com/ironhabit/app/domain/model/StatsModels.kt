package com.ironhabit.app.domain.model

/**
 * 统计模型集合（供图表 / 热力图 / 对比使用）。
 */

/**
 * 趋势点：某天的打卡次数。
 *
 * @property epochDay 日期口径
 * @property count 当天打卡次数
 */
data class TrendPoint(
    val epochDay: Long,
    val count: Int,
)

/**
 * 分类占比：某动作分类的打卡占比。
 *
 * @property category 动作分类
 * @property count 该分类打卡次数
 * @property ratio 占比（`0f..1f`）
 */
data class CategoryShare(
    val category: ExerciseCategory,
    val count: Int,
    val ratio: Float,
)

/**
 * 热力图单元格：日历密度网格的一格。
 *
 * @property epochDay 日期口径
 * @property count 当天打卡次数（0 表示无打卡）
 * @property level 密度等级（`0..4`，用于着色）
 */
data class HeatmapCell(
    val epochDay: Long,
    val count: Int,
    val level: Int,
)

/**
 * 周期对比：当前区间与上一区间的打卡次数比较。
 *
 * @property currentTotal 当前区间打卡次数
 * @property previousTotal 上一区间打卡次数
 * @property deltaPercent 变化百分比（正为增长，负为下降；上一区间为 0 时返回 `0f` 或 `100f`）
 */
data class PeriodComparison(
    val currentTotal: Int = 0,
    val previousTotal: Int = 0,
    val deltaPercent: Float = 0f,
)
