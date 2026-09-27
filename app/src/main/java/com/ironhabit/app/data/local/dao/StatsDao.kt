package com.ironhabit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.ironhabit.app.data.local.dto.CategoryRaw
import com.ironhabit.app.data.local.dto.CheckInTallyRaw
import com.ironhabit.app.data.local.dto.DayCountRaw
import com.ironhabit.app.data.local.dto.TrendRaw

/**
 * `StatsDao`：纯聚合查询（只读），返回 raw DTO，由 `StatsRepositoryImpl` 组装为 domain 模型。
 *
 * ## 本文件的每一条 `check_ins` 计数都带 `completed_sets > 0`
 * 取消勾选最后一组时 `CheckInDao.toggleSet` **不删行**，只把位图 XOR 成 0（保留"是哪几组"的身份）。
 * 于是库里会留下"完成了 0 组"的残行。它不是一次打卡：算进去，streak 会 +1、热力图会点亮、
 * 饮食侧会按训练日多给热量，而同屏的条目显示"未完成"—— 审查报告 P2-5 就是这个自相矛盾。
 * 刻意不改成"取消最后一组就删行"：那会连带丢掉那一行上的 RPE 与备注，且行 id 不稳定。
 * 判据放在查询侧，一处规定、全文件同守。
 */
@Dao
interface StatsDao {

    /** 近 N 天趋势（有打卡的日期 → 次数）。 */
    @Query(
        """
        SELECT date_epoch_day AS epochDay, COUNT(*) AS count FROM check_ins
        WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay AND completed_sets > 0
        GROUP BY date_epoch_day ORDER BY date_epoch_day
        """
    )
    suspend fun trendRows(startEpochDay: Long, endEpochDay: Long): List<DayCountRaw>

    /** 热力图密度（有打卡的日期 → 次数）。 */
    @Query(
        """
        SELECT date_epoch_day AS epochDay, COUNT(*) AS count FROM check_ins
        WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay AND completed_sets > 0
        GROUP BY date_epoch_day ORDER BY date_epoch_day
        """
    )
    suspend fun heatmapRows(startEpochDay: Long, endEpochDay: Long): List<TrendRaw>

    /**
     * 各动作分类的打卡次数，**限定在 `[start, end]` 这段区间内**。
     *
     * ⚠️ 以前它没有区间参数（全历史累计），而同一屏的趋势卡是「近 30 天」——
     * 两张图并排放着却说着两段时间，界面上还不标注。
     */
    @Query(
        """
        SELECT e.category AS category, COUNT(*) AS count
        FROM check_ins c INNER JOIN exercises e ON c.exercise_id = e.id
        WHERE c.date_epoch_day BETWEEN :startEpochDay AND :endEpochDay AND c.completed_sets > 0
        GROUP BY e.category
        """
    )
    suspend fun categoryShareRows(startEpochDay: Long, endEpochDay: Long): List<CategoryRaw>

    /** 区间内打卡总次数。 */
    @Query(
        "SELECT COUNT(*) FROM check_ins " +
            "WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay AND completed_sets > 0"
    )
    suspend fun checkInCount(startEpochDay: Long, endEpochDay: Long): Int

    /**
     * 「我的」页台账那一行要的四个数，一次读回。
     *
     * 与 `dev.sh q` 里跑的是同一句，界面上每个数都能这样复现：
     * ```
     * SELECT COUNT(*), COALESCE(SUM(completed_sets),0), COALESCE(SUM(completed_reps),0),
     *        COALESCE(SUM(CASE WHEN rpe IS NOT NULL THEN 1 ELSE 0 END),0)
     * FROM check_ins WHERE date_epoch_day BETWEEN 0 AND <today> AND completed_sets > 0
     * -- 2026-09-23 真机：12 | 25 | 52 | 6
     * ```
     * ⚠️ 上面那个样本是**加 `completed_sets > 0` 之前**量到的：当时库里还没有"取消勾选后
     * 剩下的空行"，所以四个数今天看仍然对得上；哪天对不上，先怀疑残行。
     * `COALESCE` 不是装饰：一条都没打过时 `SUM` 返回 `NULL`，非空列会直接抛。
     */
    @Query(
        """
        SELECT COUNT(*) AS rowCount,
               COALESCE(SUM(completed_sets), 0) AS setCount,
               COALESCE(SUM(completed_reps), 0) AS repCount,
               COALESCE(SUM(CASE WHEN rpe IS NOT NULL THEN 1 ELSE 0 END), 0) AS rpeRowCount
        FROM check_ins
        WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay AND completed_sets > 0
        """
    )
    suspend fun checkInTally(startEpochDay: Long, endEpochDay: Long): CheckInTallyRaw

    /** 区间内有打卡的天数（去重）。 */
    @Query(
        "SELECT COUNT(DISTINCT date_epoch_day) FROM check_ins " +
            "WHERE date_epoch_day BETWEEN :startEpochDay AND :endEpochDay AND completed_sets > 0"
    )
    suspend fun distinctActiveDays(startEpochDay: Long, endEpochDay: Long): Int
}
