package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.entity.WeekPlanEntity
import com.ironhabit.app.domain.model.WeekPlan

/**
 * `WeekPlanEntity ⇄ domain.WeekPlan` 互转。
 */
object PlanMapper {

    /** 实体 → 领域模型。 */
    fun toDomain(entity: WeekPlanEntity): WeekPlan = WeekPlan(
        id = entity.id,
        exerciseId = entity.exerciseId,
        dayOfWeek = entity.dayOfWeek,
        targetSets = entity.targetSets,
        targetReps = entity.targetReps,
        targetWeightKg = entity.targetWeightKg,
        targetDurationMin = entity.targetDurationMin,
        sortOrder = entity.sortOrder,
        isActive = entity.isActive,
        isUserEdited = entity.isUserEdited,
        createdAt = entity.createdAt,
    )

    /** 领域模型 → 实体。 */
    fun toEntity(domain: WeekPlan): WeekPlanEntity = WeekPlanEntity(
        id = domain.id,
        exerciseId = domain.exerciseId,
        dayOfWeek = domain.dayOfWeek,
        targetSets = domain.targetSets,
        targetReps = domain.targetReps,
        targetWeightKg = domain.targetWeightKg,
        targetDurationMin = domain.targetDurationMin,
        sortOrder = domain.sortOrder,
        isActive = domain.isActive,
        isUserEdited = domain.isUserEdited,
        createdAt = domain.createdAt,
    )
}
