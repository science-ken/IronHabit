package com.ironhabit.app.domain.util

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

/**
 * 日期与时区工具（纯函数，无 Android 依赖，便于 JVM 单测）。
 *
 * **日期口径**（架构 §7.3）：全工程「按天」一律用 `LocalDate.toEpochDays(): Int` 换算为
 * `Long` 后使用；展示/区间排序另存 `startOfDayMillis`。
 *
 * ⚠️ kotlinx-datetime 0.6.1 API 事实：
 * - `LocalDate.toEpochDays()` 返回 **Int**（函数名带 s），无 `toEpochDay()`；
 * - `atStartOfDayIn` / `toLocalDateTime` 是**扩展函数**，已显式 import；
 * - `Instant.toEpochMilliseconds()` 是**成员函数**，不 import。
 */
object DateUtils {

    /** 取 `clock` 在 `timeZone` 下的「今天」epochDay。 */
    fun todayEpochDay(clock: Clock, timeZone: TimeZone): Long =
        clock.now().toLocalDateTime(timeZone).date.toEpochDays().toLong()

    /** 把 epochDay 换算为 `timeZone` 下当天 00:00 的 UTC 毫秒时间戳。 */
    fun startOfDayMillis(epochDay: Long, timeZone: TimeZone): Long =
        LocalDate.fromEpochDays(epochDay.toInt()).atStartOfDayIn(timeZone).toEpochMilliseconds()

    /** 由 epochDay 计算星期：`1` = 周一 … `7` = 周日（`LocalDate.dayOfWeek.value` 天然满足）。 */
    fun weekdayMon1(epochDay: Long): Int =
        LocalDate.fromEpochDays(epochDay.toInt()).dayOfWeek.value

    /**
     * 取该 epochDay **所在周的周一**（P3：计划按周存放，处处要这一维）。
     *
     * `weekStart = epochDay − (((epochDay + 3) % 7 + 7) % 7)`：
     * epochDay `0` = 1970-01-01 是**周四**，故它所在周的周一是 `-3`；
     * 外层再补一次 `+7 % 7` 是因为 Kotlin 的 `%` 对负数取余仍为负数。
     *
     * ⚠️ 全工程**只有这一处**实现这个换算：`PlanRepositoryImpl`、`GetTodayOverviewUseCase`、
     * `BuildWeeklyReviewUseCase.weekStartOf` 都转发到这里（数据库迁移 `MIGRATION_4_5` 里那份
     * 是 SQL 侧的一次性副本，注释里互相点名）。
     */
    fun weekStartMon1(epochDay: Long): Long =
        epochDay - (((epochDay + MONDAY_ALIGN_OFFSET) % DAYS_IN_WEEK + DAYS_IN_WEEK) % DAYS_IN_WEEK)

    /** 一周的天数。 */
    const val DAYS_IN_WEEK: Long = 7

    /** epochDay `0`（周四）距其所在周周一的偏移。 */
    private const val MONDAY_ALIGN_OFFSET: Long = 3
}
