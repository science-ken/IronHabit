package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.SuggestionResult
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.AskCoachUseCase
import com.ironhabit.app.domain.usecase.CoachContext
import com.ironhabit.app.domain.usecase.CoachInsightResult
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.CoachInsightUseCase
import com.ironhabit.app.domain.usecase.ExportWeekPackageUseCase
import com.ironhabit.app.domain.usecase.ExplainDietUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
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
 * [AiCoachViewModel] 的「进度解读」单测（子项 C）。
 *
 * 关键不变量：
 * 1. 页面打开（init）就拉一次解读；
 * 2. **数字永远来自本地聚合**（无论来源是 AI 还是本地规则，`context` 都必须在）；
 * 3. 来源要如实反映：AI 文案可用 → `REMOTE_LLM`；否则 `LOCAL_RULES`；
 * 4. 解读失败**不写页面级错误**（因为本地小结仍然可用），且可手动「重新解读」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachViewModelInsightTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = mockk<SettingsRepository>()
    private val credentials = mockk<AiCredentialsStore>(relaxed = true)
    private val generateTrainingPlan = mockk<GenerateTrainingPlanUseCase>(relaxed = true)
    private val askCoach = mockk<AskCoachUseCase>(relaxed = true)
    private val generateDietPlan = mockk<GenerateDietPlanUseCase>(relaxed = true)
    private val explainDiet = mockk<ExplainDietUseCase>(relaxed = true)
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

    private fun newViewModel(aiRemote: Boolean = true, hasKey: Boolean = true): AiCoachViewModel {
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        every { settingsRepository.aiRemoteEnabled() } returns flowOf(aiRemote)
        every { credentials.isConfigured() } returns hasKey
        return AiCoachViewModel(
            settingsRepository = settingsRepository,
            aiCredentialsStore = credentials,
            generateTrainingPlan = generateTrainingPlan,
            planPreviewHolder = com.ironhabit.app.domain.usecase.PlanPreviewHolder(),
            askCoach = askCoach,
            generateDietPlan = generateDietPlan,
            explainDiet = explainDiet,
            coachInsight = coachInsight,
            buildWeeklyReview = buildWeeklyReview,
            exportWeekPackage = exportWeekPackage,
            clock = clock,
            timeZone = utc,
        )
    }

    @Test
    fun insight_isLoadedOnInit_withLocalNumbersOnly() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { coachInsight(any()) } returns CoachInsightResult(
            source = AdviceSource.LOCAL_RULES,
            context = localContext,
            fallbackReason = RemoteFallbackReason.REMOTE_DISABLED,
        )

        val viewModel = newViewModel(aiRemote = false)
        advanceUntilIdle()

        val result = viewModel.uiState.value.insightResult
        assertEquals(AdviceSource.LOCAL_RULES, result?.source)
        assertNull("未联网不得有 AI 文案", result?.text)
        assertEquals("本地数字必须在", 5, result?.context?.checkInCount)
        assertFalse(viewModel.uiState.value.isLoadingInsight)
        coVerify(exactly = 1) { coachInsight(any()) }
    }

    @Test
    fun insight_remoteOk_keepsAiTextAndLocalNumbers() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { coachInsight(any()) } returns CoachInsightResult(
            source = AdviceSource.REMOTE_LLM,
            text = "近两周出勤 5 次，强度合适；下周深蹲加 2.5kg。",
            context = localContext,
        )

        val viewModel = newViewModel()
        advanceUntilIdle()

        val result = viewModel.uiState.value.insightResult
        assertEquals(AdviceSource.REMOTE_LLM, result?.source)
        assertEquals("近两周出勤 5 次，强度合适；下周深蹲加 2.5kg。", result?.text)
        assertEquals("AI 文案之外，本地数字也必须在", 4, result?.context?.currentStreak)
    }

    @Test
    fun insight_failure_keepsLocalResult_andNoPageError() = runTest(mainDispatcherRule.testDispatcher) {
        coEvery { coachInsight(any()) } returns CoachInsightResult(
            source = AdviceSource.LOCAL_RULES,
            context = localContext,
            fallbackReason = RemoteFallbackReason.REMOTE_ERROR,
        )

        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(RemoteFallbackReason.REMOTE_ERROR, state.insightResult?.fallbackReason)
        assertEquals(5, state.insightResult?.context?.checkInCount)
        assertNull("解读失败不该占页面级错误位", state.errorRes)
    }

    @Test
    fun insight_useCaseThrows_stillProducesLocalResultCard() = runTest(mainDispatcherRule.testDispatcher) {
        // 兜底路径：即使用例本身抛异常（理论上不该发生），页面也不能崩、不能空着。
        coEvery { coachInsight(any()) } throws IllegalStateException("db closed")

        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(0, state.insightResult?.context?.checkInCount)
        assertFalse(state.isLoadingInsight)
        assertNull(state.errorRes)
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
