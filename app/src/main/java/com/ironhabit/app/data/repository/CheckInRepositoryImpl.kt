package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.CheckInDao
import com.ironhabit.app.data.mapper.CheckInMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.MAX_SETS
import com.ironhabit.app.domain.repository.CheckInRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * [CheckInRepository] 的 data 层实现。
 */
@Singleton
class CheckInRepositoryImpl @Inject constructor(
    private val checkInDao: CheckInDao,
    private val clock: Clock,
    private val timeZone: TimeZone,
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

    override suspend fun toggleSet(exerciseId: Long, epochDay: Long, setIndex: Int) {
        // 越界（含负数）静默忽略 —— 绝不用 require() 抛异常（真机抛异常即崩）。
        if (setIndex !in 0 until MAX_SETS) return

        // 行不存在时先落一条空记录，保证后续 UPDATE 命中。
        if (checkInDao.getForExerciseOnDate(exerciseId, epochDay) == null) {
            val nowMillis = clock.now().toEpochMilliseconds()
            val seed = CheckIn(
                exerciseId = exerciseId,
                dateEpochDay = epochDay,
                dateStartMillis = startOfDayMillis(epochDay),
                completedSetsMask = 0,
                isQuick = false,
                loggedAtMillis = nowMillis,
                createdAt = nowMillis,
            )
            checkInDao.upsert(CheckInMapper.toEntity(seed))
        }

        val currentMask: Int = checkInDao.getForExerciseOnDate(exerciseId, epochDay)?.completedSetsMask ?: 0
        val newMask: Int = currentMask xor (1 shl setIndex)
        // mask 与派生列同写（同一条 UPDATE），维护不变量。
        checkInDao.updateSetMask(
            exerciseId = exerciseId,
            epochDay = epochDay,
            mask = newMask,
            completedSets = newMask.countOneBits(),
        )
    }

    override suspend fun setRpe(exerciseId: Long, epochDay: Long, rpe: Int?) {
        checkInDao.updateRpe(exerciseId, epochDay, rpe?.coerceIn(MIN_RPE, MAX_RPE))
    }

    /** 把 epochDay 换算为当天本地 00:00 的 UTC 毫秒时间戳（架构 §7.3）。 */
    private fun startOfDayMillis(epochDay: Long): Long =
        LocalDate.fromEpochDays(epochDay.toInt()).atStartOfDayIn(timeZone).toEpochMilliseconds()

    private companion object {
        const val MIN_RPE = 1
        const val MAX_RPE = 10
    }
}
