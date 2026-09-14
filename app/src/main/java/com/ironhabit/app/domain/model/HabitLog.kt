package com.ironhabit.app.domain.model

/**
 * 领域模型：习惯逐日勾选日志。
 *
 * 唯一性：同一习惯同一天最多一条记录，由 `habit_logs` 表的
 * `UNIQUE(habit_id, date_epoch_day)` 约束保证勾选幂等。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property habitId 关联的习惯 id
 * @property dateEpochDay 日期口径 = `LocalDate.toEpochDays()`
 * @property dateStartMillis 当天 00:00 本地时间戳
 * @property isCompleted 完成 = `true`，取消 = `false`
 * @property note 备注，可空
 * @property loggedAtMillis 实际记录时刻（UTC 毫秒）
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class HabitLog(
    val id: Long = 0L,
    val habitId: Long = 0L,
    val dateEpochDay: Long = 0L,
    val dateStartMillis: Long = 0L,
    val isCompleted: Boolean = true,
    val note: String? = null,
    val loggedAtMillis: Long = 0L,
    val createdAt: Long = 0L,
)
