package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ironhabit.app.data.local.entity.WeekPlanEntity
import kotlinx.coroutines.flow.Flow

/**
 * `week_plans` 表 DAO：计划条目增删改查 + 按星期查询（联表过滤已停用动作）。
 */
@Dao
interface WeekPlanDao {

    @Query(
        """
        SELECT wp.* FROM week_plans wp
        INNER JOIN exercises e ON wp.exercise_id = e.id
        WHERE wp.day_of_week = :day AND wp.is_active = 1 AND e.is_active = 1
        ORDER BY wp.sort_order, wp.id
        """
    )
    fun observeActiveByDay(day: Int): Flow<List<WeekPlanEntity>>

    @Query("SELECT * FROM week_plans WHERE is_active = 1 ORDER BY day_of_week, sort_order")
    fun observeAll(): Flow<List<WeekPlanEntity>>

    /**
     * 观察**全部**计划条目（**含 `is_active = 0` 的软删除行**）。
     *
     * 🔒 AI 生成前必须拿全量：软删除行仍占 `UNIQUE(day_of_week, exercise_id)` 槽位，
     * 只看启用行会误往该槽位写入 → 把用户删掉的那条"复活"。纯新增，[observeAll] 一字未改。
     */
    @Query("SELECT * FROM week_plans ORDER BY day_of_week, sort_order")
    fun observeAllIncludingInactive(): Flow<List<WeekPlanEntity>>

    /** 「有计划的日子」→ 预览里的 chip 行（周一/周二/周四/周六…）。 */
    @Query("SELECT DISTINCT day_of_week FROM week_plans WHERE is_active = 1 ORDER BY day_of_week")
    fun observePlannedWeekdays(): Flow<List<Int>>

    @Query("SELECT * FROM week_plans ORDER BY day_of_week, sort_order")
    suspend fun getAll(): List<WeekPlanEntity>

    @Query("SELECT * FROM week_plans WHERE id = :id")
    suspend fun getById(id: Long): WeekPlanEntity?

    @Query("SELECT * FROM week_plans WHERE day_of_week = :dayOfWeek AND exercise_id = :exerciseId LIMIT 1")
    suspend fun getByDayAndExercise(dayOfWeek: Int, exerciseId: Long): WeekPlanEntity?

    /**
     * 幂等 upsert（**禁用 `OnConflictStrategy.REPLACE`**）。与 [upsertExplicit] 同语义：
     * 命中已有行（含软删行）→ `UPDATE`（保住原 rowid / flags），未命中 → `INSERT`。
     * `week_plans` 非 CASCADE 父表，但 REPLACE 会重建整行冲掉 `is_active` / `is_user_edited`，
     * 故一并统一为显式 upsert。
     */
    @Transaction
    suspend fun upsert(entity: WeekPlanEntity): Long {
        val existing = getByDayAndExercise(entity.dayOfWeek, entity.exerciseId)
        return if (existing == null) {
            insert(entity)
        } else {
            update(entity.copy(id = existing.id))
            existing.id
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WeekPlanEntity): Long

    @Update
    suspend fun update(entity: WeekPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WeekPlanEntity>): List<Long>

    /**
     * 显式 upsert（**禁用 `OnConflictStrategy.REPLACE`**）。
     *
     * `week_plans` 有 `UNIQUE(day_of_week, exercise_id)`，软删除行仍占唯一槽位；
     * `REPLACE` 会重建整行、把 `is_active` / `is_user_edited` 冲掉（架构 §6.3 坑 4）。
     *
     * ⚠️ 必须先按**主键 `id`** 匹配，再按 `(day_of_week, exercise_id)` 组合兜底：
     * 用户编辑一条已有计划时往往**会换动作 / 换星期**，此时新组合没有行，若直接按组合匹配
     * 会落到 `insert(entity)`——而 `entity.id` 仍是旧主键（非 0）→ `@Insert(ABORT)` 主键冲突
     * 抛异常、"保存失败"。先按 id 命中即可避免（v1.8 修复）。
     *
     * 命中主键 → `UPDATE`（保留原 rowid/flags，由调用方在 `entity` 给定新值）；
     * id 未命中（新增 / id 失效）→ 看组合槽位：被软删行占着则复活，否则 `INSERT`。
     */
    @Transaction
    suspend fun upsertExplicit(entity: WeekPlanEntity): Long {
        // 1) 主键命中（编辑已有行，含改了 day/exercise 的情况）→ 直接按 id 更新
        if (entity.id != 0L) {
            val byId = getById(entity.id)
            if (byId != null) {
                update(entity) // 保留原 id，按主键更新
                return entity.id
            }
        }
        // 2) 新增（id==0 或 id 未命中）→ 看 (天,动作) 槽位是否被占用（含软删行），是则复活，否则插入
        val existing = getByDayAndExercise(entity.dayOfWeek, entity.exerciseId)
        return if (existing == null) {
            insert(entity.copy(id = 0L)) // 确保走自增
        } else {
            update(entity.copy(id = existing.id))
            existing.id
        }
    }

    /** 软删除：保留唯一索引槽位 + 阻止 AI 复活。**禁止用 `DELETE`**（架构 §6.3 坑 3）。 */
    @Query("UPDATE week_plans SET is_active = 0, is_user_edited = 1 WHERE id = :id")
    suspend fun softDelete(id: Long)

    /** 用户点「恢复为推荐」→ 交还 AI 接管。 */
    @Query("UPDATE week_plans SET is_user_edited = 0 WHERE id = :id")
    suspend fun resetToRecommended(id: Long)

    /** AI 生成前的保护判定：该「天 × 动作」是否被用户动过（**含软删行**）。 */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM week_plans " +
            "WHERE day_of_week = :dayOfWeek AND exercise_id = :exerciseId AND is_user_edited = 1)"
    )
    suspend fun hasUserEdited(dayOfWeek: Int, exerciseId: Long): Boolean

    @Query("DELETE FROM week_plans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM week_plans")
    suspend fun clearAll()
}
