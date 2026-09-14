package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ironhabit.app.data.local.entity.BodyMetricEntity
import com.ironhabit.app.domain.model.BodyMetricType
import kotlinx.coroutines.flow.Flow

/**
 * `body_metrics` 表 DAO：身体数据增删改查 + 按类型趋势。
 */
@Dao
interface BodyMetricDao {

    @Query("SELECT * FROM body_metrics WHERE type = :type ORDER BY date_epoch_day DESC")
    fun observeByType(type: BodyMetricType): Flow<List<BodyMetricEntity>>

    @Query("SELECT * FROM body_metrics WHERE type = :type ORDER BY date_epoch_day DESC LIMIT 1")
    suspend fun latest(type: BodyMetricType): BodyMetricEntity?

    @Query("SELECT * FROM body_metrics ORDER BY date_epoch_day DESC")
    suspend fun getAll(): List<BodyMetricEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BodyMetricEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BodyMetricEntity>): List<Long>

    @Query("DELETE FROM body_metrics WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM body_metrics")
    suspend fun clearAll()
}
