package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.entity.BodyMetricEntity
import com.ironhabit.app.domain.model.BodyMetric

/**
 * `BodyMetricEntity ⇄ domain.BodyMetric` 互转。
 */
object BodyMetricMapper {

    /** 实体 → 领域模型。 */
    fun toDomain(entity: BodyMetricEntity): BodyMetric = BodyMetric(
        id = entity.id,
        type = entity.type,
        value = entity.value,
        unit = entity.unit,
        dateEpochDay = entity.dateEpochDay,
        dateStartMillis = entity.dateStartMillis,
        note = entity.note,
        createdAt = entity.createdAt,
    )

    /** 领域模型 → 实体。 */
    fun toEntity(domain: BodyMetric): BodyMetricEntity = BodyMetricEntity(
        id = domain.id,
        type = domain.type,
        value = domain.value,
        unit = domain.unit,
        dateEpochDay = domain.dateEpochDay,
        dateStartMillis = domain.dateStartMillis,
        note = domain.note,
        createdAt = domain.createdAt,
    )
}
