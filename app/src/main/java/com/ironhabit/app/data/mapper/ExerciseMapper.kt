package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.domain.model.Exercise

/**
 * `ExerciseEntity ⇄ domain.Exercise` 互转。
 */
object ExerciseMapper {

    /** 实体 → 领域模型（`defaultDurationSec = 0` 视作未设置，转为 `null`）。 */
    fun toDomain(entity: ExerciseEntity): Exercise = Exercise(
        id = entity.id,
        name = entity.name,
        category = entity.category,
        muscleGroup = entity.muscleGroup,
        isBuiltIn = entity.isBuiltIn,
        isActive = entity.isActive,
        defaultSets = entity.defaultSets,
        defaultReps = entity.defaultReps,
        defaultDurationSec = entity.defaultDurationSec.takeIf { it > 0 },
        sortOrder = entity.sortOrder,
        timesUsed = entity.timesUsed,
        createdAt = entity.createdAt,
    )

    /** 领域模型 → 实体（可空字段回落到数据库默认值）。 */
    fun toEntity(domain: Exercise): ExerciseEntity = ExerciseEntity(
        id = domain.id,
        name = domain.name,
        category = domain.category,
        muscleGroup = domain.muscleGroup,
        isBuiltIn = domain.isBuiltIn,
        isActive = domain.isActive,
        defaultSets = domain.defaultSets ?: 0,
        defaultReps = domain.defaultReps ?: 0,
        defaultDurationSec = domain.defaultDurationSec ?: 0,
        timesUsed = domain.timesUsed,
        sortOrder = domain.sortOrder,
        createdAt = domain.createdAt,
    )
}
