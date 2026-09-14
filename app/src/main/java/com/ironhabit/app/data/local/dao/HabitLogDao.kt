package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ironhabit.app.data.local.entity.HabitLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * `habit_logs` 表 DAO：习惯日志 upsert / 区间查询 / 去重日期序列。
 *
 * 依赖 `UNIQUE(habit_id, date_epoch_day)` 实现勾选幂等。
 */
@Dao
interface HabitLogDao {

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId AND date_epoch_day = :epochDay LIMIT 1")
    suspend fun getOn(habitId: Long, epochDay: Long): HabitLogEntity?

    @Query("SELECT * FROM habit_logs WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay")
    fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId ORDER BY date_epoch_day DESC")
    fun observeByHabit(habitId: Long): Flow<List<HabitLogEntity>>

    /** 全部【已完成】的日期，降序（习惯连续天数计算所需；未完成记录不计入）。 */
    @Query("SELECT date_epoch_day FROM habit_logs WHERE habit_id = :habitId AND is_completed = 1 GROUP BY date_epoch_day ORDER BY date_epoch_day DESC")
    fun observeActiveDays(habitId: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HabitLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<HabitLogEntity>): List<Long>

    @Query("DELETE FROM habit_logs WHERE habit_id = :habitId AND date_epoch_day = :epochDay")
    suspend fun deleteOn(habitId: Long, epochDay: Long)

    @Query("SELECT * FROM habit_logs ORDER BY date_epoch_day")
    suspend fun getAll(): List<HabitLogEntity>

    @Query("DELETE FROM habit_logs")
    suspend fun clearAll()
}
