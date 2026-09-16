package com.ironhabit.app.ui.screens.plan

import androidx.lifecycle.SavedStateHandle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.test.MainDispatcherRule
import com.ironhabit.app.ui.navigation.Destinations
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [AddEditPlanViewModel] 输入范围校验（BUG 2 — 本地表单必须与 AI 路径一样有边界）。
 *
 * 断言两件事：越界值被**拒绝并提示**（复用 `error_invalid_number`），且**绝不落库**；
 * 边界值本身仍可保存（防止修成「一律拒绝」）。
 *
 * 注：`uiState` 是 `WhileSubscribed` 的 combine 流 —— 必须有订阅者才会跟随表单更新，
 * 故每个用例都用 `backgroundScope` 挂一个收集器（测试结束自动取消）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditPlanViewModelInputLimitsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val exerciseRepository = mockk<ExerciseRepository>(relaxed = true)
    private val planRepository = mockk<PlanRepository>(relaxed = true)

    private fun viewModel(): AddEditPlanViewModel {
        every { exerciseRepository.observeActive() } returns flowOf(emptyList())
        every { planRepository.observeAll() } returns flowOf(emptyList())
        return AddEditPlanViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Destinations.PLAN_ARG_ID to 0L)),
            exerciseRepository = exerciseRepository,
            planRepository = planRepository,
            clock = Clock.System,
            // P3：手动新增计划要落到"当前这一周"，所以 VM 多了一个 timeZone 参数。
            timeZone = TimeZone.UTC,
        )
    }

    @Test
    fun setsAboveBitmaskWidthIsRejectedAndNeverSaved() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel()
            backgroundScope.launch { vm.uiState.collect { } }
            advanceUntilIdle()

            vm.onSelectExercise(exerciseId = 1L)
            vm.onTargetSetsChange((InputLimits.MAX_SETS + 1).toString()) // 32 组：位图放不下
            vm.onSave()
            advanceUntilIdle()

            assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
            assertNull("越界值不得落库", vm.uiState.value.snackbarRes)
            coVerify(exactly = 0) { planRepository.upsert(any()) }
        }

    @Test
    fun negativeWeightIsRejectedAndNeverSaved() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = viewModel()
            backgroundScope.launch { vm.uiState.collect { } }
            advanceUntilIdle()

            vm.onSelectExercise(exerciseId = 1L)
            vm.onTargetWeightChange("-10")
            vm.onSave()
            advanceUntilIdle()

            assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
            coVerify(exactly = 0) { planRepository.upsert(any()) }
        }

    @Test
    fun zeroDurationIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.onSelectExercise(exerciseId = 1L)
        vm.onTargetDurationChange("0")
        vm.onSave()
        advanceUntilIdle()

        assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
        coVerify(exactly = 0) { planRepository.upsert(any()) }
    }

    @Test
    fun boundaryValuesAreStillSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        vm.onSelectExercise(exerciseId = 1L)
        vm.onTargetSetsChange(InputLimits.MAX_SETS.toString()) // 31：合法上界
        vm.onTargetRepsChange(InputLimits.MAX_REPS.toString()) // 100：合法上界
        vm.onTargetWeightChange("0") // 自重：合法下界
        vm.onTargetDurationChange(InputLimits.MAX_DURATION_MIN.toString()) // 600：合法上界
        vm.onSave()
        advanceUntilIdle()

        assertNull("边界值不得被误拒", vm.uiState.value.numberErrorRes)
        coVerify(exactly = 1) { planRepository.upsert(any()) }
    }
}
