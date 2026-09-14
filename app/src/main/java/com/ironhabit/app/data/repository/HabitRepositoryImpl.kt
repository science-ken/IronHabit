package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.HabitDao
import com.ironhabit.app.data.local.dao.HabitLogDao
import com.ironhabit.app.data.mapper.HabitMapper
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitLog
import com.ironhabit.app.domain.repository.HabitRepository
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
 * [HabitRepository] 的 data 层实现（定义 + 日志 + 今日状态）。
 */
@Singleton
class HabitRepositoryImpl @Inject constructor(
    private val habitDao: HabitDao,
    private val habitLogDao: HabitLogDao,
    private val clock: Clock,
    private val timeZone: TimeZone,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : HabitRepository {

    override fun observeActiveHabits(): Flow<List<Habit>> =
        habitDao.observeActive()
            .map { entities -> entities.map { entity -> HabitMapper.toDomain(entity) } }
            .flowOn(ioDispatcher)

    override fun observeLogsBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<HabitLog>> =
        habitLogDao.observeBetween(startEpochDay, endEpochDay)
            .map { entities -> entities.map { entity -> HabitMapper.toDomain(entity) } }
            .flowOn(ioDispatcher)

    override suspend fun getLogOnDate(habitId: Long, epochDay: Long): HabitLog? =
        habitLogDao.getOn(habitId, epochDay)?.let(HabitMapper::toDomain)

    override suspend fun upsertHabit(habit: Habit): Long =
        habitDao.upsert(HabitMapper.toEntity(habit))

    override suspend fun setLog(habitId: Long, epochDay: Long, done: Boolean, note: String?) {
        val nowMillis = clock.now().toEpochMilliseconds()
        val existing = habitLogDao.getOn(habitId, epochDay)
        val log = HabitLog(
            id = existing?.id ?: 0L,
            habitId = habitId,
            dateEpochDay = epochDay,
            dateStartMillis = startOfDayMillis(epochDay),
            isCompleted = done,
            note = note ?: existing?.note,
            loggedAtMillis = nowMillis,
            createdAt = existing?.createdAt ?: nowMillis,
        )
        habitLogDao.upsert(HabitMapper.toEntity(log))
    }

    /** 把 epochDay 换算为当天本地 00:00 的 UTC 毫秒时间戳（架构 §7.3）。 */
    private fun startOfDayMillis(epochDay: Long): Long =
        LocalDate.fromEpochDays(epochDay.toInt()).atStartOfDayIn(timeZone).toEpochMilliseconds()

    override fun observeActiveDays(habitId: Long): Flow<List<Long>> =
        habitLogDao.observeActiveDays(habitId).flowOn(ioDispatcher)

    override suspend fun deleteHabit(habitId: Long) {
        // 软删除：保留历史日志关联，仅置 is_active = 0。
        habitDao.softDelete(habitId)
    }

    override suspend fun reorderHabits(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            habitDao.updateSortOrder(id, index)
        }
    }
}
