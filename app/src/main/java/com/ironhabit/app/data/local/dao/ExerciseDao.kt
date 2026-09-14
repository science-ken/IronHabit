package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ironhabit.app.data.local.entity.ExerciseEntity
import com.ironhabit.app.domain.model.ExerciseCategory
import kotlinx.coroutines.flow.Flow

/**
 * `exercises` 表 DAO：动作库增删改查 + 分类筛选 + 播种。
 */
@Dao
interface ExerciseDao {

    @Query("SELECT * FROM exercises WHERE is_active = 1 ORDER BY sort_order, name")
    fun observeActive(): Flow<List<ExerciseEntity>>

    /**
     * 观察全部**已停用**动作（`is_active = 0`）。
     *
     * 供训练页「已停用」分组使用：停用即从启用列表消失，必须有出口才能一键恢复，
     * 否则「误关动作」将不可逆（需重装 App）。
     */
    @Query("SELECT * FROM exercises WHERE is_active = 0 ORDER BY sort_order, name")
    fun observeInactive(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE is_active = 1 AND category = :category ORDER BY sort_order, name")
    fun observeByCategory(category: ExerciseCategory): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): ExerciseEntity?

    @Query("SELECT * FROM exercises ORDER BY sort_order, name")
    suspend fun getAll(): List<ExerciseEntity>

    @Query("SELECT COUNT(*) FROM exercises WHERE name = :name AND id != :excludeId")
    suspend fun countByName(name: String, excludeId: Long): Int

    /** 幂等插入：名称冲突（唯一索引）时忽略。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: ExerciseEntity): Long

    /** 幂等批量插入：返回每行的 rowId，被忽略的行为 `-1`。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<ExerciseEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExerciseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ExerciseEntity>): List<Long>

    @Query("UPDATE exercises SET is_active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("UPDATE exercises SET times_used = times_used + 1 WHERE id = :id")
    suspend fun bumpUsage(id: Long)

    @Query("DELETE FROM exercises")
    suspend fun clearAll()
}
