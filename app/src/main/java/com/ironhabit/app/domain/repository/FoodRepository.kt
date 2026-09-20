package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.Food
import kotlinx.coroutines.flow.Flow

/**
 * 食物库仓库接口。
 *
 * 只有 CRUD + 播种入口，**不含任何 assets 细节** ——
 * 读哪个文件、怎么解析都在 data 层（见 `FoodSeeder`），与 `ExerciseRepository.seedBuiltIns` 同一口径。
 */
interface FoodRepository {

    /** 观察全部启用中的食物（停用 = 本表唯一的删除出口）。 */
    fun observeActive(): Flow<List<Food>>

    /** 按 id 取单条（含已停用 —— 历史记录要能点进来看当时吃的是什么）。 */
    suspend fun getFood(foodId: Long): Food?

    /** 重名检查（排除自己）。唯一索引是最终防线，这个只用来给友好报错。 */
    suspend fun nameExists(name: String, excludeId: Long = 0L): Boolean

    /** 新增或更新一条食物（含它的份量行），返回行 id。 */
    suspend fun upsert(food: Food): Long

    /** 停用一条食物（`is_active = 0`）。物理删除目前不提供，见 `FoodDao.deactivate` 的说明。 */
    suspend fun deactivate(foodId: Long)

    /**
     * 幂等播种内置食物库，返回本次新增条数。
     *
     * 失败（assets 缺失 / JSON 写坏）时返回 0 并记日志，**不抛** —— 启动链路不该因为库空而崩。
     */
    suspend fun seedBuiltIns(): Int
}
