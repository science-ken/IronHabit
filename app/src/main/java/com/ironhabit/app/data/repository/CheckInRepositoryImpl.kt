package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.mapper.CheckInMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.repository.CheckInRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * [CheckInRepository] 的 data 层实现。
 */
@Singleton
class CheckInRepositoryImpl @Inject constructor(
    private val checkInDao: CheckInDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CheckInRepository {

    override fun observeByDate(epochDay: Long): Flow<List<CheckIn>> =
        checkInDao.observeByDate(epochDay)
            .map { entities -> entities.map(CheckInMapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun observeByExercise(exerciseId: Long): Flow<List<CheckIn>> =
        checkInDao.observeByExercise(exerciseId)
            .map { entities -> entities.map(CheckInMapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun getForExerciseOnDate(exerciseId: Long, epochDay: Long): CheckIn? =
        checkInDao.getForExerciseOnDate(exerciseId, epochDay)?.let(CheckInMapper::toDomain)

    override suspend fun upsert(checkIn: CheckIn): Long =
        checkInDao.upsert(CheckInMapper.toEntity(checkIn))

    override suspend fun delete(exerciseId: Long, epochDay: Long) {
        checkInDao.deleteOn(exerciseId, epochDay)
    }

    override fun observeActiveDaysSince(epochDay: Long): Flow<List<Long>> =
        checkInDao.observeActiveDaysSince(epochDay).flowOn(ioDispatcher)

    override fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<CheckIn>> =
        checkInDao.observeBetween(startEpochDay, endEpochDay)
            .map { entities -> entities.map(CheckInMapper::toDomain) }
            .flowOn(ioDispatcher)
}
