package com.ironhabit.app.domain.model

/**
 * 领域模型：周计划条目（某动作排在周几）。
 *
 * @property id 主键，自增；`0` 表示尚未落库
 * @property exerciseId 关联的动作 id
 * @property dayOfWeek 星期，`1` = 周一 … `7` = 周日
 * @property targetSets 目标组数
 * @property targetReps 目标每组次数
 * @property targetWeightKg 目标重量（kg），可空
 * @property targetDurationMin 目标时长（分钟），可空（有氧用）
 * @property sortOrder 当日内排序
 * @property isActive 是否启用（`false` = 停用）
 * @property createdAt 创建时间戳（UTC 毫秒）
 */
data class WeekPlan(
    val id: Long = 0L,
    val exerciseId: Long = 0L,
    val dayOfWeek: Int = 1,
    val targetSets: Int = 3,
    val targetReps: Int = 12,
    val targetWeightKg: Float? = null,
    val targetDurationMin: Int? = null,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = 0L,
)
