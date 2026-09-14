package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ironhabit.app.data.local.entity.HabitEntity
import kotlinx.coroutines.flow.Flow

/**
 * `habits` 表 DAO：习惯定义增删改查。
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HabitEntity): Long

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
