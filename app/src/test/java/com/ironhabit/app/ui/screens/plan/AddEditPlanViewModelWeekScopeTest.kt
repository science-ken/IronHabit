package com.ironhabit.app.ui.screens.plan

import androidx.lifecycle.SavedStateHandle
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.test.MainDispatcherRule
import com.ironhabit.app.ui.navigation.Destinations
import io.mockk.CapturingSlot
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * [AddEditPlanViewModel] 的**周归属**（P3：计划按周存放）。
 *
 * 钉住四件事，其中第 3 条是这次改动真正的风险：
 * 1. 路由带了 `week` 就写到那一周 —— 今日页翻到下周点「自己创建」不该落在本周；
 * 2. 没带才回落到当前这一周；
 * 3. `week = 0` 是「每周相同」那份的哨兵值，**不能**当成"没指定"，
 *    否则一次误传就把模板改了 —— 用户看到的是"以后每周都多这一条"；
 * 4. 编辑已有行沿用该行自己的周，不被路由上的周"搬"走。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlanViewModelWeekScopeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val exerciseRepository = mockk<ExerciseRepository>(relaxed = true)
    private val planRepository = mockk<PlanRepository>(relaxed = true)

    /** 固定时钟（V3 报告 B-3）：消除"断言口径与 VM 内部取值跨午夜不一致"的偶发 flake 窗口。 */
    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-20T04:00:00Z")
    }

    /** @param clock 默认用固定时钟；只有跨周那条测试需要拨动它。 */
    private fun viewModel(
        planId: Long = 0L,
        week: Long? = null,
        clock: Clock = fixedClock,
    ): AddEditPlanViewModel {
        every { exerciseRepository.observeActive() } returns flowOf(emptyList())
        every { planRepository.observeAll() } returns flowOf(existingRows(planId))
        return AddEditPlanViewModel(
            savedStateHandle = SavedStateHandle(
                buildMap {
                    put(Destinations.PLAN_ARG_ID, planId)
                    put(Destinations.PLAN_ARG_DAY, 1)
                    // 只有显式传了 week 才放这个键，用来区分"未指定"与"指定为 0"。
                    if (week != null) put(Destinations.PLAN_ARG_WEEK, week)
                },
            ),
            exerciseRepository = exerciseRepository,
            planRepository = planRepository,
            clock = clock,
            timeZone = TimeZone.UTC,
        )
    }

    private fun existingRows(planId: Long): List<WeekPlan> =
        if (planId == 0L) {
            emptyList()
        } else {
            listOf(
                WeekPlan(
                    id = planId,
                    exerciseId = 5L,
                    dayOfWeek = 3,
                    targetSets = 4,
                    targetReps = 10,
                    weekStartEpochDay = OTHER_WEEK,
                    createdAt = 1L,
                ),
            )
        }

    private suspend fun TestScope.saveAndCapture(vm: AddEditPlanViewModel): WeekPlan {
        val captured: CapturingSlot<WeekPlan> = slot()
        vm.onSelectExercise(5L)
        vm.onSave()
        advanceUntilIdle()
        coVerify { planRepository.upsert(capture(captured)) }
        return captured.captured
    }

    @Test
    fun newPlanWrittenToRouteWeek() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel(week = ROUTE_WEEK)
        backgroundScope.launch { vm.uiState.collect { } }
        assertEquals(ROUTE_WEEK, saveAndCapture(vm).weekStartEpochDay)
    }

    @Test
    fun newPlanWithoutRouteWeekFallsBackToCurrentWeek() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        val expected = DateUtils.weekStartMon1(DateUtils.todayEpochDay(fixedClock, TimeZone.UTC))
        assertEquals(expected, saveAndCapture(vm).weekStartEpochDay)
    }

    @Test
    fun templateSentinelZeroNeverReachesTheDatabase() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel(week = 0L)
        backgroundScope.launch { vm.uiState.collect { } }
        val expected = DateUtils.weekStartMon1(DateUtils.todayEpochDay(fixedClock, TimeZone.UTC))
        assertEquals(
            "week=0 是「每周相同」的哨兵值，路由上出现它只能当「没指定」，写进库就是在改模板",
            expected,
            saveAndCapture(vm).weekStartEpochDay,
        )
    }

    @Test
    fun editingExistingRowKeepsItsOwnWeek() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel(planId = 7L, week = ROUTE_WEEK)
        backgroundScope.launch { vm.uiState.collect { } }
        val saved = saveAndCapture(vm)
        assertEquals(OTHER_WEEK, saved.weekStartEpochDay)
        assertEquals(7L, saved.id)
    }

    /**
     * 可以拨动的时钟：这条测试要的正是"表单开着的时候跨过了周一 00:00"。
     * 用真实时钟就只能在 UTC 午夜前后那几分钟才复现（也就是 V2-P3-9 说的 flake 窗口）。
     */
    private class MovingClock(startMillis: Long) : Clock {

        private val current: AtomicLong = AtomicLong(startMillis)

        override fun now(): Instant = Instant.fromEpochMilliseconds(current.get())

        fun advanceTo(millis: Long) {
            current.set(millis)
        }
    }

    /**
     * 表单开着跨过周一 00:00 → 行必须落在**新的一周**（审查报告 V2-P3-6）。
     *
     * 旧实现把归属周在构造期算一次存成 `val`：周日夜里打开、周一凌晨保存的那一条
     * 会静默进到上一周，而用户在"这一周"的清单里看不到它 —— 今日页与训练页的同型问题
     * 2026-09-20 修过，这一处当时漏了。
     */
    @Test
    fun weekRolloverWhileTheFormIsOpenLandsInTheNewWeek() =
        runTest(mainDispatcherRule.testDispatcher) {
            val sunday: LocalDate = LocalDate(2026, 9, 27) // 周日
            val monday: LocalDate = LocalDate(2026, 9, 28) // 第二天就是周一
            val clock = MovingClock(
                sunday.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 20L * 3_600_000L,
            )
            val vm = viewModel(clock = clock)
            backgroundScope.launch { vm.uiState.collect { } }

            // 打开表单之后过了午夜：这一下就是"跨周"。
            clock.advanceTo(monday.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 60_000L)

            assertEquals(
                "周日夜里打开、周一凌晨保存：行要落进周一那一周，不能留在上一周",
                DateUtils.weekStartMon1(monday.toEpochDays().toLong()),
                saveAndCapture(vm).weekStartEpochDay,
            )
        }

    private companion object {
        const val ROUTE_WEEK = 20_495L
        const val OTHER_WEEK = 19_000L
    }
}
