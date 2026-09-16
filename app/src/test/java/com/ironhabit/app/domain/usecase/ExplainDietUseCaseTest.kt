package com.ironhabit.app.domain.usecase

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.ai.remote.DeepSeekApi
import com.ironhabit.app.domain.model.DietTarget
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * [ExplainDietUseCase] 单测（子项 B · 饮食接入 AI 教练页）。
 *
 * 核心不变量：**数值以本地为准** —— 目标热量 / 蛋白质由调用方（本地纯函数）传入并原样进提示词，
 * 远端只回一段文字；离线 / 无 Key / 失败时返回可识别结果，UI 退回本地依据卡，绝不冒充 AI。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExplainDietUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val buildCoachContext = mockk<BuildCoachContextUseCase>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val credentials = mockk<AiCredentialsStore>(relaxed = true)
    private val api = mockk<DeepSeekApi>()

    private val target = DietTarget(targetKcal = 2300, targetProtein = 150)

    private fun useCase(): ExplainDietUseCase = ExplainDietUseCase(
        buildCoachContext = buildCoachContext,
        settingsRepository = settingsRepository,
        aiCredentialsStore = credentials,
        deepSeekApi = api,
        ioDispatcher = mainDispatcherRule.testDispatcher,
    )

    private fun stubOnline(remoteEnabled: Boolean = true, key: String? = "sk-test") {
        every { settingsRepository.aiRemoteEnabled() } returns flowOf(remoteEnabled)
        every { credentials.apiKey() } returns key
        coEvery { buildCoachContext(any()) } returns CoachContext()
    }

    @Test
    fun remoteDisabled_returnsNeedsNetwork_andNeverCallsApi() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline(remoteEnabled = false)

        val answer = useCase()(target)

        assertEquals(CoachAnswer.NeedsNetwork(RemoteFallbackReason.REMOTE_DISABLED), answer)
        coVerify(exactly = 0) { api.complete(any(), any(), any()) }
    }

    @Test
    fun noApiKey_returnsNeedsNetwork_andNeverCallsApi() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline(key = null)

        val answer = useCase()(target)

        assertEquals(CoachAnswer.NeedsNetwork(RemoteFallbackReason.KEY_NOT_CONFIGURED), answer)
        coVerify(exactly = 0) { api.complete(any(), any(), any()) }
    }

    @Test
    fun success_returnsOk_andSendsLocalTargetIntoPrompt() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        val userPrompt = slot<String>()
        coEvery {
            api.complete(any(), capture(userPrompt), any())
        } returns """{"answer":"今天目标是 2300 kcal，训练日多吃一点主食。"}"""

        val answer = useCase()(target)

        assertEquals(CoachAnswer.Ok("今天目标是 2300 kcal，训练日多吃一点主食。"), answer)
        assertTrue("本地目标热量必须原样进提示词", userPrompt.captured.contains("\"targetKcal\":2300"))
        assertTrue("本地目标蛋白质必须原样进提示词", userPrompt.captured.contains("\"targetProtein\":150"))
    }

    @Test
    fun apiThrows_returnsFailed_insteadOfPropagating() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        coEvery { api.complete(any(), any(), any()) } throws IOException("DeepSeek HTTP 503")

        val answer = useCase()(target)

        assertEquals(CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR), answer)
    }

    @Test
    fun emptyApiResponse_returnsFailed() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        coEvery { api.complete(any(), any(), any()) } returns """{"foo":1}"""

        val answer = useCase()(target)

        // 合法 JSON 但没有 answer 字段 → 视为无效，绝不把 JSON 原文当分析展示。
        assertEquals(CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR), answer)
    }

    @Test
    fun contextFailure_returnsFailed_andNeverCallsApi() = runTest(mainDispatcherRule.testDispatcher) {
        every { settingsRepository.aiRemoteEnabled() } returns flowOf(true)
        every { credentials.apiKey() } returns "sk-test"
        coEvery { buildCoachContext(any()) } throws IllegalStateException("db closed")

        val answer = useCase()(target)

        assertEquals(CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR), answer)
        coVerify(exactly = 0) { api.complete(any(), any(), any()) }
    }
}
