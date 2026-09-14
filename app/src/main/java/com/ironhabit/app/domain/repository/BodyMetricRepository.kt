package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.BodyMetricType
import kotlinx.coroutines.flow.Flow

/**
 * 身体数据仓库接口（P1）。
 */
interface BodyMetricRepository {

    /** 观察某类型的身体数据（按日期倒序）。 */
    fun observeByType(type: BodyMetricType): Flow<List<BodyMetric>>

    /** 获取某类型最新一条记录，不存在返回 `null`。 */
    suspend fun latest(type: BodyMetricType): BodyMetric?

    /** 新增或更新身体数据，返回行 id。 */
    suspend fun upsert(metric: BodyMetric): Long

    /** 删除身体数据。 */
    suspend fun delete(id: Long)
}
