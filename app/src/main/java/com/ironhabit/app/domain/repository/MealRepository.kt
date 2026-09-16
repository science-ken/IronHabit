package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealTotals
import kotlinx.coroutines.flow.Flow

/**
 * 饮食仓库接口（domain 层定义，data 层实现 —— 依赖倒置）。
 *
 * 软删 / upsert 语义收敛在实现内（与 [PlanRepository] 同一套 v2 红线）：
 * - 删除 = **软删除**（`isActive = false` + `isUserEdited = true`），绝不 `DELETE` / `REPLACE`；
 * - 用户编辑 = **显式 upsert** 且置 `isUserEdited = true`；
 * - AI 生成 = **显式 upsert** 且置 `isUserEdited = false`，且跳过手改行（由 UseCase 筛）。
 */
interface MealRepository {

    /** 观察某日启用餐列表（`is_active = 1`），按 `sortOrder` 升序。 */
    fun observeMeals(epochDay: Long): Flow<List<Meal>>

    /** 观察某日合计（已摄入 / 计划）。空集时 `COALESCE` 兜底为 0。 */
    fun observeTotals(epochDay: Long): Flow<MealTotals>

    /**
     * 取某日**全部**餐（**含 `isActive == false` 的软删行**），一次性读取。
     *
     * 🔒 **AI 生成前必须拿全量**：软删行仍占 `UNIQUE(date_epoch_day, meal_type)` 槽位，
     * 且可能带 `isUserEdited = true` —— 只看启用行会误往该槽位写入，把用户删掉的餐「复活」。
     */
    suspend fun getMealsIncludingInactive(epochDay: Long): List<Meal>

    /**
     * AI 生成入口：对「可写槽位」做**显式 upsert**，并置 `isUserEdited = false`。
     *
     * 🔒 命中已存在行时**保留用户的完成勾选**（只覆盖计划内容），且**绝不做任何 DELETE**。
     * 写入前由 UseCase 排除手改槽位，本方法不再二次过滤。
     *
     * @return 实际写入的条数
     */
    suspend fun upsertGenerated(meals: List<Meal>): Int

    /**
     * 用户编辑入口：**显式 upsert** 并置 `isUserEdited = true`（保护用户改动，禁止 REPLACE）。
     *
     * @return 行 id
     */
    suspend fun upsert(meal: Meal): Long

    /** 勾选 / 取消一餐的完成态（只改 `is_completed`，不触碰 `is_user_edited`）。 */
    suspend fun setCompleted(id: Long, done: Boolean)

    /** 软删除一餐（`isActive = false` + `isUserEdited = true`），**保留唯一索引槽位**。 */
    suspend fun delete(id: Long)
}
