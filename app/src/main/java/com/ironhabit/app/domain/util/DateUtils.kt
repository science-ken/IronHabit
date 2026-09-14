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
}
