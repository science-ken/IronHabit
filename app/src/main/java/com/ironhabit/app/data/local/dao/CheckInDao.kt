package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ironhabit.app.data.local.entity.CheckInEntity
import kotlinx.coroutines.flow.Flow

/**
 * `check_ins` 表 DAO：打卡记录 upsert / 撤销 / 区间查询 / 去重天数聚合。
 *
 * 依赖 `UNIQUE(exercise_id, date_epoch_day)` 实现「一键打卡」幂等。
 */
@Dao
interface CheckInDao {

    @Query("SELECT * FROM check_ins WHERE date_epoch_day = :epochDay ORDER BY created_at DESC")
    fun observeByDate(epochDay: Long): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE exercise_id = :exerciseId ORDER BY date_epoch_day DESC")
    fun observeByExercise(exerciseId: Long): Flow<List<CheckInEntity>>

    @Query("SELECT * FROM check_ins WHERE exercise_id = :exerciseId AND date_epoch_day = :epochDay LIMIT 1")
    suspend fun getForExerciseOnDate(exerciseId: Long, epochDay: Long): CheckInEntity?

    /** 全部有打卡的日期，降序（streak 输入）。 */
    @Query("SELECT date_epoch_day FROM check_ins GROUP BY date_epoch_day ORDER BY date_epoch_day DESC")
    fun observeActiveDays(): Flow<List<Long>>

    /** 自 `sinceEpochDay`（含）起有打卡的日期，降序（streak 输入）。 */
    @Query(
        "SELECT DISTINCT date_epoch_day FROM check_ins " +
            "WHERE date_epoch_day >= :sinceEpochDay ORDER BY date_epoch_day DESC"
    )
    fun observeActiveDaysSince(sinceEpochDay: Long): Flow<List<Long>>

    @Query("SELECT * FROM check_ins WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay ORDER BY date_epoch_day")
    fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<CheckInEntity>>

    /** 幂等 upsert：唯一约束 `(exercise_id, date_epoch_day)` 冲突时替换。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CheckInEntity): Long

    /** 导入用：保留原 id 重建。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CheckInEntity>): List<Long>

    @Query("DELETE FROM check_ins WHERE exercise_id = :exerciseId AND date_epoch_day = :epochDay")
    suspend fun deleteOn(exerciseId: Long, epochDay: Long)

    /**
     * 逐组勾选：`mask` 与派生列 `completed_sets` **必须同写**（同一条 UPDATE），
     * 以保证不变量 `completed_sets == completed_sets_mask.countOneBits()`。
     */
    @Query(
        "UPDATE check_ins SET completed_sets_mask = :mask, completed_sets = :completedSets " +
            "WHERE exercise_id = :exerciseId AND date_epoch_day = :epochDay"
    )
    suspend fun updateSetMask(exerciseId: Long, epochDay: Long, mask: Int, completedSets: Int)

    /** 写入 / 清除 RPE（`1..10`，可空）。 */
    @Query(
        "UPDATE check_ins SET rpe = :rpe " +
            "WHERE exercise_id = :exerciseId AND date_epoch_day = :epochDay"
    )
    suspend fun updateRpe(exerciseId: Long, epochDay: Long, rpe: Int?)

    @Query("SELECT COUNT(*) FROM check_ins WHERE date_epoch_day = :epochDay")
    suspend fun countOn(epochDay: Long): Int

    @Query("SELECT * FROM check_ins ORDER BY date_epoch_day")
    suspend fun getAll(): List<CheckInEntity>

    @Query("DELETE FROM check_ins")
    suspend fun clearAll()
}
