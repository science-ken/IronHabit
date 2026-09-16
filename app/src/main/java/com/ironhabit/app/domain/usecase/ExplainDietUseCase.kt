package com.ironhabit.app.domain.usecase

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.remote.DeepSeekApi
import com.ironhabit.app.domain.ai.remote.RemoteChatPromptBuilder
import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 「为什么这样吃」的**远端文字分析**（子项 B · 饮食接入 AI 教练页）。
 *
 * ## 分工（**数值永远以本地为准**）
 * - 热量 / 蛋白质 / 各餐内容由本地纯函数 [com.ironhabit.app.domain.diet.DietPlanGenerator] 算出，
 *   本用例**只取一段解释文字**，绝不接受模型给出的数值；
 * - 因此离线（或未配 Key / 调用失败）时，饮食计划本身依然完整可用，
 *   UI 改为展示本地「生成依据」卡（见 `AiCoachScreen`）。
 *
 * ## 结果三态
 * 复用 [CoachAnswer]（与自由问答同构，避免多造一套类型）：
 * - [CoachAnswer.Ok] → 显示 AI 分析卡；
 * - [CoachAnswer.NeedsNetwork] → 未联网（开关关 / 无 Key），UI 显示本地依据卡；
 * - [CoachAnswer.Failed] → 已联网但失败，UI 显示本地依据卡（不打扰、不弹错）。
 *
 * ## 红线
 * - **Key 安全**：只读自 [AiCredentialsStore] 并以参数传给 [DeepSeekApi]（只进 header），
 *   不打印、不落异常消息、不拼进提示词；
 * - **诚实**：未联网时**绝不**本地编造"AI 分析"。
 */
class ExplainDietUseCase @Inject constructor(
    private val buildCoachContext: BuildCoachContextUseCase,
    private val settingsRepository: SettingsRepository,
    private val aiCredentialsStore: AiCredentialsStore,
    private val deepSeekApi: DeepSeekApi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * @param target 本次生成使用的**本地目标**（原样交给模型作为事实，模型不得改写）
     */
    suspend operator fun invoke(target: DietTarget): CoachAnswer = withContext(ioDispatcher) {
        try {
            if (!settingsRepository.aiRemoteEnabled().first()) {
                return@withContext CoachAnswer.NeedsNetwork(RemoteFallbackReason.REMOTE_DISABLED)
            }
            val apiKey: String = aiCredentialsStore.apiKey()
                ?: return@withContext CoachAnswer.NeedsNetwork(RemoteFallbackReason.KEY_NOT_CONFIGURED)

            val context: CoachContext = buildCoachContext(DEFAULT_WINDOW_DAYS)
            val raw: String = deepSeekApi.complete(
                systemPrompt = RemoteChatPromptBuilder.buildDietSystemPrompt(),
                userPrompt = RemoteChatPromptBuilder.buildDietUserPrompt(context, target),
                apiKey = apiKey,
            )
            RemoteChatPromptBuilder.parseChatAnswer(raw)
                ?.let { text -> CoachAnswer.Ok(text) }
                ?: CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Exception) {
            // 网络 / 超时 / HTTP / 解析 / 取数异常一律转成可识别失败，不冒泡到 UI。
            CoachAnswer.Failed(RemoteFallbackReason.REMOTE_ERROR)
        }
    }

    private companion object {
        /** 分析用的上下文窗口：近 7 天（与自由问答一致）。 */
        const val DEFAULT_WINDOW_DAYS: Int = 7
    }
}
