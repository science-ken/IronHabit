package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.R
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.CoachInsightResult
import com.ironhabit.app.domain.usecase.CoachInsightUseCase
import com.ironhabit.app.domain.usecase.ExportWeekPackageUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.GeneratedDietSummary
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
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [AiCoachViewModel] 的「饮食计划」单测。
 *
 * 关键不变量：**饮食数值全部由本地 [GenerateDietPlanUseCase] 算出并落进 state**，
 * 失败可重试（`onRetry` 重跑生成饮食）。
 *
 * 以前另有 3 条测"远端分析写入 / 未联网不编造 / 重新生成清掉旧分析"，
 * 随 API Key 直连通道一起删除 —— 那一整段状态字段已经不在了。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachViewModelDietTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = mockk<SettingsRepository>()
    private val generateTrainingPlan = mockk<GenerateTrainingPlanUseCase>(relaxed = true)
    private val generateDietPlan = mockk<GenerateDietPlanUseCase>()
    private val buildWeeklyReview = mockk<BuildWeeklyReviewUseCase>(relaxed = true)
    private val exportWeekPackage = mockk<ExportWeekPackageUseCase>(relaxed = true)
    private val coachInsight = mockk<CoachInsightUseCase>(relaxed = true)

    private val utc = TimeZone.UTC
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_772_000_000_000L)
    }

    private fun newViewModel(): AiCoachViewModel {
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        coEvery { coachInsight(any()) } returns CoachInsightResult()
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
    fun generateDiet_writesLocalNumbers() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        stubDiet(preservedCount = 1)

        viewModel.generateDiet()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        val summary = state.dietSummary
        assertEquals(4, summary?.writtenCount)
        assertEquals(1, summary?.preservedCount)
        assertEquals(2300, summary?.targetKcal)
        assertEquals(150, summary?.targetProtein)
        assertEquals(false, state.isGeneratingDiet)
    }

    @Test
    fun generateDiet_carriesFilteredAndDefaultsFlags() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        stubDiet(filteredCount = 2, usedDefaults = true)

        viewModel.generateDiet()
        advanceUntilIdle()

        val summary = viewModel.uiState.value.dietSummary
        assertEquals("忌口过滤数应进 state", 2, summary?.filteredCount)
        assertEquals("用了默认目标值要能被 UI 诚实提示", true, summary?.usedDefaults)
    }

    @Test
    fun generateDiet_failure_setsError_andRetryRegenerates() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { generateDietPlan(any()) } throws IllegalStateException("db closed")

        viewModel.generateDiet()
        advanceUntilIdle()
        assertEquals(
            "失败应给页面级错误（可重试）",
            R.string.error_save_failed,
            viewModel.uiState.value.errorRes,
        )

        stubDiet()
        viewModel.onRetry()
        advanceUntilIdle()

        assertEquals(2300, viewModel.uiState.value.dietSummary?.targetKcal)
        assertNull(viewModel.uiState.value.errorRes)
        coVerify(exactly = 2) { generateDietPlan(any()) }
    }
}
