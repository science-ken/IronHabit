package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.MealItem
import kotlinx.coroutines.flow.Flow

/**
 * 一餐条目仓库（"实际吃了什么"）。
 *
 * 与 [MealRepository] 分开：那边管"这一餐"（计划 + 完成状态），这边管"这一餐里的每一样"。
 * 分开的理由是语义不同 —— `meals` 可以是 AI 排的，`meal_items` 只能是用户记的。
 */
interface MealItemRepository {

    /** 某天全部条目（已过滤掉软删的餐；按餐次顺序 → 条目顺序）。 */
    fun observeByDate(epochDay: Long): Flow<List<MealItem>>

    /** 某餐现有条目数（上限判据用）。 */
    suspend fun countByMeal(mealId: Long): Int

    /** 按 id 取一条（改份量前要先拿到它当时记了多少克）。 */
    suspend fun getById(itemId: Long): MealItem?

    /** 新增或更新一条，返回行 id。 */
    suspend fun upsert(item: MealItem): Long

    /** 删除一条（物理删 —— 撤销误记必须让它真的消失）。 */
    suspend fun delete(itemId: Long)

    /** 挪到另一餐（快照与 `created_at` 一律不动）。 */
    suspend fun moveTo(itemId: Long, targetMealId: Long)
}
