package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    /**
     * 单行插入。唯一约束冲突即抛异常（**禁用 `REPLACE`**）。
     *
     * 供 [upsert] 在「未命中已有行」时使用；不在插入路径上重建行 id。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CheckInEntity): Long

    /** 导入用：保留原 id 重建（此路径**有意**用 `REPLACE`）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CheckInEntity>): List<Long>

    /** 按主键整行更新（供 [upsert] 命中已有行时使用）。 */
    @Update
    suspend fun update(entity: CheckInEntity)

    /**
     * 幂等 upsert（**禁用 `OnConflictStrategy.REPLACE`**，与 `week_plans.upsertExplicit` 同模式）。
     *
     * `check_ins` 有 `UNIQUE(exercise_id, date_epoch_day)`；`REPLACE` = `DELETE` + `INSERT`，
     * 会**重建整行、令主键 `id` 变化**（引用不稳定）。故：
     * 命中已有行 → `UPDATE`（保留原 `id`，其余列以入参为准），未命中 → `INSERT`。
     *
     * **RPE 保留**：命中已有行且入参 `rpe == null` 时，**沿用旧行的 `rpe`** —— 补录 / 一键打卡
     * 构造的 `CheckIn` 不携带 rpe，若整行覆盖会把用户已录入的强度静默抹成 NULL；
     * 入参显式给出 `rpe` 时以入参为准。
     *
     * 命名沿用 `upsert` 而非 `upsertExplicit`：`check_ins` 无「软删占位」语义，
     * 唯一约束槽位天然对应一行有效记录，无需与旧 `REPLACE` 版本并存。
     */
    @Transaction
    suspend fun upsert(entity: CheckInEntity): Long {
        val existing = getForExerciseOnDate(entity.exerciseId, entity.dateEpochDay)
        return if (existing == null) {
            insert(entity)
        } else {
            // RPE 保留：入参未显式给 rpe（== null）时**沿用旧行 rpe**。补录 / 一键打卡等入口
            // 构造的 CheckIn 不携带 rpe（null），若直接整行覆盖会把用户已录入的强度静默抹成 NULL
            // （弹层里无 RPE 字段，用户无从察觉）。入参显式给了值则以入参为准。
            val merged = if (entity.rpe == null) {
                entity.copy(id = existing.id, rpe = existing.rpe)
            } else {
                entity.copy(id = existing.id)
            }
            update(merged)
            existing.id
        }
    }

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
