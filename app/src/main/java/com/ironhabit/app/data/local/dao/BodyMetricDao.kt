package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ironhabit.app.data.local.entity.BodyMetricEntity
import com.ironhabit.app.domain.model.BodyMetricType
import kotlinx.coroutines.flow.Flow

/**
 * `body_metrics` 表 DAO：身体数据增删改查 + 按类型趋势。
 *
 * `body_metrics` **不是** CASCADE 父表，但为统一「写入不得重建 rowid」的口径，
 * [upsert] 同样采用显式 upsert（唯一键 `(type, date_epoch_day)`）。
 */
@Dao
interface BodyMetricDao {

    @Query("SELECT * FROM body_metrics WHERE type = :type ORDER BY date_epoch_day DESC")
    fun observeByType(type: BodyMetricType): Flow<List<BodyMetricEntity>>

    @Query("SELECT * FROM body_metrics WHERE type = :type ORDER BY date_epoch_day DESC LIMIT 1")
    suspend fun latest(type: BodyMetricType): BodyMetricEntity?

    @Query("SELECT * FROM body_metrics ORDER BY date_epoch_day DESC")
    suspend fun getAll(): List<BodyMetricEntity>

    @Query("SELECT * FROM body_metrics WHERE id = :id")
    suspend fun getById(id: Long): BodyMetricEntity?

    @Query("SELECT * FROM body_metrics WHERE type = :type AND date_epoch_day = :epochDay LIMIT 1")
    suspend fun getByTypeAndDate(type: BodyMetricType, epochDay: Long): BodyMetricEntity?

    /** 单行插入（冲突即抛 `ABORT`，**禁用 `REPLACE`**）。仅供 [upsert] 未命中路径使用。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: BodyMetricEntity): Long

    /** 按主键整行更新（供 [upsert] 命中路径使用，**保留原 rowid**）。 */
    @Update
    suspend fun update(entity: BodyMetricEntity)

    /**
     * 幂等 upsert（**禁用 `OnConflictStrategy.REPLACE`**，与 `CheckInDao.upsert` 同模式）。
     *
     * 命中已有行 → `UPDATE`（保住原 rowid），未命中 → `INSERT`。
     * 命中键：`id > 0` 时按主键（编辑既有记录）；否则按唯一 `(type, date_epoch_day)`（同日同类型幂等）。
     */
    @Transaction
    suspend fun upsert(entity: BodyMetricEntity): Long {
        val existing = if (entity.id > 0L) {
            getById(entity.id)
        } else {
            getByTypeAndDate(entity.type, entity.dateEpochDay)
        }
        return if (existing == null) {
            insert(entity)
        } else {
            update(entity.copy(id = existing.id))
            existing.id
        }
    }

    /**
     * 备份导入用：批量重建（调用方在导入事务内**已先 `clearAll()`**，
     * 此路径**有意**用 `REPLACE` 以保留原 id）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<BodyMetricEntity>): List<Long>

    @Query("DELETE FROM body_metrics WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM body_metrics")
    suspend fun clearAll()
}
