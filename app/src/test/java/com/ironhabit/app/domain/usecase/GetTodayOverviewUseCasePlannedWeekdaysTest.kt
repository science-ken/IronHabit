package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.Habit
import com.ironhabit.app.domain.model.HabitLog
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.PlanRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
 * [GetTodayOverviewUseCase] 的 **P3 plannedWeekdays 断链回归**。
 *
 * 症状（用户报告）：今日页只能看「选中那一天」的计划，看不到本周其他天 ——
 * 日期栏 `PlanDateStrip` 的 chip 永远不出现（`plannedWeekdays` 恒为空），
 * 且本周明明有课，翻到休息日却显示「这一周还没有训练计划」卡（`hasPlanThisWeek` 恒 false）。
 *
 * 根因：P3 重构时 ViewModel 退掉了独立的 `observePlannedWeekdays()` 流，注释声称
 * 「`getTodayOverview(day)` 已经带回了那一天所在周的结果」，但 `TodayOverview`
 * 模型根本没有 `plannedWeekdays` 字段 —— 数据在 UseCase 内部算出来只喂给了 streak，
 * 从未走出 UseCase。修复 = 随 `TodayOverview` 带出去；本测试守住这条出口。
 *
 * 仓库全部用 mockk 显式打桩（`combine` 需要每个 Flow 至少发射一次）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetTodayOverviewUseCasePlannedWeekdaysTest {

    private val utc = TimeZone.UTC
    /** 2026-02-16（周一）所在的测试周。 */
    private val cursor = LocalDate(2026, 2, 18).toEpochDays().toLong() // 周三
    private val noonMillis =
        LocalDate(2026, 2, 21).atStartOfDayIn(utc).toEpochMilliseconds() + 12L * 3_600_000L
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

    private fun stubAll(
        weekPlans: List<WeekPlan>,
        dayPlans: List<WeekPlan> = emptyList(),
    ) {
        every { planRepository.observeEffectivePlanForDay(any(), any()) } returns flowOf(dayPlans)
        every { planRepository.observeEffectivePlanForWeek(any()) } returns flowOf(weekPlans)
        every { checkInRepository.observeByDate(any()) } returns flowOf(emptyList<CheckIn>())
        every { exerciseRepository.observeActive() } returns flowOf(emptyList<Exercise>())
        every { checkInRepository.observeActiveDaysSince(any()) } returns flowOf(emptyList())
        every { habitRepository.observeActiveHabits() } returns flowOf(emptyList<Habit>())
        every { habitRepository.observeLogsBetween(any(), any()) } returns flowOf(emptyList<HabitLog>())
    }

    private fun plan(weekday: Int, exerciseId: Long): WeekPlan = WeekPlan(
        id = exerciseId,
        exerciseId = exerciseId,
        dayOfWeek = weekday,
        weekStartEpochDay = 0L, // 「每周相同」形态即可，UseCase 不关心
    )

    @Test
    fun plannedWeekdays_comeFromThatWeek_andAreSortedDistinct() = runTest {
        // 该周排了周一三五，且发射时乱序 + 周三重复 → 输出必须去重升序。
        stubAll(
            weekPlans = listOf(
                plan(weekday = 5, exerciseId = 31L),
                plan(weekday = 1, exerciseId = 11L),
                plan(weekday = 3, exerciseId = 21L),
                plan(weekday = 3, exerciseId = 22L),
            ),
        )

        val overview = useCase(cursor).first()

        assertEquals(
            "日期栏 chip 的数据源必须是「那一周」排课日的去重升序",
            listOf(1, 3, 5),
            overview.plannedWeekdays,
        )
    }

    @Test
    fun plannedWeekdays_emptyWeek_yieldsEmptyList() = runTest {
        // 那一周没排任何课（P3：翻到没计划的周）→ 空列表，UI 据此显示「创建训练计划」。
        stubAll(weekPlans = emptyList())

        val overview = useCase(cursor).first()

        assertEquals(emptyList<Int>(), overview.plannedWeekdays)
    }

    @Test
    fun plannedWeekdays_usePerWeekQueries_notTemplateOnly() = runTest {
        // 口径守护：必须走 P3 的按周查询（带 weekStartEpochDay），
        // 不得回落到"只查模板"的旧口径，否则翻到下周时 chip 会串周。
        stubAll(weekPlans = listOf(plan(weekday = 2, exerciseId = 12L)))

        useCase(cursor).first()

        verify(exactly = 1) {
            planRepository.observeEffectivePlanForWeek(any())
        }
        verify(exactly = 1) {
            planRepository.observeEffectivePlanForDay(any(), any())
        }
    }
}
