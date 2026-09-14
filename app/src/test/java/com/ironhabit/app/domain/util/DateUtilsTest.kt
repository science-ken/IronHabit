package com.ironhabit.app.domain.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DateUtils] 单测（固定 Clock + UTC，保证可复现）。
 *
 * 重点验证 kotlinx-datetime 0.6.1 的正确 API 用法：`toEpochDays()` / `atStartOfDayIn` / `toLocalDateTime`。
 */
class DateUtilsTest {

    private val utc = TimeZone.UTC
    private val baseDate = LocalDate(2026, 2, 21)
    private val baseEpochDay = baseDate.toEpochDays().toLong()
    private val noonMillis = baseDate.atStartOfDayIn(utc).toEpochMilliseconds() + 12L * 3_600_000L

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(noonMillis)
    }

    @Test
    fun todayEpochDay_equalsLocalDateEpochDays() {
        assertEquals(baseEpochDay, DateUtils.todayEpochDay(fixedClock, utc))
    }

    @Test
    fun startOfDayMillis_isLocalMidnight() {
        val millis = DateUtils.startOfDayMillis(baseEpochDay, utc)
        assertEquals(baseDate.atStartOfDayIn(utc).toEpochMilliseconds(), millis)

        val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(utc)
        assertEquals(0, local.hour)
        assertEquals(0, local.minute)
        assertEquals(0, local.second)
    }

    @Test
    fun weekdayMon1_knownAnchors() {
        // 1970-01-01 = 周四 → 4
        assertEquals(4, DateUtils.weekdayMon1(0L))
        // 1970-01-04 = 周日 → 7
        assertEquals(7, DateUtils.weekdayMon1(3L))
        // 1970-01-05 = 周一 → 1
        assertEquals(1, DateUtils.weekdayMon1(4L))
        // 2026-02-21 = 周六 → 6
        assertEquals(6, DateUtils.weekdayMon1(baseEpochDay))
    }
}
