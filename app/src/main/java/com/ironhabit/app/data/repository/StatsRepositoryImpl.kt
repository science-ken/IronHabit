package com.ironhabit.app.data.repository

import com.ironhabit.app.data.local.dao.StatsDao
import com.ironhabit.app.domain.model.CategoryShare
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.HeatmapCell
import com.ironhabit.app.domain.model.TrendPoint
import com.ironhabit.app.domain.repository.StatsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * [StatsRepository] 的 data 层实现：把 raw DTO 组装为 domain 统计模型。
 */
@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val statsDao: StatsDao,
    private val clock: Clock,
    private val timeZone: TimeZone,
) : StatsRepository {

    override suspend fun trendPoints(days: Int): List<TrendPoint> {
        val range = dayRange(days)
        val rows = statsDao.trendRows(range.first, range.second)
        val byDay = rows.associate { it.epochDay to it.count }
        return (range.first..range.second).map { epochDay ->
            TrendPoint(epochDay = epochDay, count = byDay[epochDay] ?: 0)
        }
    }

    override suspend fun categoryShare(days: Int): List<CategoryShare> {
        val range = dayRange(days)
        val rows = statsDao.categoryShareRows(range.first, range.second)
        val total = rows.sumOf { it.count }
        return rows.map { row ->
            CategoryShare(
                category = parseCategory(row.category),
                count = row.count,
                ratio = if (total > 0) row.count.toFloat() / total.toFloat() else 0f,
            )
        }
    }

    override suspend fun heatmap(days: Int): List<HeatmapCell> {
        val range = dayRange(days)
        return cellsBetween(range.first, range.second)
    }

    override suspend fun heatmapRange(startEpochDay: Long, endEpochDay: Long): List<HeatmapCell> =
        cellsBetween(startEpochDay, endEpochDay)

    /** 铺满 `[start, end]` 每一天：没打卡的补 0 档，有打卡的按次数取密度档。 */
    private suspend fun cellsBetween(startEpochDay: Long, endEpochDay: Long): List<HeatmapCell> {
        if (endEpochDay < startEpochDay) return emptyList()
        val rows = statsDao.heatmapRows(startEpochDay, endEpochDay)
        val byDay = rows.associate { it.epochDay to it.count }
        return (startEpochDay..endEpochDay).map { epochDay ->
            val count = byDay[epochDay] ?: 0
            HeatmapCell(epochDay = epochDay, count = count, level = densityLevel(count))
        }
    }

    override suspend fun completionRate(startEpochDay: Long, endEpochDay: Long): Float {
        if (endEpochDay < startEpochDay) return 0f
        val totalDays = endEpochDay - startEpochDay + 1
        if (totalDays <= 0L) return 0f
        val activeDays = statsDao.distinctActiveDays(startEpochDay, endEpochDay)
        return activeDays.toFloat() / totalDays.toFloat() * PERCENT_SCALE
    }

    /** 返回「近 days 天」的闭区间 `[start, today]`（days < 1 时退化为仅今天）。 */
    private fun dayRange(days: Int): Pair<Long, Long> {
        val today = todayEpochDay()
        val span = (days - 1).coerceAtLeast(0).toLong()
        return (today - span) to today
    }

    /** 今天（本地时区）的 epochDay。 */
    private fun todayEpochDay(): Long =
        clock.now().toLocalDateTime(timeZone).date.toEpochDays().toLong()

    /** 打卡次数 → 热力图密度等级（0…4）。 */
    private fun densityLevel(count: Int): Int = when {
        count <= 0 -> 0
        1 == count -> 1
        count == 2 -> 2
        count == 3 -> 3
        else -> MAX_DENSITY_LEVEL
    }

    /** 分类名 → 枚举（无法识别时回落到 `CUSTOM`）。 */
    private fun parseCategory(name: String): ExerciseCategory =
        runCatching { ExerciseCategory.valueOf(name) }.getOrNull() ?: ExerciseCategory.CUSTOM

    private companion object {
        const val PERCENT_SCALE: Float = 100f
        const val MAX_DENSITY_LEVEL: Int = 4
    }
}
