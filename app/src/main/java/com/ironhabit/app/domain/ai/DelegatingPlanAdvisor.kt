package com.ironhabit.app.domain.ai

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.RemoteFallbackReason
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate

/**
 * 按配置**委托切换**的顾问：远端（DeepSeek）优先，任何不可用 → 自动回落本地规则。
 *
 * ## 路由规则（联网一期 §6.2 N4，每条都有单测）
 * 1. 开关关（`ai_remote_enabled = false`，**默认**）→ 直接走 [local]，不碰网络；
 * 2. 开关开但 Key 未配置 → 走 [local]，回落原因 [RemoteFallbackReason.KEY_NOT_CONFIGURED]；
 * 3. 开关开且 Key 在 → 走 [remote]；**任何异常**（网络 / 超时 / HTTP 非 200 / 解析失败）
 *    → 走 [local]，回落原因 [RemoteFallbackReason.REMOTE_ERROR]。
 *
 * ## 来源标注（诚实原则）
 * - [source] 返回**最近一次调用实际使用的来源**（本地规则 / AI 联网生成）；
 * - [lastFallbackReason] 非空表示本次虽是本地结果，但原因不是"用户选了本地"，
 *   UseCase 会把它透传给 UI 显示"本次来自本地规则（联网失败）"。
 *
 * ## 线程说明（重要）
 * `PlanAdvisor.planWeek / suggestExercises` 是**非 suspend 纯函数形状**（本地实现零 IO），
 * 而读开关（DataStore）需要挂起。本类在读取开关时用 `runBlocking`——**仅因此类只在
 * UseCase 的 `withContext(Dispatchers.IO)` 内被调用**（HTTP 也是阻塞调用，同一约束），
 * 不会阻塞主线程；切回主线程调用属于违反 UseCase 约定，不该发生。
 *
 * @param local 本地规则实现（[LocalRuleAdvisor]，永远可用）
 * @param remote 远端实现（[com.ironhabit.app.domain.ai.remote.RemoteLlmAdvisor]）
 * @param credentials API Key 加密存储
 * @param settingsRepository 读「是否启用远端」开关
 */
class DelegatingPlanAdvisor(
    private val local: PlanAdvisor,
    private val remote: PlanAdvisor,
    private val credentials: AiCredentialsStore,
    private val settingsRepository: SettingsRepository,
) : PlanAdvisor {

    /** 最近一次调用实际使用的来源（`@Volatile`：UI / UseCase 可能在别的线程读）。 */
    @Volatile
    private var lastUsedSource: AdviceSource = AdviceSource.LOCAL_RULES

    /** 最近一次调用的回落原因（见接口默认实现的约定）。 */
    @Volatile
    override var lastFallbackReason: RemoteFallbackReason? = null
        private set

    override val source: AdviceSource
        get() = lastUsedSource

    override fun planWeek(
        profile: UserProfile,
        library: List<Exercise>,
        existing: List<WeekPlan>,
        history: List<ExerciseProgress>,
        today: LocalDate,
        bodyWeightKg: Float?,
    ): PlanProposal = route(
        remoteCall = { remote.planWeek(profile, library, existing, history, today, bodyWeightKg) },
        localCall = { local.planWeek(profile, library, existing, history, today, bodyWeightKg) },
    )

    override fun suggestExercises(
        profile: UserProfile,
        candidates: List<Exercise>,
        existing: List<Exercise>,
    ): List<ExerciseSuggestion> = route(
        remoteCall = { remote.suggestExercises(profile, candidates, existing) },
        localCall = { local.suggestExercises(profile, candidates, existing) },
    )

    /** 统一路由：判定 → 远端优先 → 异常回落本地并记录原因。 */
    private fun <T> route(remoteCall: () -> T, localCall: () -> T): T {
        val remoteEnabled: Boolean = readRemoteEnabled()

        if (!remoteEnabled) {
            record(source = AdviceSource.LOCAL_RULES, reason = RemoteFallbackReason.REMOTE_DISABLED)
            return localCall()
        }

        if (!credentials.isConfigured()) {
            record(source = AdviceSource.LOCAL_RULES, reason = RemoteFallbackReason.KEY_NOT_CONFIGURED)
            return localCall()
        }

        return try {
            val result: T = remoteCall()
            if (isUsableRemoteResult(result)) {
                record(source = AdviceSource.REMOTE_LLM, reason = null)
                result
            } else {
                // B-3：远端"成功返回"了空/幻觉结果（如臆造的动作 id 被库校验全部丢弃 → 一条不剩）
                // ≠ 有效建议。按失败回落本地，绝不把空结果交给上层 —— 否则生成链路会把
                // 「整周计划」当成"本次没排任何条目"而回收掉用户全部现有 AI 行。
                record(source = AdviceSource.LOCAL_RULES, reason = RemoteFallbackReason.REMOTE_ERROR)
                localCall()
            }
        } catch (cancellation: CancellationException) {
            // B-8：协程取消必须原样放行 —— 吞掉它会破坏结构化并发（调用方以为还在跑）。
            throw cancellation
        } catch (t: Throwable) {
            // 领域层零 Android 依赖（不打 android.util.Log，JVM 单测也不被它拖累）；
            // 失败细节统一由 RemoteAdvisorException / IOException 的 message 承载，
            // 且回落原因经 lastFallbackReason 透传给 UI（诚实提示，不静默吞掉）。
            record(source = AdviceSource.LOCAL_RULES, reason = RemoteFallbackReason.REMOTE_ERROR)
            localCall()
        }
    }

    /**
     * 远端结果是否**可用**（B-3）：空结果按失败处理，回落本地。
     *
     * - [PlanProposal]：至少要有一条「天 × 动作」草案（`days` 全空 / 每天条目全空 = 不可用）；
     * - 动作建议列表：非空才算可用；
     * - 其他类型（未来扩展）：视为可用。
     */
    private fun <T> isUsableRemoteResult(result: T): Boolean = when (result) {
        is PlanProposal -> result.days.any { day -> day.items.isNotEmpty() }
        is List<*> -> result.isNotEmpty()
        else -> true
    }

    /** 读「是否启用远端」开关（见类注释的线程说明）。 */
    private fun readRemoteEnabled(): Boolean = runBlocking {
        settingsRepository.aiRemoteEnabled().first()
    }

    /** 记录本次调用的来源与回落原因。 */
    private fun record(source: AdviceSource, reason: RemoteFallbackReason?) {
        lastUsedSource = source
        lastFallbackReason = reason
    }
}
