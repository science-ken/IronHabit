package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ironhabit.app.data.local.entity.HabitEntity
import kotlinx.coroutines.flow.Flow

/**
 * `habits` 表 DAO：习惯定义增删改查。
 *
 * ⚠️ **CASCADE 父表**：`habit_logs.habit_id → habits.id` 是 `ON DELETE CASCADE`
 * （见 `HabitLogEntity`），且 Room 在 `onOpen` 里执行 `PRAGMA foreign_keys = ON`。
 * `INSERT OR REPLACE` 的语义是 **DELETE 旧行 + INSERT 新行**（rowid 变），
 * 因此**任何**对 `habits` 的 REPLACE 写入都会级联删光该习惯的全部历史日志。
 * 故 [upsert] 一律走**显式 upsert**（`@Transaction` 读改写，命中即 `UPDATE` 保住 rowid）。
 */
@Dao
interface HabitDao {

    @Query("SELECT * FROM habits WHERE is_active = 1 ORDER BY sort_order, id")
    fun observeActive(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE is_active = 1 ORDER BY sort_order, id")
    suspend fun getActive(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getById(id: Long): HabitEntity?

    @Query("SELECT * FROM habits ORDER BY sort_order, id")
    suspend fun getAll(): List<HabitEntity>

    /** 单行插入（主键冲突即抛 `ABORT`，**禁用 `REPLACE`**）。仅供 [upsert] 未命中路径使用。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: HabitEntity): Long

    /** 按主键整行更新（供 [upsert] 命中路径使用，**保留原 rowid**、不触发 CASCADE）。 */
    @Update
    suspend fun update(entity: HabitEntity)

    /**
     * 幂等 upsert（**禁用 `OnConflictStrategy.REPLACE`**，与 `CheckInDao.upsert` 同模式）。
     *
     * 命中已有行 → `UPDATE`（`entity.copy(id = existing.id)` 保住原 rowid），未命中 → `INSERT`。
     * 命名沿用 `upsert`（androidTest 直接按此名调用）。
     */
    @Transaction
    suspend fun upsert(entity: HabitEntity): Long {
        val existing = if (entity.id > 0L) getById(entity.id) else null
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
    suspend fun insertAll(entities: List<HabitEntity>): List<Long>

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 软删除（`is_active = 0`），不做物理 DELETE，保留历史日志关联。 */
    @Query("UPDATE habits SET is_active = 0 WHERE id = :id")
    suspend fun softDelete(id: Long)

    /** 写排序值（习惯排序）。 */
    @Query("UPDATE habits SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM habits")
    suspend fun clearAll()
}
