package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.CoachContext
import com.ironhabit.app.domain.usecase.CoachInsightResult
import com.ironhabit.app.domain.usecase.CoachInsightUseCase
import com.ironhabit.app.domain.usecase.ExportWeekPackageUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.PlanPreviewHolder
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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [AiCoachViewModel] 的「进度解读」单测。
 *
 * 关键不变量：
 * 1. 页面打开（init）就拉一次解读，且可手动「重新解读」；
 * 2. 数字来自本地聚合（[CoachInsightResult.context] 必须在）；
 * 3. 解读失败**不写页面级错误** —— 那一格空着就行，不该弹一张吓人的错误卡。
 *
 * 以前另有两条：一条测"AI 文案可用时 `source` 必须如实标成远端"，一条测
 * "回落原因是 REMOTE_ERROR 时结果仍带本地数字"。它们跟着 API Key 直连通道一起删了 ——
 * 那种结果现在构造不出来，测试留着也只会一直绿而什么都不证明。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachViewModelInsightTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = mockk<SettingsRepository>()
    private val generateTrainingPlan = mockk<GenerateTrainingPlanUseCase>(relaxed = true)
    private val generateDietPlan = mockk<GenerateDietPlanUseCase>(relaxed = true)
    private val buildWeeklyReview = mockk<BuildWeeklyReviewUseCase>(relaxed = true)
    private val exportWeekPackage = mockk<ExportWeekPackageUseCase>(relaxed = true)
    private val coachInsight = mockk<CoachInsightUseCase>()

    private val utc = TimeZone.UTC
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_772_000_000_000L)
    }

    /** 本地数字：解读卡里必须始终能看到它们。 */
    private val localContext = CoachContext(
        checkInCount = 5,
        windowDays = 14,
        averageRpe = 7.2,
        weightDeltaKg = -0.8f,
        currentStreak = 4,
    )

    private fun newViewModel(): AiCoachViewModel {
        // 必须先 stub 再构造：ViewModel 的 init 里就会订阅 profile()，
        // 非 relaxed 的 mock 在被桩好之前被调用会直接抛。
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        return AiCoachViewModel(
            settingsRepository = settingsRepository,
            generateTrainingPlan = generateTrainingPlan,
            planPreviewHolder = PlanPreviewHolder(),
            generateDietPlan = generateDietPlan,
            coachInsight = coachInsight,
            buildWeeklyReview = buildWeeklyReview,
            exportWeekPackage = exportWeekPackage,
            clock = clock,
            timeZone = utc,
        )
    }

    @Test
    fun insight_isLoadedOnInit_withLocalNumbers() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { coachInsight(any()) } returns CoachInsightResult(context = localContext)

        val viewModel = newViewModel()
        advanceUntilIdle()

        val result = viewModel.uiState.value.insightResult
        assertEquals("本地数字必须在", 5, result?.context?.checkInCount)
        assertEquals(4, result?.context?.currentStreak)
        assertFalse(viewModel.uiState.value.isLoadingInsight)
        coVerify(exactly = 1) { coachInsight(any()) }
    }

    @Test
    fun insight_useCaseThrows_stillProducesResultCard_andNoPageError() =
        runTest(mainDispatcherRule.testDispatcher) {
            // 兜底路径：即使用例本身抛异常（理论上不该发生），页面也不能崩、不能空着。
            coEvery { coachInsight(any()) } throws IllegalStateException("db closed")

            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(0, state.insightResult?.context?.checkInCount)
            assertFalse(state.isLoadingInsight)
            assertNull("解读失败不该占页面级错误位", state.errorRes)
        }

    @Test
    fun reloadInsight_refetches() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { coachInsight(any()) } returns CoachInsightResult(context = localContext)

        val viewModel = newViewModel()
        advanceUntilIdle()
        coVerify(exactly = 1) { coachInsight(any()) }

        viewModel.loadInsight()
        advanceUntilIdle()

        coVerify(exactly = 2) { coachInsight(any()) }
    }
}
