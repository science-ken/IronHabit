package com.ironhabit.app.data.local.dto

import androidx.room.ColumnInfo

/**
 * 「每个动作最近一次完成情况」的聚合投影（与 [com.ironhabit.app.domain.ai.ExerciseProgress] 1:1）。
 *
 * 由 `CheckInDao.observeLatestPerExercise()` 的一条聚合 SQL 产出，供本地规则引擎做
 * **渐进超负荷**判定（**不新增表、不加迁移、不改 `VERSION`**）。
 *
 * @property exerciseId 动作 id
 * @property lastSetsCompleted 最近一次完成组数
 * @property lastTargetSets 最近一次目标组数（来自关联计划；**无计划时为 `null`**，仓库层保持原样不再兜底，
 *   由规则层把 null 解释为"未做满 → 维持"，避免误加重）
 * @property lastRpe 最近一次 RPE（`1..10`），未评级为 `null`
 * @property lastWeightKg 最近一次使用重量；自重动作为 `null`
 */
data class ExerciseProgressRaw(
    @ColumnInfo(name = "exerciseId") val exerciseId: Long,
    @ColumnInfo(name = "lastSetsCompleted") val lastSetsCompleted: Int,
    @ColumnInfo(name = "lastTargetSets") val lastTargetSets: Int?,
    @ColumnInfo(name = "lastRpe") val lastRpe: Int?,
    @ColumnInfo(name = "lastWeightKg") val lastWeightKg: Float?,
)
