package com.ironhabit.app.domain.usecase

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.ai.remote.DeepSeekApi
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [CoachInsightUseCase] 单测（子项 C · 进度解读卡）。
 *
 * 核心不变量：**数字永远来自本地聚合** —— 无论联网成功、失败还是未联网，
 * [CoachInsightResult.context] 都必须带上本地数字；远端只可能多给一段文字，
 * 且 `source` 必须如实反映"这段文字到底是不是 AI 写的"。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachInsightUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val buildCoachContext = mockk<BuildCoachContextUseCase>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val credentials = mockk<AiCredentialsStore>(relaxed = true)
    private val api = mockk<DeepSeekApi>()

    /** 一份确定的本地数字：两条断言都用它，确保"远端失败也不影响数字"。 */
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
        settingsRepository = settingsRepository,
        aiCredentialsStore = credentials,
        deepSeekApi = api,
        ioDispatcher = mainDispatcherRule.testDispatcher,
    )

    private fun stubOnline(remoteEnabled: Boolean = true, key: String? = "sk-test") {
        every { settingsRepository.aiRemoteEnabled() } returns flowOf(remoteEnabled)
        every { credentials.apiKey() } returns key
        coEvery { buildCoachContext(any()) } returns localContext
    }

    @Test
    fun remoteDisabled_returnsLocalSummary_withNumbers_andNeverCallsApi() =
        runTest(mainDispatcherRule.testDispatcher) {
            stubOnline(remoteEnabled = false)

            val result = useCase()()

            assertEquals(AdviceSource.LOCAL_RULES, result.source)
            assertNull("未联网不得有 AI 文案", result.text)
            assertEquals(RemoteFallbackReason.REMOTE_DISABLED, result.fallbackReason)
            assertEquals("本地数字必须完整保留", 6, result.context.checkInCount)
            assertEquals(3, result.context.currentStreak)
            coVerify(exactly = 0) { api.complete(any(), any(), any()) }
        }

    @Test
    fun noApiKey_returnsLocalSummary_withKeyReason() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline(key = null)

        val result = useCase()()

        assertEquals(AdviceSource.LOCAL_RULES, result.source)
        assertEquals(RemoteFallbackReason.KEY_NOT_CONFIGURED, result.fallbackReason)
        assertEquals(6, result.context.checkInCount)
        coVerify(exactly = 0) { api.complete(any(), any(), any()) }
    }

    @Test
    fun success_returnsRemoteText_andKeepsLocalNumbers() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        val userPrompt = slot<String>()
        coEvery {
            api.complete(any(), capture(userPrompt), any())
        } returns """{"answer":"近两周出勤 6 次、平均 RPE 7，可以再加重 2.5kg。"}"""

        val result = useCase()()

        assertEquals(AdviceSource.REMOTE_LLM, result.source)
        assertEquals("近两周出勤 6 次、平均 RPE 7，可以再加重 2.5kg。", result.text)
        assertNull("成功了就没有回落原因", result.fallbackReason)
        assertEquals(6, result.context.checkInCount)
        assertTrue("本地数字必须原样进提示词", userPrompt.captured.contains("\"count\":6"))
        assertTrue(userPrompt.captured.contains("\"currentStreak\":3"))
    }

    @Test
    fun apiThrows_fallsBackToLocalSummary_andKeepsNumbers() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        coEvery { api.complete(any(), any(), any()) } throws IOException("DeepSeek HTTP 503")

        val result = useCase()()

        assertEquals(AdviceSource.LOCAL_RULES, result.source)
        assertNull(result.text)
        assertEquals(RemoteFallbackReason.REMOTE_ERROR, result.fallbackReason)
        assertEquals("远端失败不影响本地数字", -0.6f, result.context.weightDeltaKg)
    }

    @Test
    fun invalidResponse_fallsBackToLocalSummary() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        coEvery { api.complete(any(), any(), any()) } returns """{"foo":1}"""

        val result = useCase()()

        assertEquals(AdviceSource.LOCAL_RULES, result.source)
        assertEquals(RemoteFallbackReason.REMOTE_ERROR, result.fallbackReason)
    }

    @Test
    fun windowDays_isForwardedToContextBuilder() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline(remoteEnabled = false)

        useCase()()
        coVerify(exactly = 1) { buildCoachContext(CoachInsightUseCase.WINDOW_DAYS) }

        useCase()(7)
        coVerify(exactly = 1) { buildCoachContext(7) }
    }
}
