package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.ironhabit.app.data.local.dto.CategoryRaw
import com.ironhabit.app.data.local.dto.DayCountRaw
import com.ironhabit.app.data.local.dto.TrendRaw

/**
 * `StatsDao`：纯聚合查询（只读），返回 raw DTO，由 `StatsRepositoryImpl` 组装为 domain 模型。
 */
@Dao
interface StatsDao {

    /** 近 N 天趋势（有打卡的日期 → 次数）。 */
    @Query(
        """
        SELECT date_epoch_day AS epochDay, COUNT(*) AS count FROM check_ins
        WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay
        GROUP BY date_epoch_day ORDER BY date_epoch_day
        """
    )
    suspend fun trendRows(startEpochDay: Long, endEpochDay: Long): List<DayCountRaw>

    /** 热力图密度（有打卡的日期 → 次数）。 */
    @Query(
        """
        SELECT date_epoch_day AS epochDay, COUNT(*) AS count FROM check_ins
        WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay
        GROUP BY date_epoch_day ORDER BY date_epoch_day
        """
    )
    suspend fun heatmapRows(startEpochDay: Long, endEpochDay: Long): List<TrendRaw>

    /** 各动作分类的打卡次数。 */
    @Query(
        """
        SELECT e.category AS category, COUNT(*) AS count
        FROM check_ins c INNER JOIN exercises e ON c.exercise_id = e.id
        GROUP BY e.category
        """
    )
    suspend fun categoryShareRows(): List<CategoryRaw>

    /** 区间内打卡总次数。 */
    @Query("SELECT COUNT(*) FROM check_ins WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay")
    suspend fun checkInCount(startEpochDay: Long, endEpochDay: Long): Int

    /** 区间内有打卡的天数（去重）。 */
    @Query("SELECT COUNT(DISTINCT date_epoch_day) FROM check_ins WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay")
    suspend fun distinctActiveDays(startEpochDay: Long, endEpochDay: Long): Int
}
