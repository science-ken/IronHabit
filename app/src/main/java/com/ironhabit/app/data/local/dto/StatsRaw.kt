package com.ironhabit.app.data.local.dto

/**
 * 聚合查询返回的原始 DTO（Room 直接映射，字段名对应 `AS` 别名）。
 */

/**
 * 「日期 → 次数」原始行（近 N 天趋势）。
 *
 * @property epochDay 日期口径
 * @property count 当天打卡次数
 */
data class DayCountRaw(
    val epochDay: Long,
    val count: Int,
)

/**
 * 分类占比原始行。
 *
 * @property category 动作分类名（`ExerciseCategory.name`）
 * @property count 该分类打卡次数
 */
data class CategoryRaw(
    val category: String,
    val count: Int,
)

/**
 * 「日期 → 次数」原始行（热力图密度）。
 *
 * @property epochDay 日期口径
 * @property count 当天打卡次数
 */
data class TrendRaw(
    val epochDay: Long,
    val count: Int,
)
