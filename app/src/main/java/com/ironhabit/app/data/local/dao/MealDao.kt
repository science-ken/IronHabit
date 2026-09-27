package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ironhabit.app.data.local.dto.DietTallyRaw
import com.ironhabit.app.data.local.dto.MealTotalsRaw
import com.ironhabit.app.data.local.entity.MealEntity
import kotlinx.coroutines.flow.Flow

/**
 * `meals` 表 DAO：当日查询 / 汇总 / 勾选 / 软删除 / **显式 upsert**。
 *
 * 🔒 **软删 + 重生成口径**（与 v2 `week_plans` 完全一致，见设计文档 §2.3）：
 * - 删除 = `softDelete`（`is_active = 0` + `is_user_edited = 1`），**禁止 `DELETE`**；
 * - 用户编辑 = [upsertUser]（显式 upsert + `is_user_edited = 1`），**禁用 `REPLACE`**；
 * - AI 生成 = [upsertGenerated]（显式 upsert + `is_user_edited = 0`，命中则**保留用户勾选**）。
 *
 * ⚠️ `is_active = 1` 过滤**必须**同时出现在 [observeByDate] 与 [observeTotals] 两处
 * （§6.3 防回归红线）；`is_completed` 只影响 intake、**不影响** plan。
 */
@Dao
interface MealDao {

    /** 观察某日启用餐列表（`is_active = 1`），按 `sort_order` 升序。 */
    @Query("SELECT * FROM meals WHERE date_epoch_day = :epochDay AND is_active = 1 ORDER BY sort_order")
    fun observeByDate(epochDay: Long): Flow<List<MealEntity>>

    /**
     * 「我的」页饮食台账那一行要的四个数，一次读回。
     *
     * 与 `dev.sh q` 里跑的是同一句，界面上每个数都能这样复现：
     * ```
     * SELECT (SELECT COUNT(*) FROM meals WHERE is_active=1 AND date_epoch_day<=<today>),        -- 32
     *        (SELECT COUNT(DISTINCT i.meal_id) FROM meal_items i JOIN meals m ON i.meal_id=m.id
     *            WHERE m.is_active=1 AND m.date_epoch_day<=<today>),                            -- 2  填了几餐
     *        (SELECT COUNT(*) FROM meal_items i JOIN meals m ... 同上),                          -- 2  几条食物
     *        (SELECT COALESCE(SUM(i.kcal),0) FROM ... 同上),                                     -- 194
     *        (SELECT MAX(m.date_epoch_day) FROM meals m WHERE ... AND EXISTS(
     *            SELECT 1 FROM meal_items x WHERE x.meal_id=m.id))                              -- 20716
     * ```
     * ⚠️ 分母只数 **`date_epoch_day <= 今天`** 的餐次：饮食计划会提前排到未来几天，
     * 把没到的餐次算进"缺口"，首屏那行金字就变成冤枉人。
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM meals
                WHERE is_active = 1 AND date_epoch_day <= :todayEpochDay) AS mealRowCount,
            (SELECT COUNT(DISTINCT i.meal_id) FROM meal_items i
                INNER JOIN meals m ON i.meal_id = m.id
                WHERE m.is_active = 1 AND m.date_epoch_day <= :todayEpochDay) AS filledMealCount,
            (SELECT COUNT(*) FROM meal_items i
                INNER JOIN meals m ON i.meal_id = m.id
                WHERE m.is_active = 1 AND m.date_epoch_day <= :todayEpochDay) AS itemCount,
            (SELECT COALESCE(SUM(i.kcal), 0) FROM meal_items i
                INNER JOIN meals m ON i.meal_id = m.id
                WHERE m.is_active = 1 AND m.date_epoch_day <= :todayEpochDay) AS itemKcal,
            (SELECT MAX(m.date_epoch_day) FROM meals m
                WHERE m.is_active = 1 AND m.date_epoch_day <= :todayEpochDay
                  AND EXISTS (SELECT 1 FROM meal_items x WHERE x.meal_id = m.id)) AS lastFilledEpochDay
        """
    )
    suspend fun dietTally(todayEpochDay: Long): DietTallyRaw

    /**
     * 当日合计。
     *
     * ⚠️ 空集时 `SUM` 返回 `NULL` —— **必须 `COALESCE`**，否则新的一天一进页面就崩。
     * `intakeKcal / intakeProtein` = 只算已完成餐；`planKcal / planProtein` = 全部餐。
     */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN is_completed = 1 THEN kcal ELSE 0 END), 0) AS intakeKcal, " +
            "COALESCE(SUM(CASE WHEN is_completed = 1 THEN protein_g ELSE 0 END), 0) AS intakeProtein, " +
            "COALESCE(SUM(kcal), 0) AS planKcal, " +
            "COALESCE(SUM(protein_g), 0) AS planProtein " +
            "FROM meals WHERE date_epoch_day = :epochDay AND is_active = 1"
    )
    fun observeTotals(epochDay: Long): Flow<MealTotalsRaw>

    /** 取某日**全部**餐（**含软删行**），一次性读取；AI 生成前用它判断手改槽位。 */
    @Query("SELECT * FROM meals WHERE date_epoch_day = :epochDay ORDER BY sort_order")
    suspend fun getByDateIncludingInactive(epochDay: Long): List<MealEntity>

    /** 按 `(日期, 餐次)` 精确定位一行（**含软删行**，因为软删行仍占唯一索引槽位）。 */
    @Query("SELECT * FROM meals WHERE date_epoch_day = :epochDay AND meal_type = :mealType LIMIT 1")
    suspend fun getByDateAndType(epochDay: Long, mealType: String): MealEntity?

    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun getById(id: Long): MealEntity?

    /** 该「日期 × 餐次」是否被用户动过（含软删行）。 */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM meals " +
            "WHERE date_epoch_day = :epochDay AND meal_type = :mealType AND is_user_edited = 1)"
    )
    suspend fun hasUserEdited(epochDay: Long, mealType: String): Boolean

    @Query("UPDATE meals SET is_completed = :done WHERE id = :id")
    suspend fun setCompleted(id: Long, done: Boolean)

    /** 软删除：保留唯一索引槽位 + 阻止重新生成复活。**禁止用 `DELETE`**（§2.3）。 */
    @Query("UPDATE meals SET is_active = 0, is_user_edited = 1 WHERE id = :id")
    suspend fun softDelete(id: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MealEntity): Long

    @Update
    suspend fun update(entity: MealEntity)

    // ---------------- 备份 / 恢复专用（B-2：meals 表此前完全漏出备份）----------------

    /** 全量读取（**含软删行**）—— 备份导出必须带走用户的「不吃这餐」记录，与其它 6 张表同口径。 */
    @Query("SELECT * FROM meals ORDER BY date_epoch_day, sort_order")
    suspend fun getAll(): List<MealEntity>

    /** 恢复 = 整体替换：先物理清空再按备份重灌（仅在 `BackupRepositoryImpl` 的事务内调用）。 */
    @Query("DELETE FROM meals")
    suspend fun clearAll()

    /** 恢复批量写入（清空后插入，`ABORT` 保证撞唯一槽位即报错回滚，绝不静默 REPLACE）。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<MealEntity>): List<Long>

    /**
     * AI 生成时的显式 upsert（**禁用 `OnConflictStrategy.REPLACE`**）。
     *
     * 命中 `(日期, 餐次)` 已有行（**含软删行**）→ `UPDATE`，但**只覆盖"计划内容"**
     * （`items_text` / `kcal` / `protein_g` / `sort_order`），**保留用户的完成勾选**
     * （`is_completed`）、`is_active`、`is_user_edited`、`created_at` 与主键 rowid；
     * 未命中 → `INSERT`。
     *
     * 🔒 绝不 `DELETE` / `REPLACE`；也不做「先删当天再重建」（会一次丢失全部勾选与手改）。
     */
    @Transaction
    suspend fun upsertGenerated(entity: MealEntity): Long {
        val existing = getByDateAndType(entity.dateEpochDay, entity.mealType)
        return if (existing == null) {
            insert(entity.copy(id = 0L, isUserEdited = false))
        } else {
            update(
                existing.copy(
                    itemsText = entity.itemsText,
                    kcal = entity.kcal,
                    proteinG = entity.proteinG,
                    sortOrder = entity.sortOrder,
                )
            )
            existing.id
        }
    }

    /**
     * 用户编辑时的显式 upsert（**禁用 `OnConflictStrategy.REPLACE`**）。
     *
     * 先按主键 `id` 匹配（编辑已有行），未命中再按 `(日期, 餐次)` 槽位兜底（**含软删行 → 复活**）；
     * 置 `is_user_edited = 1`、`is_active = 1`，并**保留原完成勾选与创建时间**（编辑内容不重置打卡）。
     *
     * 改餐次时目标槽位已被占的两种情况，处理方式**不一样**（审查报告 P2-1）：
     * - 占位的是**软删行** → 内容搬进那一行并复活它，原来那一行改成软删。
     *   弹层这时已经明说"改过来会把你删掉的那一餐恢复并替换"，这里按那句兑现；
     *   两边都留软删/生效两行，是为了保住各自"别再给我重新排这一餐"的记忆
     *   （硬删掉那条占位行的话，下次生成饮食又会把同一餐排回来）。
     * - 占位的是**另一条活着的餐** → 不特殊处理，让它撞 `UNIQUE(date_epoch_day, meal_type)`。
     *   界面上那颗 chip 已经灰掉，走到这里只剩竞态（两个人同时改），
     *   由 `TodayViewModel` 落 `error_duplicate_meal` 一句准话。
     */
    @Transaction
    suspend fun upsertUser(entity: MealEntity): Long {
        if (entity.id != 0L) {
            val byId = getById(entity.id)
            if (byId != null) {
                val occupant: MealEntity? = getByDateAndType(entity.dateEpochDay, entity.mealType)
                if (occupant != null && occupant.id != byId.id && !occupant.isActive) {
                    update(
                        occupant.copy(
                            itemsText = entity.itemsText,
                            kcal = entity.kcal,
                            proteinG = entity.proteinG,
                            sortOrder = entity.sortOrder,
                            isUserEdited = true,
                            isActive = true,
                            // 「吃了这餐」跟着内容走：它是这一餐的状态，不是槽位的。
                            isCompleted = byId.isCompleted,
                            createdAt = occupant.createdAt,
                        )
                    )
                    softDelete(byId.id)
                    return occupant.id
                }
                update(
                    entity.copy(
                        id = byId.id,
                        isUserEdited = true,
                        isActive = true,
                        isCompleted = byId.isCompleted,
                        createdAt = byId.createdAt,
                    )
                )
                return byId.id
            }
        }
        val existing = getByDateAndType(entity.dateEpochDay, entity.mealType)
        return if (existing == null) {
            insert(entity.copy(id = 0L, isUserEdited = true, isActive = true))
        } else {
            update(
                entity.copy(
                    id = existing.id,
                    isUserEdited = true,
                    isActive = true,
                    isCompleted = existing.isCompleted,
                    createdAt = existing.createdAt,
                )
            )
            existing.id
        }
    }
}
