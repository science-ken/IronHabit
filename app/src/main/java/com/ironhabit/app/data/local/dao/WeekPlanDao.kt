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
 * `week_plans` 表 DAO：计划条目增删改查 + 按星期 / 按周查询（联表过滤已停用动作）。
 *
 * ## P3 的语义变化（重要）
 * 唯一槽位从「星期 × 动作」变成「**星期 × 动作 × 哪一周**」：
 * - `week_start_epoch_day = 0` = **「每周相同」的那份计划**（旧称模板；只有用户勾了才会存在）；
 * - `week_start_epoch_day = 某周周一` = **只属于那一周的计划**（默认形态）。
 *
 * 因此**所有按槽位查/写的查询都必须带 `week_start_epoch_day`** —— 否则"给这一周排深蹲"
 * 会命中"另一周/每周相同那份里的深蹲那一行"，把它改掉。
 */
@Dao
interface WeekPlanDao {

    /**
     * 某一天的**全部候选行**（每周相同的那份 + 各周专属的都返回，**含软删除行**）。
     *
     * 由 `WeekPlanWeekResolver` 决定这一天到底用哪一份 —— DAO 不做选择，避免两处规则不一致。
     * 联表仍然过滤"动作已被停用"的行（与界面一致：停用的动作不该出现在计划里）。
     */
    @Query(
        """
        SELECT wp.* FROM week_plans wp
        INNER JOIN exercises e ON wp.exercise_id = e.id
        WHERE wp.day_of_week = :day AND e.is_active = 1
        ORDER BY wp.sort_order, wp.id
        """
    )
    fun observeRowsForDay(day: Int): Flow<List<WeekPlanEntity>>

    /** 某一周的**全部行**（`week_start_epoch_day` = 该周周一），按 星期 / 排序。 */
    @Query(
        "SELECT * FROM week_plans WHERE week_start_epoch_day = :weekStartEpochDay " +
            "ORDER BY day_of_week, sort_order, id"
    )
    fun observeRowsForWeek(weekStartEpochDay: Long): Flow<List<WeekPlanEntity>>

    /** 同 [observeRowsForWeek] 的一次性版本（生成 / 备份这类要快照的场景）。 */
    @Query(
        "SELECT * FROM week_plans WHERE week_start_epoch_day = :weekStartEpochDay " +
            "ORDER BY day_of_week, sort_order, id"
    )
    suspend fun getRowsForWeek(weekStartEpochDay: Long): List<WeekPlanEntity>

    /** 「每周相同」的那份计划（`week_start_epoch_day = 0`）。 */
    @Query(
        "SELECT * FROM week_plans WHERE week_start_epoch_day = 0 " +
            "ORDER BY day_of_week, sort_order, id"
    )
    suspend fun getRepeatRows(): List<WeekPlanEntity>

    /** 取消「每周相同」：把那份计划整体停用（**软删除，不 DELETE**）。 */
    @Query("UPDATE week_plans SET is_active = 0 WHERE week_start_epoch_day = 0 AND is_active = 1")
    suspend fun deactivateRepeatRows(): Int

    @Query("SELECT * FROM week_plans WHERE is_active = 1 ORDER BY day_of_week, sort_order")
    fun observeAll(): Flow<List<WeekPlanEntity>>

    /**
     * 观察**全部**计划条目（**含 `is_active = 0` 的软删除行**，所有周都算）。
     *
     * 🔒 AI 生成前必须拿全量：软删除行仍占唯一槽位，只看启用行会误往该槽位写入 →
     * 把用户删掉的那条"复活"。纯新增，[observeAll] 一字未改。
     */
    @Query("SELECT * FROM week_plans ORDER BY day_of_week, sort_order")
    fun observeAllIncludingInactive(): Flow<List<WeekPlanEntity>>

    /** 「有计划的日子」→ 预览里的 chip 行（对**某一周**而言）。 */
    @Query(
        "SELECT DISTINCT day_of_week FROM week_plans " +
            "WHERE is_active = 1 AND week_start_epoch_day = :weekStartEpochDay ORDER BY day_of_week"
    )
    fun observePlannedWeekdays(weekStartEpochDay: Long): Flow<List<Int>>

    @Query("SELECT DISTINCT day_of_week FROM week_plans WHERE is_active = 1 ORDER BY day_of_week")
    fun observeAllPlannedWeekdays(): Flow<List<Int>>

    @Query("SELECT * FROM week_plans ORDER BY day_of_week, sort_order")
    suspend fun getAll(): List<WeekPlanEntity>

    @Query("SELECT * FROM week_plans WHERE id = :id")
    suspend fun getById(id: Long): WeekPlanEntity?

    /**
     * 按**槽位**取行：`(星期, 动作, 哪一周)`。
     *
     * ⚠️ P3 起必须带 `weekStartEpochDay` —— 少了它就会跨周串行（见接口注释）。
     */
    @Query(
        "SELECT * FROM week_plans WHERE day_of_week = :dayOfWeek AND exercise_id = :exerciseId " +
            "AND week_start_epoch_day = :weekStartEpochDay LIMIT 1"
    )
    suspend fun getBySlot(
        dayOfWeek: Int,
        exerciseId: Long,
        weekStartEpochDay: Long,
    ): WeekPlanEntity?

    /**
     * 幂等 upsert（**禁用 `OnConflictStrategy.REPLACE`**）。与 [upsertExplicit] 同语义：
     * 命中已有行（含软删行）→ `UPDATE`（保住原 rowid / flags），未命中 → `INSERT`。
     * `week_plans` 非 CASCADE 父表，但 REPLACE 会重建整行冲掉 `is_active` / `is_user_edited`，
     * 故一并统一为显式 upsert。
     */
    @Transaction
    suspend fun upsert(entity: WeekPlanEntity): Long {
        val existing = getBySlot(entity.dayOfWeek, entity.exerciseId, entity.weekStartEpochDay)
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
     * 唯一槽位是 `UNIQUE(day_of_week, exercise_id, week_start_epoch_day)`，软删除行仍占槽位；
     * `REPLACE` 会重建整行、把 `is_active` / `is_user_edited` 冲掉（架构 §6.3 坑 4）。
     *
     * ⚠️ 必须先按**主键 `id`** 匹配，再按**槽位**兜底：
     * 用户编辑一条已有计划时往往**会换动作 / 换星期**，此时新槽位没有行，若直接按槽位匹配
     * 会落到 `insert(entity)`——而 `entity.id` 仍是旧主键（非 0）→ `@Insert(ABORT)` 主键冲突
     * 抛异常、"保存失败"。先按 id 命中即可避免（v1.8 修复）。
     *
     * ⚠️ P3：槽位兜底必须带 `week_start_epoch_day`（改哪一周的计划就只碰那一周的行）。
     *
     * 命中主键 → `UPDATE`（保留原 rowid/flags，由调用方在 `entity` 给定新值）；
     * id 未命中（新增 / id 失效）→ 看槽位：被软删行占着则复活，否则 `INSERT`。
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
        // 2) 新增（id==0 或 id 未命中）→ 看同一周的同槽位是否被占用（含软删行），是则复活，否则插入
        val existing = getBySlot(entity.dayOfWeek, entity.exerciseId, entity.weekStartEpochDay)
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

    /**
     * 用户点「恢复为推荐」→ 交还 AI 接管（清掉 `is_user_edited`）。
     *
     * 🔒 **必须带 `is_active = 1`**（补守卫）：软删除行（`is_active = 0`）是"用户明确删掉的槽位"，
     * 一旦把它的 `is_user_edited` 清成 0，下次 AI 生成就会把这个槽位**复活** ——
     * 用户会看到"我删掉的那条又回来了"，而且不知道是自己哪一步点出来的。
     * 守卫做两道：`ResetPlanItemUseCase` 先判一次（可单测），SQL 这里再兜一次（防别的调用方绕过）。
     *
     * @return 实际影响行数（`0` = 什么都没改，例如对软删除行 / 不存在的 id 调用）
     */
    @Query("UPDATE week_plans SET is_user_edited = 0 WHERE id = :id AND is_active = 1")
    suspend fun resetToRecommended(id: Long): Int

    /**
     * **回收被淘汰的旧 AI 行**（修复 C2）：把 [ids] 中**非用户手改**的行置为停用（`is_active = 0`）。
     *
     * 🔒 **只做 `UPDATE`，绝不 `DELETE` / `REPLACE`**（父表红线，架构 §6.3 坑 3/4）：
     * 软删除保留唯一槽位，避免"先删再建"重建行、错乱历史关联。
     * 二次兜底 `is_user_edited = 0`：即使用例漏筛，也**不会**误停用用户手改行。
     *
     * @param ids 待淘汰的行主键（调用方已按"AI 生成 / 已启用 / 不在本次写入集合内"筛过）
     * @return 实际被停用的行数（`retiredCount` 的真源）
     */
    @Query("UPDATE week_plans SET is_active = 0 WHERE id IN (:ids) AND is_user_edited = 0")
    suspend fun deactivateGenerated(ids: List<Long>): Int

    /**
     * AI 生成前的保护判定：该**周**的「天 × 动作」是否被用户动过（**含软删行**）。
     *
     * P3 起按周判定：用户改了"下周三"，不该影响"这周三"能不能被 AI 重排。
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM week_plans WHERE day_of_week = :dayOfWeek " +
            "AND exercise_id = :exerciseId AND week_start_epoch_day = :weekStartEpochDay " +
            "AND is_user_edited = 1)"
    )
    suspend fun hasUserEdited(
        dayOfWeek: Int,
        exerciseId: Long,
        weekStartEpochDay: Long,
    ): Boolean

    @Query("DELETE FROM week_plans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM week_plans")
    suspend fun clearAll()
}
