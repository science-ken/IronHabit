package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.domain.model.CheckIn

/**
 * `CheckInEntity ⇄ domain.CheckIn` 互转。
 */
object CheckInMapper {

    /** 实体 → 领域模型。 */
    fun toDomain(entity: CheckInEntity): CheckIn = CheckIn(
        id = entity.id,
        exerciseId = entity.exerciseId,
        planId = entity.planId,
        dateEpochDay = entity.dateEpochDay,
        dateStartMillis = entity.dateStartMillis,
        completedSets = entity.completedSets,
        completedReps = entity.completedReps,
        weightKg = entity.weightKg,
        durationMinutes = entity.durationMinutes,
        notes = entity.notes,
        isQuick = entity.isQuick,
        loggedAtMillis = entity.loggedAtMillis,
        createdAt = entity.createdAt,
    )

    /** 领域模型 → 实体。 */
    fun toEntity(domain: CheckIn): CheckInEntity = CheckInEntity(
        id = domain.id,
        exerciseId = domain.exerciseId,
        planId = domain.planId,
        dateEpochDay = domain.dateEpochDay,
        dateStartMillis = domain.dateStartMillis,
        completedSets = domain.completedSets,
        completedReps = domain.completedReps,
        weightKg = domain.weightKg,
        durationMinutes = domain.durationMinutes,
        notes = domain.notes,
        isQuick = domain.isQuick,
        loggedAtMillis = domain.loggedAtMillis,
        createdAt = domain.createdAt,
    )
}
