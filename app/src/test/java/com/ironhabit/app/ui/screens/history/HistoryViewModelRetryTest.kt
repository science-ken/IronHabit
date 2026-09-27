package com.ironhabit.app.ui.screens.history

import com.ironhabit.app.R
import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.StatsRepository
import com.ironhabit.app.domain.usecase.GetHeatmapUseCase
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * 「打卡历史」的页内重试（审查报告 V2-P2-2 / P2-7 那一族）。
 *
 * 这一页以前是 `.catch` 直接挂在 combine 外面：读库失败一次，流就终止，
 * `errorRes` 从此冻在错误帧上，而页面上那颗重试按钮**根本不存在** ——
 * 用户唯一的出路是杀掉 App。所以这里钉两件必须同时成立的事：
 * 1. 失败帧确实进了错误态（否则"重试"这条测试是空跑的）；
 * 2. [HistoryViewModel.onRetry] 让上游**重新订阅了一次**并交出数据
 *    （只把 `errorRes` 清成 null 而没重订阅，等于把错误藏起来骗人）。
 *
 * 断言的形状照 `today/TodayViewModelDateCursorTest`：直接驱动生产 VM，只 mock 依赖。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelRetryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val checkInRepository = mockk<CheckInRepository>(relaxed = true)
    private val exerciseRepository = mockk<ExerciseRepository>(relaxed = true)
    private val getHeatmap = mockk<GetHeatmapUseCase>(relaxed = true)
    private val statsRepository = mockk<StatsRepository>(relaxed = true)
    private val clock = mockk<Clock>()

    private fun viewModel(): HistoryViewModel = HistoryViewModel(
        checkInRepository = checkInRepository,
        exerciseRepository = exerciseRepository,
        getHeatmap = getHeatmap,
        statsRepository = statsRepository,
        clock = clock,
        timeZone = TimeZone.UTC,
    )

    /** 一次读库失败、一次给出当天记录：重新登记桩就等于"这一次订阅要读到什么"。 */
    private fun stubObserveBetween(shouldFail: Boolean) {
        every { checkInRepository.observeBetween(any(), any()) } returns
            if (shouldFail) {
                flow { throw IOException("模拟数据库读失败") }
            } else {
                flowOf(listOf(CheckIn(id = 7L, exerciseId = 3L, dateEpochDay = EPOCH_DAY, completedSetsMask = 0b1)))
            }
    }

    @Test
    fun retryResubscribesTheFailedFlowAndClearsTheError() = runTest {
        every { clock.now() } returns Instant.fromEpochMilliseconds(NOW_MILLIS)
        every { exerciseRepository.observeActive() } returns flowOf(emptyList())
        coEvery { getHeatmap(any()) } returns emptyList()
        coEvery { statsRepository.completionRate(any(), any()) } returns 0f
        stubObserveBetween(shouldFail = true)

        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(
            "读库失败必须落到错误态，否则下面那条断言是空跑的",
            R.string.error_load_failed,
            viewModel.uiState.value.errorRes,
        )

        // 换桩而**不重建 VM**：只有"同一个人身上重新读到好数据"才证明重试真的重订阅了上游。
        stubObserveBetween(shouldFail = false)
        viewModel.onRetry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("重试后仍停在错误态 = onRetry 没有真的重订阅上游", state.errorRes)
        assertEquals("重试后应交出那一条历史", 1, state.days.size)
        assertEquals(1, state.days.single().items.size)
    }

    private companion object {
        const val EPOCH_DAY: Long = 20_724L
        const val NOW_MILLIS: Long = EPOCH_DAY * 86_400_000L
    }
}
