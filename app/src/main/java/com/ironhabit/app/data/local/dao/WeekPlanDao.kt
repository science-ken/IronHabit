package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT * FROM week_plans ORDER BY day_of_week, sort_order")
    suspend fun getAll(): List<WeekPlanEntity>

    @Query("SELECT * FROM week_plans WHERE id = :id")
    suspend fun getById(id: Long): WeekPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WeekPlanEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<WeekPlanEntity>): List<Long>

    @Query("DELETE FROM week_plans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM week_plans")
    suspend fun clearAll()
}
