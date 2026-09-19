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
import kotlinx.datetime.TimeZone
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

    private fun viewModel(planId: Long = 0L, week: Long? = null): AddEditPlanViewModel {
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
            clock = Clock.System,
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
        val expected = DateUtils.weekStartMon1(DateUtils.todayEpochDay(Clock.System, TimeZone.UTC))
        assertEquals(expected, saveAndCapture(vm).weekStartEpochDay)
    }

    @Test
    fun templateSentinelZeroNeverReachesTheDatabase() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel(week = 0L)
        backgroundScope.launch { vm.uiState.collect { } }
        val expected = DateUtils.weekStartMon1(DateUtils.todayEpochDay(Clock.System, TimeZone.UTC))
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

    private companion object {
        const val ROUTE_WEEK = 20_495L
        const val OTHER_WEEK = 19_000L
    }
}
