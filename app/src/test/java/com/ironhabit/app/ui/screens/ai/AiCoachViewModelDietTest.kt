package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.SuggestionResult
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.AskCoachUseCase
import com.ironhabit.app.domain.usecase.CoachAnswer
import com.ironhabit.app.domain.usecase.ExplainDietUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GeneratedDietSummary
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.SuggestExercisesUseCase
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [AiCoachViewModel] 的「饮食计划」单测（子项 B）。
 *
 * 关键不变量：
 * 1. **本地先落结果**：本地 [GenerateDietPlanUseCase] 的结果无论联网与否都必须进 state（数值可信）；
 * 2. **远端只加文字**：联网成功 → 写入 `dietAnalysis`；未联网 / 失败 → 保持 `null`（UI 退回本地依据卡）；
 * 3. **重新生成会清掉旧分析**，避免"数值已变、解释还是上一版"；
 * 4. 失败可重试（`onRetry` 重跑生成饮食）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachViewModelDietTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = mockk<SettingsRepository>()
    private val bodyMetricRepository = mockk<BodyMetricRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val credentials = mockk<AiCredentialsStore>(relaxed = true)
    private val generateTrainingPlan = mockk<GenerateTrainingPlanUseCase>(relaxed = true)
    private val suggestExercises = mockk<SuggestExercisesUseCase>()
    private val askCoach = mockk<AskCoachUseCase>(relaxed = true)
    private val generateDietPlan = mockk<GenerateDietPlanUseCase>()
    private val explainDiet = mockk<ExplainDietUseCase>()

    private val utc = TimeZone.UTC
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_772_000_000_000L)
    }

    private fun newViewModel(aiRemote: Boolean = true, hasKey: Boolean = true): AiCoachViewModel {
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        every { settingsRepository.aiRemoteEnabled() } returns flowOf(aiRemote)
        every { bodyMetricRepository.observeByType(any()) } returns flowOf(emptyList())
        every { exerciseRepository.observeActive() } returns flowOf(emptyList())
        every { credentials.isConfigured() } returns hasKey
        coEvery { suggestExercises.suggest() } returns SuggestionResult(
            suggestions = emptyList(),
            source = AdviceSource.LOCAL_RULES,
        )
        return AiCoachViewModel(
            settingsRepository = settingsRepository,
            bodyMetricRepository = bodyMetricRepository,
            exerciseRepository = exerciseRepository,
            aiCredentialsStore = credentials,
            generateTrainingPlan = generateTrainingPlan,
            suggestExercises = suggestExercises,
            askCoach = askCoach,
            generateDietPlan = generateDietPlan,
            explainDiet = explainDiet,
            clock = clock,
            timeZone = utc,
        )
    }

    private fun stubDiet(
        writtenCount: Int = 4,
        preservedCount: Int = 0,
        filteredCount: Int = 0,
        usedDefaults: Boolean = false,
    ) {
        coEvery { generateDietPlan(any()) } returns GeneratedDietSummary(
            writtenCount = writtenCount,
            preservedCount = preservedCount,
            target = DietTarget(
                targetKcal = 2300,
                targetProtein = 150,
                usedDefaults = usedDefaults,
            ),
            filteredCount = filteredCount,
        )
    }

    @Test
    fun generateDiet_ok_writesLocalNumbers_andKeepsRemoteAnalysis() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        stubDiet(preservedCount = 1)
        coEvery { explainDiet(any()) } returns CoachAnswer.Ok("训练日多吃主食，蛋白质分三餐吃。")

        viewModel.generateDiet()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val summary = state.dietSummary
        assertEquals(4, summary?.writtenCount)
        assertEquals(1, summary?.preservedCount)
        assertEquals(2300, summary?.targetKcal)
        assertEquals(150, summary?.targetProtein)
        assertEquals("远端分析应写入 state", "训练日多吃主食，蛋白质分三餐吃。", state.dietAnalysis)
        assertEquals(false, state.isGeneratingDiet)
    }

    @Test
    fun generateDiet_withoutRemote_keepsLocalNumbers_andNoAnalysis() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel(aiRemote = false)
        advanceUntilIdle()
        stubDiet(filteredCount = 2)
        coEvery { explainDiet(any()) } returns CoachAnswer.NeedsNetwork(RemoteFallbackReason.REMOTE_DISABLED)

        viewModel.generateDiet()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("离线也要有完整本地结果", 2300, state.dietSummary?.targetKcal)
        assertEquals("忌口过滤数应进 state", 2, state.dietSummary?.filteredCount)
        assertNull("未联网不得编造分析", state.dietAnalysis)
    }

    @Test
    fun generateDiet_remoteFailure_keepsLocalNumbers_andNoAnalysis() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        stubDiet()
        coEvery { explainDiet(any()) } returns CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)

        viewModel.generateDiet()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2300, state.dietSummary?.targetKcal)
        assertNull(state.dietAnalysis)
        assertNull("远端分析失败不该弹页面级错误", state.errorRes)
    }

    @Test
    fun regenerate_clearsPreviousAnalysis() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        stubDiet()
        coEvery { explainDiet(any()) } returns CoachAnswer.Ok("第一次分析")

        viewModel.generateDiet()
        advanceUntilIdle()
        assertEquals("第一次分析", viewModel.uiState.value.dietAnalysis)

        // 第二次：远端这次失败 → 旧分析必须被清掉，不能残留
        coEvery { explainDiet(any()) } returns CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
        viewModel.generateDiet()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.dietAnalysis)
    }

    @Test
    fun generateDiet_failure_setsError_andRetryRegenerates() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { generateDietPlan(any()) } throws IllegalStateException("db closed")

        viewModel.generateDiet()
        advanceUntilIdle()
        assertEquals("失败应给页面级错误（可重试）", com.ironhabit.app.R.string.error_save_failed, viewModel.uiState.value.errorRes)

        // 修好后重试：应重新调用生成
        stubDiet()
        coEvery { explainDiet(any()) } returns CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
        viewModel.onRetry()
        advanceUntilIdle()

        assertEquals(2300, viewModel.uiState.value.dietSummary?.targetKcal)
        assertNull(viewModel.uiState.value.errorRes)
        coVerify(exactly = 2) { generateDietPlan(any()) }
    }
}
