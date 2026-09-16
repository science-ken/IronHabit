package com.ironhabit.app.domain.usecase

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.remote.DeepSeekApi
import com.ironhabit.app.domain.ai.remote.RemoteChatPromptBuilder
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 「进度解读」的结果（子项 C）。
 *
 * **关键不变量：数字永远来自本地纯聚合**（[CoachContext]），远端只提供一段文字。
 * 因此离线 / 未配 Key / 调用失败时，卡片依然有完整可信的结构化小结，
 * 只是 [source] 为 [AdviceSource.LOCAL_RULES] 并由 UI **明确标注"本地规则"**（不冒充 AI）。
 *
 * @property source 本次实际来源：`REMOTE_LLM` = AI 文案可用；`LOCAL_RULES` = 只显示本地小结
 * @property text AI 写的「最近进展 + 下一步建议」（仅 `REMOTE_LLM` 有值）
 * @property context 本地聚合出的数字（打卡次数 / 平均 RPE / 体重变化 / 连续天数 / 今日饮食）
 * @property fallbackReason 走本地时的回落原因（`null` = 未发生回落，例如本来就没开联网）
 */
data class CoachInsightResult(
    val source: AdviceSource = AdviceSource.LOCAL_RULES,
    val text: String? = null,
    val context: CoachContext = CoachContext(),
    val fallbackReason: RemoteFallbackReason? = null,
)

/**
 * 「进度解读」用例（子项 C）：聚合最近 N 天的训练/体重/饮食，交给 AI 写一段「最近进展 + 下一步建议」；
 * 离线则退回**结构化本地小结**（由 UI 用 [CoachInsightResult.context] 渲染数字）。
 *
 * ## 分工与红线
 * - 数字**只来自** [BuildCoachContextUseCase]（纯聚合）；提示词里明确要求模型不得改数字；
 * - 未联网（开关关 / 无 Key）时**一次网络都不发**，直接返回本地结果；
 * - 调用失败也返回本地结果（附带 [CoachInsightResult.fallbackReason]），**不冒泡到 UI、不弹错**；
 * - Key 只进 Authorization header，不打印、不拼进提示词。
 *
 * @param windowDays 统计窗口（默认近 14 天；`< 1` 会被 [BuildCoachContextUseCase] 钳到 1）
 */
class CoachInsightUseCase @Inject constructor(
    private val buildCoachContext: BuildCoachContextUseCase,
    private val settingsRepository: SettingsRepository,
    private val aiCredentialsStore: AiCredentialsStore,
    private val deepSeekApi: DeepSeekApi,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend operator fun invoke(windowDays: Int = WINDOW_DAYS): CoachInsightResult =
        withContext(ioDispatcher) {
            // 本地数字先算出来：无论联网与否都要用（离线小结 / 提示词事实）。
            val context: CoachContext = buildCoachContext(windowDays)

            if (!settingsRepository.aiRemoteEnabled().first()) {
                return@withContext CoachInsightResult(
                    source = AdviceSource.LOCAL_RULES,
                    context = context,
                    fallbackReason = RemoteFallbackReason.REMOTE_DISABLED,
                )
            }
            val apiKey: String = aiCredentialsStore.apiKey()
                ?: return@withContext CoachInsightResult(
                    source = AdviceSource.LOCAL_RULES,
                    context = context,
                    fallbackReason = RemoteFallbackReason.KEY_NOT_CONFIGURED,
                )

            try {
                val raw: String = deepSeekApi.complete(
                    systemPrompt = RemoteChatPromptBuilder.buildInsightSystemPrompt(),
                    userPrompt = RemoteChatPromptBuilder.buildInsightUserPrompt(context),
                    apiKey = apiKey,
                )
                val text: String? = RemoteChatPromptBuilder.parseChatAnswer(raw)
                if (text == null) {
                    CoachInsightResult(
                        source = AdviceSource.LOCAL_RULES,
                        context = context,
                        fallbackReason = RemoteFallbackReason.REMOTE_ERROR,
                    )
                } else {
                    CoachInsightResult(
                        source = AdviceSource.REMOTE_LLM,
                        text = text,
                        context = context,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Exception) {
                // 网络 / 超时 / HTTP / 解析异常 → 一律退回本地小结，不留白、不打扰用户。
                CoachInsightResult(
                    source = AdviceSource.LOCAL_RULES,
                    context = context,
                    fallbackReason = RemoteFallbackReason.REMOTE_ERROR,
                )
            }
        }

    companion object {
        /** 默认统计窗口：近 14 天（进度解读看趋势，比问答的 7 天更长）。 */
        const val WINDOW_DAYS: Int = 14
    }
}
