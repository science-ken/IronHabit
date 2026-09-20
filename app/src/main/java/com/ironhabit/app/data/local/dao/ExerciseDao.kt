package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.domain.model.ExerciseCategory
import kotlinx.coroutines.flow.Flow

/**
 * `exercises` 表 DAO：动作库增删改查 + 分类筛选 + 播种。
 *
 * ⚠️ **CASCADE 父表（被两张表引用）**：`week_plans.exercise_id → exercises.id` 与
 * `check_ins.exercise_id → exercises.id` **均为 `ON DELETE CASCADE`**。
 * `INSERT OR REPLACE` = DELETE + INSERT（rowid 变），故对 `exercises` 的 REPLACE 写入
 * 会**同时级联删掉**该动作的全部周计划条目**与历史打卡记录**。
 * 故 [upsert] 一律走**显式 upsert**（`@Transaction` 读改写，命中即 `UPDATE` 保住 rowid）。
 */
@Dao
interface ExerciseDao {

    @Query("SELECT * FROM exercises WHERE is_active = 1 ORDER BY sort_order, name")
    fun observeActive(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE is_active = 1 AND category = :category ORDER BY sort_order, name")
    fun observeByCategory(category: ExerciseCategory): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): ExerciseEntity?

    @Query("SELECT * FROM exercises ORDER BY sort_order, name")
    suspend fun getAll(): List<ExerciseEntity>

    @Query("SELECT COUNT(*) FROM exercises WHERE name = :name AND id != :excludeId")
    suspend fun countByName(name: String, excludeId: Long): Int

    /** 按唯一 `name` 查询（供 [upsert] 在 id 为空时幂等命中）。 */
    @Query("SELECT * FROM exercises WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ExerciseEntity?

    /**
     * 批量按 `name` 取行（播种补空用：一次查询拿回全部占位行，避免逐条内置动作点查）。
     *
     * ⚠️ `names` 不能为空 —— 空列表会生成 `IN ()`，SQLite 视为语法错误。调用方
     * （`DatabaseSeeder`）自己保证非空。
     */
    @Query("SELECT * FROM exercises WHERE name IN (:names)")
    suspend fun getByNames(names: List<String>): List<ExerciseEntity>

    /** 单行插入（主键冲突即抛 `ABORT`，**禁用 `REPLACE`**）。仅供 [upsert] 未命中路径使用。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ExerciseEntity): Long

    /** 按主键整行更新（供 [upsert] 命中路径使用，**保留原 rowid**、不触发 CASCADE）。 */
    @Update
    suspend fun update(entity: ExerciseEntity)

    /** 幂等插入：名称冲突（唯一索引）时忽略。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: ExerciseEntity): Long

    /** 幂等批量插入：返回每行的 rowId，被忽略的行为 `-1`。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<ExerciseEntity>): List<Long>

    /**
     * 幂等 upsert（**禁用 `OnConflictStrategy.REPLACE`**，与 `CheckInDao.upsert` 同模式）。
     *
     * 命中已有行 → `UPDATE`（`entity.copy(id = existing.id)` 保住原 rowid，**不触发** CASCADE），
     * 未命中 → `INSERT`。命中键：`id > 0` 时按主键（编辑既有动作）；否则按唯一 `name`（幂等）。
     * 命名沿用 `upsert`（androidTest 直接按此名调用）。
     */
    @Transaction
    suspend fun upsert(entity: ExerciseEntity): Long {
        val existing = if (entity.id > 0L) getById(entity.id) else getByName(entity.name)
        return if (existing == null) {
            insert(entity)
        } else {
            update(entity.copy(id = existing.id))
            existing.id
        }
    }

    /**
     * 备份导入用：批量重建（调用方在导入事务内**已先 `clearAll()`**，
     * 此路径**有意**用 `REPLACE` 以保留原 id；清表后无旧行可级联，安全）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ExerciseEntity>): List<Long>

    @Query("UPDATE exercises SET times_used = times_used + 1 WHERE id = :id")
    suspend fun bumpUsage(id: Long)

    @Query("DELETE FROM exercises")
    suspend fun clearAll()
}
