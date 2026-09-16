package com.ironhabit.app.domain.usecase

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.ai.remote.DeepSeekApi
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
 * [AskCoachUseCase] 单测（子项 A · AI 自由问答）。
 *
 * 三态都必须**可识别、不抛异常到 UI**，且离线时**一次网络都不发**：
 * - 开关关 → [CoachAnswer.NeedsNetwork]（`REMOTE_DISABLED`）
 * - 无 Key → [CoachAnswer.NeedsNetwork]（`KEY_NOT_CONFIGURED`）
 * - 成功 → [CoachAnswer.Ok]
 * - 网络 / 空响应 / 上下文异常 → [CoachAnswer.Failed]（`REMOTE_ERROR`）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskCoachUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val buildCoachContext = mockk<BuildCoachContextUseCase>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val credentials = mockk<AiCredentialsStore>(relaxed = true)
    private val api = mockk<DeepSeekApi>()

    private fun useCase(): AskCoachUseCase = AskCoachUseCase(
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

        val answer = useCase()("今天该练什么")

        assertEquals(CoachAnswer.NeedsNetwork(RemoteFallbackReason.REMOTE_DISABLED), answer)
        coVerify(exactly = 0) { api.complete(any(), any(), any()) }
    }

    @Test
    fun noApiKey_returnsNeedsNetwork_andNeverCallsApi() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline(key = null)

        val answer = useCase()("今天该练什么")

        assertEquals(CoachAnswer.NeedsNetwork(RemoteFallbackReason.KEY_NOT_CONFIGURED), answer)
        coVerify(exactly = 0) { api.complete(any(), any(), any()) }
    }

    @Test
    fun success_returnsOk_andPassesQuestionIntoUserPrompt() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        val userPrompt = slot<String>()
        coEvery {
            api.complete(any(), capture(userPrompt), any())
        } returns """{"answer":"今天练腿，4 组 × 8 次"}"""

        val answer = useCase()("今天该练什么")

        assertEquals(CoachAnswer.Ok("今天练腿，4 组 × 8 次"), answer)
        assertTrue("用户问题必须进 user 提示词", userPrompt.captured.contains("今天该练什么"))
    }

    @Test
    fun apiThrows_returnsFailed_insteadOfPropagating() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        coEvery { api.complete(any(), any(), any()) } throws IOException("DeepSeek HTTP 503")

        val answer = useCase()("今天该练什么")

        assertEquals(CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR), answer)
    }

    @Test
    fun emptyApiResponse_returnsFailed() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()
        coEvery { api.complete(any(), any(), any()) } returns "   "

        val answer = useCase()("今天该练什么")

        assertEquals(CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR), answer)
    }

    @Test
    fun blankQuestion_returnsFailed_andNeverCallsApi() = runTest(mainDispatcherRule.testDispatcher) {
        stubOnline()

        val answer = useCase()("   ")

        assertEquals(CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR), answer)
        coVerify(exactly = 0) { api.complete(any(), any(), any()) }
    }

    @Test
    fun contextFailure_returnsFailed_andNeverCallsApi() = runTest(mainDispatcherRule.testDispatcher) {
        every { settingsRepository.aiRemoteEnabled() } returns flowOf(true)
        every { credentials.apiKey() } returns "sk-test"
        coEvery { buildCoachContext(any()) } throws IllegalStateException("db closed")

        val answer = useCase()("今天该练什么")

        assertEquals(CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR), answer)
        coVerify(exactly = 0) { api.complete(any(), any(), any()) }
    }
}
