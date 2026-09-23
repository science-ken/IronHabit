package com.ironhabit.app.data.local.dto

/**
 * 「我的」页记录台账的原始行（Room 直接映射，字段名对应 `AS` 别名）。
 *
 * 三个都是**一行多列**的聚合，而不是"每类一行"：一行读回来最省事，
 * 也让每类的口径能各自 JOIN 自己的表（见各 DAO 里那条 SQL 的注释）。
 */

/**
 * 打卡台账：条数 + 组数 + 次数 + 带 RPE 的条数。
 *
 * 列名一律带 `Count` 后缀：`rows` / `sets` 在 SQL 里是关键词，拿它们当 `AS` 别名
 * 要么解析失败、要么Room 映射不上。
 *
 * @property rowCount 打卡记录条数（一次练 5 个动作 = 5 条）
 * @property setCount `SUM(completed_sets)`
 * @property repCount `SUM(completed_reps)`
 * @property rpeRowCount 填了 RPE 的条数（`rpe IS NOT NULL`）
 */
data class CheckInTallyRaw(
    val rowCount: Int,
    val setCount: Int,
    val repCount: Int,
    val rpeRowCount: Int,
)

/**
 * 饮食台账：餐次数 + 填了几餐 + 几条食物 + 热量 + 最后一个填过的餐次日。
 *
 * @property mealRowCount 已过去的启用餐次行数
 * @property filledMealCount 其中填过东西的餐次数（`COUNT(DISTINCT meal_id)`，
 *   一餐记 3 条也只算 1 餐）
 * @property itemCount 这些餐次下的食物条数
 * @property itemKcal 这些食物条的合计热量
 * @property lastFilledEpochDay 有食物的最晚餐次日；一条都没填过时为 `null`
 */
data class DietTallyRaw(
    val mealRowCount: Int,
    val filledMealCount: Int,
    val itemCount: Int,
    val itemKcal: Int,
    val lastFilledEpochDay: Long?,
)

/**
 * 习惯台账：在跑的习惯数 + 打卡条数 + 最近一次。
 *
 * @property activeHabits `is_active = 1` 的习惯数
 * @property completedLogs 已完成的打卡日志数（**含已删除习惯的历史**，见 SQL 注释）
 * @property lastEpochDay 最近一次完成打卡的日期；从没打过 = `null`
 */
data class HabitTallyRaw(
    val activeHabits: Int,
    val completedLogs: Int,
    val lastEpochDay: Long?,
)

/**
 * 身体数据台账。
 *
 * ⚠️ 数的是**全部 7 种指标**的行数，不是体重那一类：`BodyMetricType` 有体重 / 体脂 /
 * 骨骼肌 / 腰围 / 胸围 / 臂围 / 臀围，只数体重会让这一行说"1 条"而身体数据页列出更多。
 *
 * @property rowCount 身体数据记录条数
 * @property lastEpochDay 最近一条的日期；从没记过 = `null`
 */
data class BodyTallyRaw(
    val rowCount: Int,
    val lastEpochDay: Long?,
)
