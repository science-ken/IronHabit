package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ironhabit.app.data.local.dto.ExerciseProgressRaw
import com.ironhabit.app.data.local.entity.CheckInEntity
import com.ironhabit.app.domain.model.MAX_SETS
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

    /**
     * 逐组勾选的**原子**读改写：缺行则插种子 → 读 mask → `XOR` 翻转第 [setIndex] 位 → 同写两列。
     *
     * 修复（BUG 1 — 静默丢更新）：此前 `CheckInRepositoryImpl.toggleSet` 自己分三条语句做
     * 「`SELECT` 判空 → 可能 `INSERT` 种子 → 再 `SELECT` 取 mask → `UPDATE`」，**三条语句之间
     * 没有事务**。两次快速连点（或两个并发收集器）会读到**同一个**旧 mask，各自 `xor` 后写回，
     * 于是其中一次勾选被静默吞掉；种子插入还会撞上 `UNIQUE(exercise_id, date_epoch_day)` 的
     * `ABORT` 冲突而抛异常（真机即崩）。
     *
     * 本方法把整段读改写收进**同一个事务**：Room 会把并发的事务体串行化，第二个调用读到的一定是
     * 第一个提交后的 mask，故两次点击不会互相覆盖；种子行也已在同一事务内落库，第二个调用
     * 直接走 `UPDATE`，不再撞唯一约束。
     *
     * **不变量**：`completed_sets` 是 `completed_sets_mask` 的派生列，
     * 二者**必须同写**（此处复用 [updateSetMask]，单条 `UPDATE` 同时写两列），
     * 从而恒有 `completed_sets == completed_sets_mask.countOneBits()`。
     *
     * **绝不抛异常**：[setIndex] 越界（`!in 0 until MAX_SETS`，含负数）时**静默忽略**并返回
     * 当前 mask（无行则返回 `0`）—— 与仓库层既有策略一致（真机崩溃比一次错点严重得多）。
     *
     * @param seed 行不存在时插入的模板行；其 `exerciseId` / `dateEpochDay` 以入参为准，
     *   两个 mask 列一律**强制为 `0`**（本方法只做「从空开始翻转」，不接受调用方预处理过的位图）。
     *   行已存在时该参数被忽略。
     * @return 事务提交后的 `completed_sets_mask`
     */
    @Transaction
    suspend fun toggleSetBit(
        exerciseId: Long,
        epochDay: Long,
        setIndex: Int,
        seed: CheckInEntity,
    ): Int {
        if (setIndex !in 0 until MAX_SETS) {
            return getForExerciseOnDate(exerciseId, epochDay)?.completedSetsMask ?: 0
        }

        // 行不存在时先落一条空记录，保证后续 UPDATE 命中。此处**直接 INSERT**（而非 upsert）：
        // 同一事务内上一条 SELECT 已确认该 `(exercise_id, date_epoch_day)` 槽位为空，
        // 事务串行化保证并发调用不会插在 SELECT 与 INSERT 之间，故不会触发 ABORT 冲突；
        // 全新行也没有旧 rpe 需要保留（upsert 的合并逻辑在此无用）。
        if (getForExerciseOnDate(exerciseId, epochDay) == null) {
            insert(
                seed.copy(
                    exerciseId = exerciseId,
                    dateEpochDay = epochDay,
                    completedSetsMask = 0,
                    completedSets = 0,
                ),
            )
        }

        val currentMask: Int = getForExerciseOnDate(exerciseId, epochDay)?.completedSetsMask ?: 0
        val newMask: Int = currentMask xor (1 shl setIndex)
        updateSetMask(
            exerciseId = exerciseId,
            epochDay = epochDay,
            mask = newMask,
            completedSets = newMask.countOneBits(),
        )
        return newMask
    }

    /** 写入 / 清除 RPE（`1..10`，可空）。 */
    @Query(
        "UPDATE check_ins SET rpe = :rpe " +
            "WHERE exercise_id = :exerciseId AND date_epoch_day = :epochDay"
    )
    suspend fun updateRpe(exerciseId: Long, epochDay: Long, rpe: Int?)

    /**
     * 观测"每个动作最近一次完成情况"的聚合（**只读新增**，不改既有任何查询）。
     *
     * 用途：本地规则引擎的**渐进超负荷**判定（`docs/ai-coach-local.md` §4.4）。
     *
     * 取"最近一次"的判据：先按 `date_epoch_day` 倒序、再按 `created_at` 倒序取第一条 ——
     * 用**相关子查询精确定位到行**，避免 `GROUP BY` 在同日多行时挑行的不确定性。
     * （`UNIQUE(exercise_id, date_epoch_day)` 保证同一动作同一天只有一行。）
     *
     * `last_target_sets` 来自关联计划（`plan_id`），无计划时为 `null` —— **不在 SQL 里兜底**，
     * 由仓库层回落到常量（避免把业务常量写死进 SQL，便于单测覆盖）。
     */
    @Query(
        "SELECT c.exercise_id AS exerciseId, " +
            "c.completed_sets AS lastSetsCompleted, " +
            "wp.target_sets AS lastTargetSets, " +
            "c.rpe AS lastRpe, " +
            "c.weight_kg AS lastWeightKg " +
            "FROM check_ins c " +
            "LEFT JOIN week_plans wp ON wp.id = c.plan_id " +
            "WHERE c.id = (" +
            "SELECT c2.id FROM check_ins c2 " +
            "WHERE c2.exercise_id = c.exercise_id " +
            "ORDER BY c2.date_epoch_day DESC, c2.created_at DESC LIMIT 1" +
            ") ORDER BY c.exercise_id"
    )
    fun observeLatestPerExercise(): Flow<List<ExerciseProgressRaw>>

    @Query("SELECT COUNT(*) FROM check_ins WHERE date_epoch_day = :epochDay")
    suspend fun countOn(epochDay: Long): Int

    @Query("SELECT * FROM check_ins ORDER BY date_epoch_day")
    suspend fun getAll(): List<CheckInEntity>

    @Query("DELETE FROM check_ins")
    suspend fun clearAll()
}
