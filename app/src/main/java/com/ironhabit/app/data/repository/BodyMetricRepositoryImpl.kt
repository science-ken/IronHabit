package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.BodyMetricDao
import com.ironhabit.app.data.local.dto.BodyTallyRaw
import com.ironhabit.app.data.mapper.BodyMetricMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.BodyMetric
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.BodyTally
import com.ironhabit.app.domain.repository.BodyMetricRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [BodyMetricRepository] 的 data 层实现。
 */
@Singleton
class BodyMetricRepositoryImpl @Inject constructor(
    private val bodyMetricDao: BodyMetricDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BodyMetricRepository {

    override fun observeByType(type: BodyMetricType): Flow<List<BodyMetric>> =
        bodyMetricDao.observeByType(type)
            .map { entities -> entities.map(BodyMetricMapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun latest(type: BodyMetricType): BodyMetric? =
        bodyMetricDao.latest(type)?.let(BodyMetricMapper::toDomain)

    override suspend fun tally(): BodyTally {
        val raw: BodyTallyRaw = bodyMetricDao.bodyTally()
        return BodyTally(rowCount = raw.rowCount, lastEpochDay = raw.lastEpochDay)
    }

    override suspend fun upsert(metric: BodyMetric): Long =
        bodyMetricDao.upsert(BodyMetricMapper.toEntity(metric))

    override suspend fun delete(id: Long) {
        bodyMetricDao.deleteById(id)
    }
}
