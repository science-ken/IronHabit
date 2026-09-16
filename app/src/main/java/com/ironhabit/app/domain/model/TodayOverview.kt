package com.ironhabit.app.domain.model

/**
 * 首页「今日」聚合视图模型。
 *
 * @property dateEpochDay 视图对应日期（`LocalDate.toEpochDays()`）
 * @property plans 今日训练项
 * @property habits 今日习惯项
 * @property completedCount 已完成项数（训练 + 习惯）
 * @property totalCount 总项数（训练 + 习惯）
 * @property trainingStreak 训练连续打卡信息
 * @property plannedWeekdays **所选日所在周**已排计划的星期（`1..7`，升序；P3 按周口径）。
 *   日期栏 chip 与「这一周有没有计划」的判定都吃它 —— 回归修复：P3 重构时 ViewModel
 *   退掉了独立的 `observePlannedWeekdays()` 流并指望这里带回，但本模型此前并没有这个字段，
 *   导致日期条 chip 消失、休息日被误判成「这一周还没有训练计划」。
 */
data class TodayOverview(
    val dateEpochDay: Long = 0L,
    val plans: List<TodayPlanItem> = emptyList(),
    val habits: List<HabitItem> = emptyList(),
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val trainingStreak: StreakInfo = StreakInfo(),
    val plannedWeekdays: List<Int> = emptyList(),
)

/**
 * 今日训练项：计划条目 + 动作 + 完成状态。
 *
 * @property plan 计划条目
 * @property exercise 动作
 * @property isCompleted 今日是否已打卡
 * @property checkIn 今日打卡记录，未打卡时为 `null`
 */
data class TodayPlanItem(
    val plan: WeekPlan,
    val exercise: Exercise,
    val isCompleted: Boolean = false,
    val checkIn: CheckIn? = null,
)

/**
 * 今日习惯项：习惯 + 今日完成状态 + 连续天数。
 *
 * @property habit 习惯
 * @property isCompletedToday 今日是否已勾选
 * @property streak 该习惯的连续打卡信息
 */
data class HabitItem(
    val habit: Habit,
    val isCompletedToday: Boolean = false,
    val streak: StreakInfo = StreakInfo(),
)
