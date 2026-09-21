package com.ironhabit.app.domain.usecase

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.remote.DeepSeekApi
import com.ironhabit.app.domain.ai.remote.RemoteChatPromptBuilder
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 「问教练」的结果（**可识别**，不抛异常到 UI）。
 *
 * 三态对应 UI 的诚实展示（子项 A）：
 * - [Ok] → 显示 AI 回答（标注来源：AI 联网）；
 * - [NeedsNetwork] → **未联网**（开关关 / 无 Key），UI 保持诚实的禁用说明，**不编造回答**；
 * - [Failed] → 已联网但失败（网络 / HTTP / 空响应），UI 提示可重试。
 *
 * `reason` 复用既有 [RemoteFallbackReason]（避免新增枚举）：
 * [NeedsNetwork] 取 `REMOTE_DISABLED` / `KEY_NOT_CONFIGURED`；[Failed] 取 `REMOTE_ERROR`。
 */
sealed interface CoachAnswer {

    /** 成功：拿到 AI 回答正文（已 trim、非空）。 */
    data class Ok(val text: String) : CoachAnswer

    /** 未联网：本次**不会**发起网络请求。 */
    data class NeedsNetwork(val reason: RemoteFallbackReason) : CoachAnswer

    /** 已联网但失败（网络 / 超时 / HTTP 错误 / 空响应）。 */
    data class Failed(val reason: RemoteFallbackReason) : CoachAnswer
}

/**
 * 本次会话里已经问过 / 答过的一轮 —— 追问要靠它才知道"那饮食呢"接的是什么。
 *
 * ⚠️ 只装**成对**的轮次：一问配一答，且答必须是模型真写出来的正文。
 * 未联网 / 失败那两种气泡不是模型输出，不进来（见 `toCoachTurns`）。
 */
data class CoachTurn(
    val question: String,
    val answer: String,
)

/**
 * AI 自由问答（子项 A）：把用户问题 + 现有上下文 + **本次会话已成对的轮次**交给 DeepSeek，
 * 取回一段中文回答。
 *
 * ## 红线
 * - **诚实**：未联网（开关关 / 无 Key）返回 [CoachAnswer.NeedsNetwork]，**绝不本地编造回答**冒充 AI；
 * - **Key 安全**：Key 只读自 [AiCredentialsStore] 并以参数传给 [DeepSeekApi]（只进 header），
 *   本用例**不打印、不落异常消息、不拼进提示词**；
 * - **隐私**：问答**不落库**（上下文即时聚合、回答只回传内存态，由 UI 保留最近若干轮）。
 *
 * ## 线程
 * 远端是**阻塞 HTTP**（[DeepSeekApi.complete]，30s 超时）→ 整段包 `withContext(ioDispatcher)`。
 *
 * @param question 用户问题；空白 → 直接 [CoachAnswer.Failed]（不发网络）。
 * @param history 本次会话最近几轮**成对**问答；空 = 首轮提问（提示词里就不带 history 字段）。
 */
class AskCoachUseCase @Inject constructor(
    private val buildCoachContext: BuildCoachContextUseCase,
    private val settingsRepository: SettingsRepository,
    private val aiCredentialsStore: AiCredentialsStore,
    private val deepSeekApi: DeepSeekApi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(
        question: String,
        history: List<CoachTurn> = emptyList(),
    ): CoachAnswer = withContext(ioDispatcher) {
        val trimmed: String = question.trim()
        if (trimmed.isEmpty()) {
            return@withContext CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
        }

        try {
            if (!settingsRepository.aiRemoteEnabled().first()) {
                return@withContext CoachAnswer.NeedsNetwork(RemoteFallbackReason.REMOTE_DISABLED)
            }
            val apiKey: String = aiCredentialsStore.apiKey()
                ?: return@withContext CoachAnswer.NeedsNetwork(RemoteFallbackReason.KEY_NOT_CONFIGURED)

            val context = buildCoachContext(DEFAULT_WINDOW_DAYS)
            val raw: String = deepSeekApi.complete(
                systemPrompt = RemoteChatPromptBuilder.buildChatSystemPrompt(),
                userPrompt = RemoteChatPromptBuilder.buildChatUserPrompt(trimmed, context, history),
                apiKey = apiKey,
            )
            RemoteChatPromptBuilder.parseChatAnswer(raw)
                ?.let { text -> CoachAnswer.Ok(text) }
                ?: CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
        } catch (cancellation: CancellationException) {
            // B-8：协程取消必须原样放行 —— 吞掉它会破坏结构化并发（用户离开页面后协程"死不透"）。
            throw cancellation
        } catch (e: Exception) {
            // 网络 / 超时 / HTTP / 解析 / 上下文取数异常，一律转成可识别的失败，不冒泡到 UI。
            CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
        }
    }

    private companion object {
        /** 问答上下文窗口：近 7 天。 */
        const val DEFAULT_WINDOW_DAYS: Int = 7
    }
}
