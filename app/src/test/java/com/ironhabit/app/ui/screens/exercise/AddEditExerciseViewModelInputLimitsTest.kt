package com.ironhabit.app.ui.screens.exercise

import androidx.lifecycle.SavedStateHandle
import com.ironhabit.app.R
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.test.MainDispatcherRule
import com.ironhabit.app.ui.navigation.Destinations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [AddEditExerciseViewModel] 输入范围校验（BUG 2）。
 *
 * 越界值必须被拒绝（复用 `error_invalid_number`）且**绝不落库**；
 * 合法边界值（含内置有氧动作的 `2400` 秒默认时长）仍必须能保存。
 * 名称非空 / 内置行锁定等既有行为不在本测试范围内。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddEditExerciseViewModelInputLimitsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val exerciseRepository = mockk<ExerciseRepository>(relaxed = true)

    private fun viewModel(): AddEditExerciseViewModel {
        coEvery { exerciseRepository.getById(any()) } returns null
        coEvery { exerciseRepository.nameExists(any(), any()) } returns false
        return AddEditExerciseViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Destinations.EXERCISE_ADD_EDIT_ARG to 0L)),
            exerciseRepository = exerciseRepository,
            clock = Clock.System,
        )
    }

    @Test
    fun zeroSetsIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onNameChange("自定义动作")
        vm.onDefaultSetsChange((InputLimits.MIN_SETS - 1).toString()) // "0"
        vm.onSave()
        advanceUntilIdle()

        assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
        assertNull("名称合法时不得报名称错", vm.uiState.value.nameErrorRes)
        coVerify(exactly = 0) { exerciseRepository.upsert(any()) }
    }

    @Test
    fun repsAboveMaxIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onNameChange("自定义动作")
        vm.onDefaultRepsChange((InputLimits.MAX_REPS + 1).toString()) // "101"
        vm.onSave()
        advanceUntilIdle()

        assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
        coVerify(exactly = 0) { exerciseRepository.upsert(any()) }
    }

    @Test
    fun durationAboveMaxIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onNameChange("自定义动作")
        vm.onDefaultDurationChange((InputLimits.MAX_DURATION_SEC + 1).toString()) // "7201"
        vm.onSave()
        advanceUntilIdle()

        assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
        coVerify(exactly = 0) { exerciseRepository.upsert(any()) }
    }

    /** 内置有氧动作的默认时长口径（`2400` 秒）必须仍然可保存，否则编辑内置行会被自身校验拦下。 */
    @Test
    fun builtInCardioDurationValueIsStillSavable() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onNameChange("自定义动作")
        vm.onDefaultSetsChange(InputLimits.MIN_SETS.toString())
        vm.onDefaultDurationChange("2400") // 内置有氧动作（快走/骑行）的默认时长
        vm.onSave()
        advanceUntilIdle()

        assertNull("内置有氧默认时长不得被误拒", vm.uiState.value.numberErrorRes)
        coVerify(exactly = 1) { exerciseRepository.upsert(any()) }
    }
}
