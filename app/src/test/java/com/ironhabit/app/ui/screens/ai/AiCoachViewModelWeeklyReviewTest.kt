package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.R
import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.BodyReview
import com.ironhabit.app.domain.model.DietReview
import com.ironhabit.app.domain.model.SuggestionResult
import com.ironhabit.app.domain.model.TrainingReview
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeeklyReview
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.AskCoachUseCase
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.CoachInsightUseCase
import com.ironhabit.app.domain.usecase.ExplainDietUseCase
import com.ironhabit.app.domain.usecase.ExportWeekPackageUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import com.ironhabit.app.domain.usecase.SuggestExercisesUseCase
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [AiCoachViewModel] 的周复盘 / 数据包部分（P2）单测。
 *
 * 覆盖点：
 * 1. 打开页面就载入本周复盘（纯本地）；
 * 2. `weekOffset` 只影响"算哪一周"，且**不许翻到未来**；
 * 3. 数据包只在用户点导出时才算（不让首帧多跑一次序列化）；
 * 4. 粒度开关变了要**立刻重算**（否则开关动了内容没动，看着像坏了）；
 * 5. 用例抛异常时不许崩：复盘失败 = "这周没数据"，导出失败 = 可见的错误。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachViewModelWeeklyReviewTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = mockk<SettingsRepository>()
    private val bodyMetricRepository = mockk<BodyMetricRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val credentials = mockk<AiCredentialsStore>(relaxed = true)
    private val generateTrainingPlan = mockk<GenerateTrainingPlanUseCase>(relaxed = true)
    private val suggestExercises = mockk<SuggestExercisesUseCase>()
    private val askCoach = mockk<AskCoachUseCase>(relaxed = true)
    private val generateDietPlan = mockk<GenerateDietPlanUseCase>(relaxed = true)
    private val explainDiet = mockk<ExplainDietUseCase>(relaxed = true)
    private val coachInsight = mockk<CoachInsightUseCase>(relaxed = true)
    private val buildWeeklyReview = mockk<BuildWeeklyReviewUseCase>()
    private val exportWeekPackage = mockk<ExportWeekPackageUseCase>()

    /** 固定"今天" = 2026-09-16（周三）→ 本周一是 2026-09-14。 */
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-16T10:00:00Z")
    }

    private val thisWeekStart: Long = LocalDate(2026, 9, 14).toEpochDays().toLong()

    private fun review(weekStart: Long = thisWeekStart): WeeklyReview = WeeklyReview(
        weekStartEpochDay = weekStart,
        weekEndEpochDay = weekStart + 6,
        training = TrainingReview(
            plannedDays = 3,
            completedDays = 2,
            totalVolumeKg = 5000f,
            totalSets = 20,
            avgRpe = 7f,
            progressed = emptyList(),
            stalled = emptyList(),
        ),
        body = BodyReview(startWeightKg = null, latestWeightKg = null),
        diet = DietReview(loggedDays = 0, avgKcal = null, avgProteinG = null),
    )

    private fun newViewModel(
        reviewResult: WeeklyReview = review(),
        json: String = "{\"schema\":\"ironhabit-week-package/v1\"}",
    ): AiCoachViewModel {
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        every { settingsRepository.aiRemoteEnabled() } returns flowOf(false)
        every { bodyMetricRepository.observeByType(any()) } returns flowOf(emptyList())
        every { exerciseRepository.observeActive() } returns flowOf(emptyList())
        every { credentials.isConfigured() } returns false
        coEvery { suggestExercises.suggest() } returns SuggestionResult(
            suggestions = emptyList(),
            source = AdviceSource.LOCAL_RULES,
        )
        // 桩按"被请求的那一周"返回对应的复盘 —— 这样断言结果就等于断言"VM 算对了哪一周"。
        coEvery { buildWeeklyReview(any()) } answers {
            review(firstArg<Long?>() ?: thisWeekStart)
        }
        coEvery { exportWeekPackage(any(), any()) } returns json
        return AiCoachViewModel(
            settingsRepository = settingsRepository,
            bodyMetricRepository = bodyMetricRepository,
            exerciseRepository = exerciseRepository,
            aiCredentialsStore = credentials,
            generateTrainingPlan = generateTrainingPlan,
            planPreviewHolder = com.ironhabit.app.domain.usecase.PlanPreviewHolder(),
            suggestExercises = suggestExercises,
            askCoach = askCoach,
            generateDietPlan = generateDietPlan,
            explainDiet = explainDiet,
            coachInsight = coachInsight,
            buildWeeklyReview = buildWeeklyReview,
            exportWeekPackage = exportWeekPackage,
            clock = clock,
            timeZone = TimeZone.UTC,
        )
    }

    @Test
    fun init_loadsThisWeekReview_withoutTouchingThePackage() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.weekOffset)
        assertEquals(thisWeekStart, viewModel.uiState.value.weeklyReview?.weekStartEpochDay)
        assertFalse(viewModel.uiState.value.isLoadingReview)
        assertNull("没点导出之前不许生成数据包", viewModel.uiState.value.weekPackageJson)
        coVerify(exactly = 0) { exportWeekPackage(any(), any()) }
    }

    @Test
    fun loadPreviousWeek_computesPreviousMonday() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.loadWeeklyReview(-1)
        advanceUntilIdle()

        assertEquals(
            "上一周的周一 = 本周一 − 7 天（桩按请求的周返回，所以断言结果就是断言算对了哪一周）",
            thisWeekStart - 7,
            viewModel.uiState.value.weeklyReview?.weekStartEpochDay,
        )
        assertEquals(-1, viewModel.uiState.value.weekOffset)
    }

    @Test
    fun futureWeekOffset_isClampedToThisWeek() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.loadWeeklyReview(3)
        advanceUntilIdle()

        assertEquals("未来的周复盘没有意义 → 夹回本周", 0, viewModel.uiState.value.weekOffset)
        assertEquals(thisWeekStart, viewModel.uiState.value.weeklyReview?.weekStartEpochDay)
    }

    @Test
    fun exportPackage_usesCurrentGranularity_andOpensSheet() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel(json = "{\"ok\":true}")
        advanceUntilIdle()

        val includeDetails = slot<Boolean>()
        viewModel.onExportPackage()
        advanceUntilIdle()

        coVerify { exportWeekPackage(any(), capture(includeDetails)) }
        assertTrue("默认粒度 = 明细 + 汇总（定稿 2B）", includeDetails.captured)
        assertEquals("{\"ok\":true}", viewModel.uiState.value.weekPackageJson)
        assertFalse(viewModel.uiState.value.isBuildingPackage)
    }

    @Test
    fun exportPackage_withoutReview_doesNothing() = runTest(mainDispatcherRule.testDispatcher) {
        // 复盘还没算出来（首帧）→ 不许生成数据包，也不许只弹一个空弹层。
        // ⚠️ 覆盖桩必须放在 newViewModel() **之后**（它会注册默认桩，放在前面会被覆盖）。
        val viewModel = newViewModel()
        coEvery { buildWeeklyReview(any()) } throws IllegalStateException("boom")
        viewModel.loadWeeklyReview()
        advanceUntilIdle()

        assertNull("加载失败 → 复盘为空（界面显示「这周没数据」）", viewModel.uiState.value.weeklyReview)
        viewModel.onExportPackage()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.weekPackageJson)
        coVerify(exactly = 0) { exportWeekPackage(any(), any()) }
    }

    @Test
    fun toggleGranularity_regeneratesOnlyWhenSheetIsOpen() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        // 弹层没开 → 只翻状态，不生成
        viewModel.onTogglePackageDetails()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.includePackageDetails)
        coVerify(exactly = 0) { exportWeekPackage(any(), any()) }

        // 打开弹层（生成一次）→ 再切粒度 → 必须立刻按新粒度重算
        viewModel.onExportPackage()
        advanceUntilIdle()
        viewModel.onTogglePackageDetails()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.includePackageDetails)
        coVerify(exactly = 2) { exportWeekPackage(any(), any()) }
        val flags = mutableListOf<Boolean>()
        coVerify { exportWeekPackage(any(), capture(flags)) }
        assertEquals("两次分别按 false / true 生成", listOf(false, true), flags)
    }

    @Test
    fun dismissPackage_clearsContent() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onExportPackage()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.weekPackageJson != null)

        viewModel.onDismissPackage()
        assertNull("关闭后不许留旧内容（下次打开必须是新的）", viewModel.uiState.value.weekPackageJson)
    }

    @Test
    fun copied_showsSnackbar() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onPackageCopied()

        assertEquals(R.string.ai_package_copied, viewModel.uiState.value.snackbarRes)
    }

    @Test
    fun exportFailure_isVisibleAsError() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        coEvery { exportWeekPackage(any(), any()) } throws IllegalStateException("boom")
        advanceUntilIdle()

        viewModel.onExportPackage()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.weekPackageJson)
        assertEquals(
            "序列化失败是「真错误」（不是「没数据」）→ 必须可见",
            R.string.error_save_failed,
            viewModel.uiState.value.errorRes,
        )
    }

    @Test
    fun reviewFailure_keepsUiUsable_withoutErrorCard() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        coEvery { buildWeeklyReview(any()) } throws IllegalStateException("boom")
        viewModel.loadWeeklyReview()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.weeklyReview)
        assertFalse(viewModel.uiState.value.isLoadingReview)
        assertNull("复盘算不出来 = 这周没数据，不该弹吓人的错误卡", viewModel.uiState.value.errorRes)
    }
}
