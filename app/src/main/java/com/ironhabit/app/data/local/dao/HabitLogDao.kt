package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ironhabit.app.data.local.entity.HabitLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * `habit_logs` 表 DAO：习惯日志 upsert / 区间查询 / 去重日期序列。
 *
 * 依赖 `UNIQUE(habit_id, date_epoch_day)` 实现勾选幂等。
 * `habit_logs` 是 `habits` 的**子表**（非 CASCADE 父表）；为统一写入口径，[upsert] 亦为显式 upsert。
 */
@Dao
interface HabitLogDao {

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId AND date_epoch_day = :epochDay LIMIT 1")
    suspend fun getOn(habitId: Long, epochDay: Long): HabitLogEntity?

    @Query("SELECT * FROM habit_logs WHERE id = :id")
    suspend fun getById(id: Long): HabitLogEntity?

    @Query("SELECT * FROM habit_logs WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay")
    fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_logs WHERE habit_id = :habitId ORDER BY date_epoch_day DESC")
    fun observeByHabit(habitId: Long): Flow<List<HabitLogEntity>>

    /** 全部【已完成】的日期，降序（习惯连续天数计算所需；未完成记录不计入）。 */
    @Query("SELECT date_epoch_day FROM habit_logs WHERE habit_id = :habitId AND is_completed = 1 GROUP BY date_epoch_day ORDER BY date_epoch_day DESC")
    fun observeActiveDays(habitId: Long): Flow<List<Long>>

    /** 单行插入（冲突即抛 `ABORT`，**禁用 `REPLACE`**）。仅供 [upsert] 未命中路径使用。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: HabitLogEntity): Long

    /** 按主键整行更新（供 [upsert] 命中路径使用，**保留原 rowid**）。 */
    @Update
    suspend fun update(entity: HabitLogEntity)

    /**
     * 幂等 upsert（**禁用 `OnConflictStrategy.REPLACE`**，与 `CheckInDao.upsert` 同模式）。
     *
     * 命中已有行 → `UPDATE`（保住原 rowid），未命中 → `INSERT`。
     * 命中键：`id > 0` 时按主键；否则按唯一 `(habit_id, date_epoch_day)`。
     */
    @Transaction
    suspend fun upsert(entity: HabitLogEntity): Long {
        val existing = if (entity.id > 0L) {
            getById(entity.id)
        } else {
            getOn(entity.habitId, entity.dateEpochDay)
        }
        return if (existing == null) {
            insert(entity)
        } else {
            update(entity.copy(id = existing.id))
            existing.id
        }
    }

    /**
     * 备份导入用：批量重建（调用方在导入事务内**已先 `clearAll()`**，
     * 此路径**有意**用 `REPLACE` 以保留原 id）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<HabitLogEntity>): List<Long>

    @Query("DELETE FROM habit_logs WHERE habit_id = :habitId AND date_epoch_day = :epochDay")
    suspend fun deleteOn(habitId: Long, epochDay: Long)

    @Query("SELECT * FROM habit_logs ORDER BY date_epoch_day")
    suspend fun getAll(): List<HabitLogEntity>

    @Query("DELETE FROM habit_logs")
    suspend fun clearAll()
}
