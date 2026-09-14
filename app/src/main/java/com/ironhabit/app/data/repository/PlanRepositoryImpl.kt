package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.WeekPlanDao
import com.ironhabit.app.data.mapper.PlanMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.PlanRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [PlanRepository] 的 data 层实现。
 */
@Singleton
class PlanRepositoryImpl @Inject constructor(
    private val weekPlanDao: WeekPlanDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PlanRepository {

    override fun observePlansForDay(dayOfWeek: Int): Flow<List<WeekPlan>> =
        weekPlanDao.observeActiveByDay(dayOfWeek)
            .map { entities -> entities.map(PlanMapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun observeAll(): Flow<List<WeekPlan>> =
        weekPlanDao.observeAll()
            .map { entities -> entities.map(PlanMapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun upsert(plan: WeekPlan): Long =
        weekPlanDao.upsert(PlanMapper.toEntity(plan))

    override suspend fun delete(id: Long) {
        weekPlanDao.deleteById(id)
    }
}
