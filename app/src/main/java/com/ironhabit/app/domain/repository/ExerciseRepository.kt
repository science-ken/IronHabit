package com.ironhabit.app.domain.repository

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import kotlinx.coroutines.flow.Flow

/**
 * 动作仓库接口（domain 定义，data 实现）。
 */
interface ExerciseRepository {

    /** 观察全部启用动作（按 `sortOrder`、`name` 升序）。 */
    fun observeActive(): Flow<List<Exercise>>

    /**
     * 观察全部**已停用**动作（`isActive = false`）。
     *
     * 供「已停用」分组恢复用：停用后动作从 [observeActive] 消失，必须另有出口，
     * 否则「误关动作」不可逆。
     */
    fun observeInactive(): Flow<List<Exercise>>

    /** 观察某分类下的启用动作。 */
    fun observeByCategory(category: ExerciseCategory): Flow<List<Exercise>>

    /** 按 id 获取动作，不存在返回 `null`。 */
    suspend fun getById(id: Long): Exercise?

    /** 新增或更新动作，返回行 id。 */
    suspend fun upsert(exercise: Exercise): Long

    /** 启用 / 停用动作。 */
    suspend fun setActive(id: Long, active: Boolean)

    /** 动作名是否已存在（`excludeId` 用于编辑时排除自身）。 */
    suspend fun nameExists(name: String, excludeId: Long): Boolean

    /** 打卡后累加动作使用次数（供排序）。 */
    suspend fun bumpUsage(exerciseId: Long)

    /** 幂等播种内置动作，返回**本次新插入**的条数（已存在的不计）。 */
    suspend fun seedBuiltIns(): Int
}
