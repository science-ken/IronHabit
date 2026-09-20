package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ironhabit.app.data.local.entity.MealItemEntity
import kotlinx.coroutines.flow.Flow

/**
 * `meal_items` 表 DAO：一餐里"实际吃了什么"的条目。
 *
 * ⚠️ 与 `MealDao` / `FoodDao` 同一条红线：**禁用 `OnConflictStrategy.REPLACE`**。
 * 本表是两张外键的**子表**，REPLACE = DELETE + INSERT 会换 rowid；
 * 虽然目前没有表指向它，但换 id 会让"编辑一条"变成"删了重建"，
 * 顺带把 `created_at` 丢掉 —— 显式 [upsert] 保住 rowid。
 *
 * ## 为什么"取某天的条目"要 JOIN meals
 * 条目只挂在餐上，日期在 `meals` 那一行。所以按天取必须走 join，
 * 并且**沿用 `meals.is_active = 1` 的过滤**：一餐被"这餐不吃"软删之后，
 * 它下面的条目也不能再计入当天营养 —— 否则删掉晚餐反而热量没变。
 */
@Dao
interface MealItemDao {

    @Query(
        """
        SELECT mi.* FROM meal_items mi
        JOIN meals m ON m.id = mi.meal_id
        WHERE m.date_epoch_day = :epochDay AND m.is_active = 1
        ORDER BY m.sort_order, mi.sort_order, mi.id
        """
    )
    fun observeByDate(epochDay: Long): Flow<List<MealItemEntity>>

    /**
     * [observeByDate] 的一次性版本：周复盘要连着算七天，起七个 Flow 再 each 一条
     * 不如让调用方按天取。过滤口径**必须与上面逐字一致**（含 `is_active = 1`），
     * 否则"流里看到的"和"复盘算出来的"会是两套数。
     */
    @Query(
        """
        SELECT mi.* FROM meal_items mi
        JOIN meals m ON m.id = mi.meal_id
        WHERE m.date_epoch_day = :epochDay AND m.is_active = 1
        ORDER BY m.sort_order, mi.sort_order, mi.id
        """
    )
    suspend fun getByDate(epochDay: Long): List<MealItemEntity>

    @Query("SELECT * FROM meal_items WHERE meal_id = :mealId ORDER BY sort_order, id")
    suspend fun getByMeal(mealId: Long): List<MealItemEntity>

    @Query("SELECT * FROM meal_items ORDER BY id")
    suspend fun getAll(): List<MealItemEntity>

    /** 条目数上限判据用（`InputLimits.MAX_ITEMS_PER_MEAL`）。 */
    @Query("SELECT COUNT(*) FROM meal_items WHERE meal_id = :mealId")
    suspend fun countByMeal(mealId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MealItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<MealItemEntity>): List<Long>

    @Update
    suspend fun update(entity: MealItemEntity)

    @Query("SELECT * FROM meal_items WHERE id = :id")
    suspend fun getById(id: Long): MealItemEntity?

    /**
     * 幂等 upsert：`id > 0` 且命中 → `UPDATE`（保住 rowid 与 `created_at`）；否则 `INSERT`。
     */
    @androidx.room.Transaction
    suspend fun upsert(entity: MealItemEntity): Long {
        val existing = if (entity.id > 0L) getById(entity.id) else null
        return if (existing == null) {
            insert(entity)
        } else {
            update(entity.copy(id = existing.id, createdAt = existing.createdAt))
            existing.id
        }
    }

    /**
     * 物理删除一条条目。
     *
     * 与 `foods` / `meals` 只软删**刻意不同**：条目本身就是"记错了"的那个动作，
     * 给它留一个 `is_active = 0` 的尸体没有任何消费方会读，
     * 只会让"这餐吃了什么"的查询到处多一个 WHERE。
     * 撤销误记靠的就是这里 —— 所以它必须真的消失。
     */
    @Query("DELETE FROM meal_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 挪餐次（早餐记成午餐了）。只改归属，快照与 created_at 一律不动。 */
    @Query("UPDATE meal_items SET meal_id = :targetMealId, sort_order = :sortOrder WHERE id = :id")
    suspend fun moveToMeal(id: Long, targetMealId: Long, sortOrder: Int)

    /** 备份恢复用（调用方已在恢复事务里先 [clearAll]，无行可级联，安全）。 */
    @Query("DELETE FROM meal_items")
    suspend fun clearAll()
}
