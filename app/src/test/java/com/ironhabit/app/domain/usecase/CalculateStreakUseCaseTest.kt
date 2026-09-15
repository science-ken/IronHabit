package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.StreakInfo
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.domain.util.StreakCalculator
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CalculateStreakUseCase] 单测：验证「注入时钟取今天 + 委托纯函数」的语义。
 *
 * 使用固定 [Clock]（2026-02-21 noon UTC）保证可复现；断言结果与直接调用
 * [StreakCalculator] 完全一致，并额外校验边界（今天未练但昨天练 → 不断档）。
 */
class CalculateStreakUseCaseTest {

    private val utc = TimeZone.UTC
    private val baseDate = LocalDate(2026, 2, 21)
    private val baseEpochDay = baseDate.toEpochDays().toLong()
    private val noonMillis = baseDate.atStartOfDayIn(utc).toEpochMilliseconds() + 12L * 3_600_000L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(noonMillis)
    }

    private val useCase = CalculateStreakUseCase(clock = clock, timeZone = utc)

    @Test
    fun delegatesToPureCalculatorWithInjectedToday() {
        val days = listOf(baseEpochDay, baseEpochDay - 1L, baseEpochDay - 2L)

        val actual = useCase(days)
        val expected = StreakCalculator.calculate(
            sortedDescEpochDays = days,
            todayEpochDay = DateUtils.todayEpochDay(clock, utc),
        )

        assertEquals(expected, actual)
        assertEquals(3, actual.current)
        assertEquals(3, actual.best)
        assertEquals(baseEpochDay, actual.lastActiveEpochDay)
    }

    @Test
    fun todayNotLoggedButYesterdayLoggedDoesNotBreak() {
        // 今天尚未打卡，但昨天打过 → 连击不立即断（架构 §4.2）。
        val days = listOf(baseEpochDay - 1L, baseEpochDay - 2L)

        val actual: StreakInfo = useCase(days)

        assertEquals(2, actual.current)
        assertEquals(2, actual.best)
        assertEquals(baseEpochDay - 1L, actual.lastActiveEpochDay)
    }

    @Test
    fun emptyInputYieldsZeroStreak() {
        val actual: StreakInfo = useCase(emptyList())

        assertEquals(0, actual.current)
        assertEquals(0, actual.best)
        assertEquals(null, actual.lastActiveEpochDay)
    }

    @Test
    fun staleHistoryResetsCurrentButKeepsBest() {
        // 最近活跃在 5 天前 → current = 0，但 best 永久保留。
        val days = listOf(
            baseEpochDay - 5L,
            baseEpochDay - 6L,
            baseEpochDay - 7L,
            baseEpochDay - 8L,
        )

        val actual: StreakInfo = useCase(days)

        assertEquals(0, actual.current)
        assertEquals(4, actual.best)
        assertEquals(baseEpochDay - 5L, actual.lastActiveEpochDay)
    }

    @Test
    fun forwardsExpectedWeekdaysToCalculator() {
        // 2026-02-21 是周六：应做日「周一 / 周三 / 周五」= baseEpochDay-5 / -3 / -1，三天都完成。
        // 休息日（周二/周四/周六）不打断 → 当前段 = 20500..20504 共 5 天。
        val days = listOf(baseEpochDay - 1L, baseEpochDay - 3L, baseEpochDay - 5L)

        val actual: StreakInfo = useCase(days, expectedWeekdays = setOf(1, 3, 5))
        val expected: StreakInfo = StreakCalculator.calculate(
            sortedDescEpochDays = days,
            todayEpochDay = DateUtils.todayEpochDay(clock, utc),
            expectedWeekdays = setOf(1, 3, 5),
        )

        assertEquals(expected, actual)
        assertEquals(5, actual.current)
        assertEquals(5, actual.best)
    }
}
