package com.ironhabit.app.data.notification

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [triggerAtMillis] —— 提醒时刻的时区换算（审查报告 P1-6）。
 *
 * 旧写法是「当天 00:00 的绝对毫秒 + `hour × 3_600_000`」，隐含"一天恒等于 24 小时"。
 * 下面第一条就是它的反例：在夏令时切换日，同样钟面的两个时刻**不是**整 48 小时。
 *
 * 用户手机在 `Asia/Shanghai`（无夏令时），所以这条对他本人零影响 —— 修的是正确性债：
 * 换到有 DST 的时区（出国、或用户改系统时区）时，提醒不该整体偏一小时。
 */
class ReminderTriggerTimeTest {

    private val millisPerHour = 3_600_000L

    @Test
    fun springForwardGapIsHonouredInsteadOfAssuming24HourDays() {
        val newYork = TimeZone.of("America/New_York")
        val saturday = LocalDate(2026, 3, 7).toEpochDays().toLong()
        val monday = LocalDate(2026, 3, 9).toEpochDays().toLong()

        val before: Long = triggerAtMillis(saturday, daysAhead = 0L, hour = 2, minute = 30, timeZone = newYork)
        val after: Long = triggerAtMillis(monday, daysAhead = 0L, hour = 2, minute = 30, timeZone = newYork)

        assertEquals(
            "纽约 2026-03-08 凌晨 02:00 直接跳到 03:00，钟面的 02:30 相隔 47 小时而不是 48 小时。" +
                "旧写法（00:00 + 固定毫秒）算出来恰好是 48 小时，提醒于是整体偏一小时",
            47L * millisPerHour,
            after - before,
        )
    }

    @Test
    fun zoneWithoutDaylightSavingIsStillExactlyOneDayApart() {
        val shanghai = TimeZone.of("Asia/Shanghai")
        val day = LocalDate(2026, 9, 22).toEpochDays().toLong()

        val today = triggerAtMillis(day, daysAhead = 0L, hour = 20, minute = 0, timeZone = shanghai)
        val tomorrow = triggerAtMillis(day, daysAhead = 1L, hour = 20, minute = 0, timeZone = shanghai)

        assertEquals(24L * millisPerHour, tomorrow - today)
    }

    /** 换算是双向的：算出来的绝对时刻读回本地钟面，必须还是用户设的那个时间。 */
    @Test
    fun computedInstantStillReadsBackAsTheRequestedClockFace() {
        val newYork = TimeZone.of("America/New_York")
        val day = LocalDate(2026, 6, 15).toEpochDays().toLong()

        val local = Instant.fromEpochMilliseconds(
            triggerAtMillis(day, daysAhead = 0L, hour = 7, minute = 45, timeZone = newYork),
        ).toLocalDateTime(newYork)

        assertEquals(15, local.dayOfMonth)
        assertEquals(7, local.hour)
        assertEquals(45, local.minute)
    }
}
