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
        completedSetsMask = entity.completedSetsMask,
        rpe = entity.rpe,
        completedReps = entity.completedReps,
        weightKg = entity.weightKg,
        durationMinutes = entity.durationMinutes,
        notes = entity.notes,
        isQuick = entity.isQuick,
        loggedAtMillis = entity.loggedAtMillis,
        createdAt = entity.createdAt,
    )

    /**
     * 领域模型 → 实体。
     *
     * `completed_sets` 是派生冗余列：**只出不进** —— 写回时由 `completedSetsMask` 现算
     * （`countOneBits()`），绝不独立赋值，以保证不变量
     * `completed_sets == completed_sets_mask.countOneBits()`。
     */
    fun toEntity(domain: CheckIn): CheckInEntity = CheckInEntity(
        id = domain.id,
        exerciseId = domain.exerciseId,
        planId = domain.planId,
        dateEpochDay = domain.dateEpochDay,
        dateStartMillis = domain.dateStartMillis,
        completedSets = domain.completedSetsMask.countOneBits(),
        completedSetsMask = domain.completedSetsMask,
        rpe = domain.rpe,
        completedReps = domain.completedReps,
        weightKg = domain.weightKg,
        durationMinutes = domain.durationMinutes,
        notes = domain.notes,
        isQuick = domain.isQuick,
        loggedAtMillis = domain.loggedAtMillis,
        createdAt = domain.createdAt,
    )
}
