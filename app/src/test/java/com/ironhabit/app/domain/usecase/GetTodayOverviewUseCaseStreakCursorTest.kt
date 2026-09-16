package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitLog
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.PlanRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [GetTodayOverviewUseCase] 的 **C5 回归**：训练连续天数必须按**游标日**（所选日）统计，
 * 而不是"真实今天"。修复前 `buildOverview` 调 `CalculateStreakUseCase` 时不传基准日，
 * 导致今日页切到历史某天时，streak 仍用真实今天 → 口径混用（游标日之后的活动被计入）。
 *
 * 仓库全部用 mockk 显式打桩（`combine` 需要每个 Flow 至少发射一次）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetTodayOverviewUseCaseStreakCursorTest {

    private val utc = TimeZone.UTC
    private val realToday = LocalDate(2026, 2, 21)
    private val realTodayEpoch = realToday.toEpochDays().toLong()
    private val noonMillis = realToday.atStartOfDayIn(utc).toEpochMilliseconds() + 12L * 3_600_000L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(noonMillis)
    }

    private val planRepository: PlanRepository = mockk(relaxed = true)
    private val checkInRepository: CheckInRepository = mockk(relaxed = true)
    private val habitRepository: HabitRepository = mockk(relaxed = true)
    private val exerciseRepository: ExerciseRepository = mockk(relaxed = true)

    private val useCase = GetTodayOverviewUseCase(
        planRepository = planRepository,
        checkInRepository = checkInRepository,
        habitRepository = habitRepository,
        exerciseRepository = exerciseRepository,
        calculateStreakUseCase = CalculateStreakUseCase(clock, utc),
    )

    private fun stubEmptyExceptActiveDays(activeDays: List<Long>) {
        // P3：计划按周取 —— 用例现在调的是带 `weekStartEpochDay` 的那两个方法。
        every { planRepository.observeEffectivePlanForDay(any(), any()) } returns flowOf(emptyList())
        every { checkInRepository.observeByDate(any()) } returns flowOf(emptyList<CheckIn>())
        every { exerciseRepository.observeActive() } returns flowOf(emptyList<Exercise>())
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(activeDays)
        every { habitRepository.observeActiveHabits() } returns flowOf(emptyList<Habit>())
        every { habitRepository.observeLogsBetween(any(), any()) } returns flowOf(emptyList<HabitLog>())
        every { planRepository.observeEffectivePlanForWeek(any()) } returns flowOf(emptyList())
    }

    @Test
    fun trainingStreak_usesCursorDay_notRealToday() = runTest {
        // 游标日 = 5 天前；同时存在"游标日之后"的活跃日（真实今天 & 昨天）。
        val cursor = realTodayEpoch - 5L
        val activeDays = listOf(realTodayEpoch, realTodayEpoch - 1L, cursor)
        stubEmptyExceptActiveDays(activeDays)

        val overview = useCase(cursor).first()

        assertEquals(
            "游标日之后的活动不得计入 streak（修复 C5：口径必须与所选日一致）",
            cursor,
            overview.trainingStreak.lastActiveEpochDay,
        )
        assertEquals(1, overview.trainingStreak.current)
    }

    @Test
    fun trainingStreak_cursorDayIsRealToday_countsLatestRun() = runTest {
        // 游标日 = 真实今天 → 与旧行为一致：三天连续计入。
        val activeDays = listOf(realTodayEpoch, realTodayEpoch - 1L, realTodayEpoch - 2L)
        stubEmptyExceptActiveDays(activeDays)

        val overview = useCase(realTodayEpoch).first()

        assertEquals(realTodayEpoch, overview.trainingStreak.lastActiveEpochDay)
        assertEquals(3, overview.trainingStreak.current)
    }
}
