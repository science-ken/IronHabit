package com.ironhabit.app.ui.screens.bodymetrics

import com.ironhabit.app.R
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [BodyMetricsViewModel] 输入范围校验（BUG 2）。
 *
 * 数值必须落在**当前指标类型**的区间内（见 [InputLimits.rangeFor]）：越界一律拒绝并提示
 * （复用 `error_invalid_number`）、**不写库**；边界值仍必须能保存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyMetricsViewModelInputLimitsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val bodyMetricRepository = mockk<BodyMetricRepository>(relaxed = true)

    private fun viewModel(): BodyMetricsViewModel {
        every { bodyMetricRepository.observeByType(any()) } returns flowOf(emptyList())
        coEvery { bodyMetricRepository.latest(any()) } returns null
        return BodyMetricsViewModel(
            bodyMetricRepository = bodyMetricRepository,
            clock = Clock.System,
            timeZone = TimeZone.UTC,
        )
    }

    @Test
    fun weightBelowRangeIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        val belowMin = InputLimits.rangeFor(BodyMetricType.WEIGHT).start - 10f // 10 kg
        vm.onValueChange(belowMin.toString())
        vm.onAddRecord()
        advanceUntilIdle()

        assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
        coVerify(exactly = 0) { bodyMetricRepository.upsert(any()) }
    }

    @Test
    fun bodyFatAboveRangeIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSelectType(BodyMetricType.BODY_FAT)
        advanceUntilIdle()

        val aboveMax = InputLimits.rangeFor(BodyMetricType.BODY_FAT).endInclusive + 5f // 75 %
        vm.onValueChange(aboveMax.toString())
        vm.onAddRecord()
        advanceUntilIdle()

        assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
        coVerify(exactly = 0) { bodyMetricRepository.upsert(any()) }
    }

    @Test
    fun nanTextIsRejectedAndNeverSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onValueChange("NaN") // 能 parse 成 Float，但不是有限值
        vm.onAddRecord()
        advanceUntilIdle()

        assertEquals(R.string.error_invalid_number, vm.uiState.value.numberErrorRes)
        coVerify(exactly = 0) { bodyMetricRepository.upsert(any()) }
    }

    @Test
    fun rangeBoundaryValueIsStillSaved() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onValueChange(InputLimits.rangeFor(BodyMetricType.WEIGHT).start.toString()) // 20 kg
        vm.onAddRecord()
        advanceUntilIdle()

        assertNull("边界值不得被误拒", vm.uiState.value.numberErrorRes)
        coVerify(exactly = 1) { bodyMetricRepository.upsert(any()) }
    }
}
