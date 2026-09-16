package com.ironhabit.app.data.local.dto

/**
 * 当日饮食合计的聚合投影（Room 直接映射，字段名对应 SQL 的 `AS` 别名，
 * 与既有 `StatsRaw.kt` 同处一个包、同一风格）。
 *
 * ⚠️ **空集时 `SUM()` 返回 `NULL`（非 0）** —— DAO 语句已用 `COALESCE(..., 0)` 兜底，
 * 否则新的一天（一条数据都没有）首次进入今日页时会因 NULL → Int/Double 而崩溃
 * （「每天第一次打开就崩」，只在真实装机、跨天后才暴露，见设计文档 §6.1 / 风险 2）。
 *
 * @property intakeKcal 已摄入热量（只算 `is_completed = 1` 的餐）
 * @property intakeProtein 已摄入蛋白质（只算 `is_completed = 1` 的餐）
 * @property planKcal 计划总热量（全部启用餐）
 * @property planProtein 计划总蛋白质（全部启用餐）
 */
data class MealTotalsRaw(
    val intakeKcal: Int,
    val intakeProtein: Double,
    val planKcal: Int,
    val planProtein: Double,
)
