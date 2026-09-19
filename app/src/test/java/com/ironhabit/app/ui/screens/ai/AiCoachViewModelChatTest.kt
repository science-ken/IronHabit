package com.ironhabit.app.ui.screens.ai

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.SuggestionResult
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.AskCoachUseCase
import com.ironhabit.app.domain.usecase.CoachAnswer
import com.ironhabit.app.domain.usecase.CoachInsightResult
import com.ironhabit.app.domain.usecase.BuildWeeklyReviewUseCase
import com.ironhabit.app.domain.usecase.CoachInsightUseCase
import com.ironhabit.app.domain.usecase.ExportWeekPackageUseCase
import com.ironhabit.app.domain.usecase.ExplainDietUseCase
import com.ironhabit.app.domain.usecase.GenerateDietPlanUseCase
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [AiCoachViewModel] 的「问教练」单测（子项 A）。
 *
 * 只覆盖问答相关的状态机：三态气泡映射、输入清空、发送中防重入、内存条数上限、
 * 以及 [AiCoachUiState.canAskCoach] 的派生条件（**开关 + Key 缺一不可**）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiCoachViewModelChatTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val settingsRepository = mockk<SettingsRepository>()
    private val bodyMetricRepository = mockk<BodyMetricRepository>()
    private val exerciseRepository = mockk<ExerciseRepository>()
    private val credentials = mockk<AiCredentialsStore>(relaxed = true)
    private val generateTrainingPlan = mockk<GenerateTrainingPlanUseCase>(relaxed = true)
    private val suggestExercises = mockk<SuggestExercisesUseCase>()
    private val askCoach = mockk<AskCoachUseCase>()
    private val generateDietPlan = mockk<GenerateDietPlanUseCase>(relaxed = true)
    private val explainDiet = mockk<ExplainDietUseCase>(relaxed = true)
    private val buildWeeklyReview = mockk<BuildWeeklyReviewUseCase>(relaxed = true)
    private val exportWeekPackage = mockk<ExportWeekPackageUseCase>(relaxed = true)
    private val coachInsight = mockk<CoachInsightUseCase>(relaxed = true)

    /** 固定时钟：让 `todayEpochDay()` 可复现（本类只关心问答，故取任意确定时刻）。 */
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
        // init 里会自动跑一次进度解读（子项 C），这里显式桩掉，避免依赖 relaxed 的默认值。
        coEvery { coachInsight(any()) } returns CoachInsightResult()
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
            timeZone = utc,
        )
    }

    @Test
    fun canAskCoach_requiresBothRemoteSwitchAndApiKey() = runTest(mainDispatcherRule.testDispatcher) {
        // 初始流（档案 / 开关 / Key 快照）由 init 里的 collect 异步写入 uiState，
        // 因此必须先推进调度器再断言，否则读到的是初始值。
        val online = newViewModel(aiRemote = true, hasKey = true)
        advanceUntilIdle()
        assertTrue("开关开 + 有 Key → 可用", online.uiState.value.canAskCoach)

        val switchOff = newViewModel(aiRemote = false, hasKey = true)
        advanceUntilIdle()
        assertFalse("开关关 → 不可用", switchOff.uiState.value.canAskCoach)

        val noKey = newViewModel(aiRemote = true, hasKey = false)
        advanceUntilIdle()
        assertFalse("无 Key → 不可用", noKey.uiState.value.canAskCoach)
    }

    @Test
    fun ask_ok_appendsUserThenAnswer_andClearsInput() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { askCoach("今天要不要练腿") } returns CoachAnswer.Ok("今天练腿，4 组 × 8 次")

        viewModel.onChatInputChange("今天要不要练腿")
        viewModel.onAskCoach()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.chatMessages.size)
        assertEquals(CoachChatKind.USER, state.chatMessages[0].kind)
        assertEquals("今天要不要练腿", state.chatMessages[0].text)
        assertEquals(CoachChatKind.ANSWER, state.chatMessages[1].kind)
        assertEquals("今天练腿，4 组 × 8 次", state.chatMessages[1].text)
        assertEquals("发送后输入框应清空", "", state.chatInput)
        assertFalse("发送结束应恢复可用", state.isAsking)
    }

    @Test
    fun ask_needsNetwork_appendsHonestBubble_withoutFakingAnswer() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { askCoach(any()) } returns CoachAnswer.NeedsNetwork(RemoteFallbackReason.REMOTE_DISABLED)

        viewModel.onChatInputChange("帮我排个计划")
        viewModel.onAskCoach()
        advanceUntilIdle()

        val messages = viewModel.uiState.value.chatMessages
        assertEquals(2, messages.size)
        assertEquals(CoachChatKind.NEEDS_NETWORK, messages[1].kind)
        assertEquals("诚实态不带正文", "", messages[1].text)
    }

    @Test
    fun ask_failed_appendsFailedBubble() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { askCoach(any()) } returns CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)

        viewModel.onChatInputChange("晚饭吃什么")
        viewModel.onAskCoach()
        advanceUntilIdle()

        val messages = viewModel.uiState.value.chatMessages
        assertEquals(2, messages.size)
        assertEquals(CoachChatKind.FAILED, messages[1].kind)
    }

    @Test
    fun blankInput_neverCallsUseCase() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.onChatInputChange("   ")
        viewModel.onAskCoach()
        advanceUntilIdle()

        assertTrue("空白问题不应产生气泡", viewModel.uiState.value.chatMessages.isEmpty())
        coVerify(exactly = 0) { askCoach(any()) }
    }

    @Test
    fun messages_areTrimmedToLastSix() = runTest(mainDispatcherRule.testDispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        coEvery { askCoach(any()) } returns CoachAnswer.Ok("好的")

        repeat(4) { round ->
            viewModel.onChatInputChange("问题${round + 1}")
            viewModel.onAskCoach()
            advanceUntilIdle()
        }

        val messages = viewModel.uiState.value.chatMessages
        assertEquals("只保留最近 6 条（3 轮）", 6, messages.size)
        assertEquals("最早的一轮已被挤掉", "问题2", messages.first().text)
        assertEquals("最后一条是第 4 轮的回答", "好的", messages.last().text)
    }
}
