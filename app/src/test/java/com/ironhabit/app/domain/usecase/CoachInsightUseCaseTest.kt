package com.ironhabit.app.domain.usecase

import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * [CoachInsightUseCase] 单测（进度解读卡）。
 *
 * 核心不变量：**数字只来自本地聚合**（[BuildCoachContextUseCase]），本用例不产生任何文案。
 *
 * 以前这个文件有 4 条在测"联网成功 / 无 Key / HTTP 503 / 响应畸形"四种回落，
 * 随 API Key 直连通道一起删除 —— 那些分支已经不存在，留着只会一直绿而什么都不证明。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachInsightUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val buildCoachContext = mockk<BuildCoachContextUseCase>()

    private val localContext = CoachContext(
        checkInCount = 6,
        windowDays = 14,
        averageRpe = 7.0,
        weightDeltaKg = -0.6f,
        currentStreak = 3,
        todayIntakeKcal = 1500,
        todayPlanKcal = 2300,
    )

    private fun useCase(): CoachInsightUseCase = CoachInsightUseCase(
        buildCoachContext = buildCoachContext,
        ioDispatcher = mainDispatcherRule.testDispatcher,
    )

    @Test
    fun resultCarriesLocalNumbers() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { buildCoachContext(any()) } returns localContext

        val result = useCase()()

        assertEquals(6, result.context.checkInCount)
        assertEquals(3, result.context.currentStreak)
        assertEquals(-0.6f, result.context.weightDeltaKg)
    }

    @Test
    fun windowDays_isForwardedToContextBuilder() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { buildCoachContext(any()) } returns localContext

        useCase()()
        coVerify(exactly = 1) { buildCoachContext(CoachInsightUseCase.WINDOW_DAYS) }

        useCase()(7)
        coVerify(exactly = 1) { buildCoachContext(7) }
    }
}
