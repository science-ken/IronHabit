package com.ironhabit.app.domain.model

/**
 * 领域模型：训练打卡记录。
 *
 * 唯一性：同一动作同一天最多一条记录，由 `check_ins` 表的
 * `UNIQUE(exercise_id, date_epoch_day)` 约束保证「一键打卡」幂等。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property exerciseId 关联的动作 id
 * @property planId 来源计划条目 id，可空（**故意不建外键**，删除计划不抹掉历史）
 * @property dateEpochDay 日期口径 = `LocalDate.toEpochDays()`
 * @property dateStartMillis 当天 00:00 本地时间戳（区间查询/排序用）
 * @property completedSets 实际组数
 * @property completedReps 实际每组次数
 * @property weightKg 重量（kg），可空
 * @property durationMinutes 时长（分钟），可空
 * @property notes 备注，可空
 * @property isQuick 一键完成 = `true`，补录 = `false`
 * @property loggedAtMillis 实际记录时刻（UTC 毫秒）
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class CheckIn(
    val id: Long = 0L,
    val exerciseId: Long = 0L,
    val planId: Long? = null,
    val dateEpochDay: Long = 0L,
    val dateStartMillis: Long = 0L,
    val completedSets: Int = 0,
    val completedReps: Int = 0,
    val weightKg: Float? = null,
    val durationMinutes: Int? = null,
    val notes: String? = null,
    val isQuick: Boolean = false,
    val loggedAtMillis: Long = 0L,
    val createdAt: Long = 0L,
)
