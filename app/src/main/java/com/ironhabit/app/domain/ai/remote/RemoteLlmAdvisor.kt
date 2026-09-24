package com.ironhabit.app.domain.ai.remote

import com.ironhabit.app.data.preferences.AiCredentialsStore
import com.ironhabit.app.domain.ai.PlanAdvisor
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.PlanItemDraft
import com.ironhabit.app.domain.model.PlanNote
import com.ironhabit.app.domain.model.PlanNoteDetail
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.PlannedDay
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.SuggestionReason
import com.ironhabit.app.domain.model.TrainingFocus
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import java.io.IOException
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * 远端（DeepSeek）调用 / 解析失败的**可识别异常**。
 *
 * 委托层（[com.ironhabit.app.domain.ai.DelegatingPlanAdvisor]）捕获它后回落本地规则；
 * 消息只含技术细节（HTTP 状态 / 字段名），**绝不含 API Key**。
 */
class RemoteAdvisorException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 「一周计划」响应的独立**纯函数解析**（联网一期派工单要求：可被 JVM 单测直接覆盖，无需网络）。
 *
 * ## 三道防线（每条都有单测）
 * 1. `exerciseId` 不在 [library] → **丢弃该条**（不崩、不写入幻觉 id）；
 * 2. JSON 缺字段 / 畸形 / 不是合法 JSON → 抛 [RemoteAdvisorException]，由委托层回落本地；
 * 3. `targetSets` / `targetReps` ≤ 0 或离谱 → **coerce** 到 `1..31` / `1..100`（31 = 打卡位图宽度）；
 *    `targetWeightKg` ≤ 0 → 视为自重（`null`）。
 *
 * 额外兜底（与本地规则层同口径）：`dayOfWeek` clamp 到 `1..7`；
 * 用户手改槽位（`existing.isUserEdited`，含软删除行）**绝不生成条目**；
 * 同一天内同动作去重；整天无有效条目则整天丢弃。
 *
 * @param json 模型返回的文本（容忍被 ```json 围栏包裹）
 * @param library 动作库全集（id 白名单）
 * @param existing 当前周计划全部行（用于排除手改槽位）
 */
fun parseProposalJson(
    json: String,
    library: List<Exercise>,
    existing: List<WeekPlan>,
): PlanProposal {
    val remote: RemoteProposal = decodeStrict<RemoteProposal>(json)

    val libraryIds: Set<Long> = library.map { it.id }.toSet()
    val libraryById: Map<Long, Exercise> = library.associateBy { it.id }
    val blockedSlots: Set<Pair<Int, Long>> = existing
        .filter { it.isUserEdited }
        .map { it.dayOfWeek to it.exerciseId }
        .toSet()
    val preservedIds: List<Long> = existing
        .filter { it.isUserEdited }
        .map { it.id }

    val days: List<PlannedDay> = remote.days
        .map { day -> day.dayOfWeek.coerceIn(MIN_DAY_OF_WEEK, MAX_DAY_OF_WEEK) to day }
        .mapNotNull { (dayOfWeek, day) ->
            val focus: TrainingFocus = TrainingFocus.entries
                .firstOrNull { it.name == day.focus?.trim()?.uppercase() }
                ?: TrainingFocus.FULL_BODY   // 未知 focus → 全身兜底，不崩

            val items: List<PlanItemDraft> = day.items
                .filter { item -> item.exerciseId in libraryIds }   // 防线 ①：幻觉 id 丢弃
                .filter { item -> (dayOfWeek to item.exerciseId) !in blockedSlots }  // 手改槽位不碰
                .distinctBy { it.exerciseId }                        // 同日同动作去重
                .mapIndexed { index, item ->
                    PlanItemDraft(
                        exerciseId = item.exerciseId,
                        targetSets = item.targetSets.coerceIn(MIN_SETS, MAX_SETS),   // 防线 ③
                        targetReps = item.targetReps.coerceIn(MIN_REPS, MAX_REPS),   // 防线 ③
                        targetWeightKg = item.targetWeightKg?.takeIf { it > 0f },
                        // 修复 C3：有氧时长不由模型编造，而是按 exerciseId **回本地动作库**取默认时长换算成分。
                        targetDurationMin = libraryById[item.exerciseId]
                            ?.defaultDurationSec
                            ?.let { sec -> sec / SECONDS_PER_MINUTE }
                            ?.takeIf { minutes -> minutes >= MIN_DURATION_MIN },
                        reason = if (index == 0) PlanReason.PRIMARY_LIFT else PlanReason.SUPPLEMENT,
                    )
                }
            if (items.isEmpty()) null else PlannedDay(dayOfWeek = dayOfWeek, focus = focus, items = items)
        }

    return PlanProposal(
        days = days,
        preservedUserEditedIds = preservedIds,
        notes = days.flatMap { day ->
            day.items.map { item ->
                PlanNote(kind = item.reason, exerciseId = item.exerciseId, detail = PlanNoteDetail.None)
            }
        },
        analysis = remote.analysis,
        basis = emptyList(),
        source = AdviceSource.REMOTE_LLM,
    )
}

/**
 * 「补充动作」响应的独立**纯函数解析**。
 *
 * 防线：`name` **不在候选池** → 丢弃（逐字比对，防幻觉动作名）；
 * 已在用户动作库（`existing`，含停用动作）→ 丢弃（幂等键 = name）。
 * 候选池数据（分类 / 肌群 / 默认组次）**以本地候选为准**，不信模型编的描述。
 */
fun parseSuggestionsJson(
    json: String,
    candidates: List<Exercise>,
    existing: List<Exercise>,
): List<ExerciseSuggestion> {
    val remote: RemoteSuggestions = decodeStrict(json)

    val existingNames: Set<String> = existing.map { it.name.trim() }.toSet()
    val candidateByName: Map<String, Exercise> = candidates.associateBy { it.name.trim() }

    return remote.suggestions
        .map { it.name.trim() }
        .filter { it.isNotEmpty() }
        .filter { name -> name in candidateByName }            // 不在候选池 → 丢弃
        .filter { name -> name !in existingNames }             // 已在库 → 丢弃（幂等）
        .distinct()
        .map { name ->
            val candidate: Exercise = candidateByName.getValue(name)
            ExerciseSuggestion(
                name = candidate.name.trim(),
                category = candidate.category,
                muscleGroups = candidate.muscleGroups,
                defaultSets = (candidate.defaultSets ?: DEFAULT_SETS).coerceIn(MIN_SETS, MAX_SETS),
                defaultReps = (candidate.defaultReps ?: DEFAULT_REPS).coerceIn(MIN_REPS, MAX_REPS),
                noteKey = noteKeyFor(reasonFor(remote.suggestions.firstOrNull { it.name.trim() == name })),
                reason = reasonFor(remote.suggestions.firstOrNull { it.name.trim() == name }),
            )
        }
}

/** 独立解析：去围栏 → 宽松反序列化；任何畸形输入都升级为 [RemoteAdvisorException]。 */
private inline fun <reified T> decodeStrict(raw: String): T {
    val cleaned: String = stripCodeFence(raw)
    return try {
        lenientJson.decodeFromString<T>(cleaned)
    } catch (e: SerializationException) {
        throw RemoteAdvisorException("远端响应 JSON 无法解析（缺字段 / 畸形）", e)
    } catch (e: IllegalArgumentException) {
        throw RemoteAdvisorException("远端响应不是合法 JSON", e)
    }
}

/**
 * 容忍模型违规包裹 ```json 围栏（system 段已禁止，但双保险）。
 *
 * `internal` 而非 `private`：外部 AI 文档导入那条路粘回来的文本同样常见围栏
 * （网页里的模型基本都会包一层），这条容忍逻辑不该写两份。
 */
internal fun stripCodeFence(raw: String): String {
    var text: String = raw.trim()
    if (text.startsWith("```")) {
        text = text
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
        val closing: Int = text.lastIndexOf("```")
        if (closing >= 0) text = text.substring(0, closing)
    }
    return text.trim()
}

/**
 * 远端顾问（`source = [AdviceSource.REMOTE_LLM]`，联网一期）。
 *
 * - **只做三件事**：组装提示词 → 调 [DeepSeekApi] → 解析 JSON（解析为独立纯函数，见上）；
 * - 任何失败都以 [RemoteAdvisorException] 抛出，**回落决策不在这里**（归委托层）；
 * - 阻塞 IO：调用方（UseCase）已包 `withContext(Dispatchers.IO)`。
 */
class RemoteLlmAdvisor(
    private val api: DeepSeekApi,
    private val credentials: AiCredentialsStore,
) : PlanAdvisor {

    override val source: AdviceSource = AdviceSource.REMOTE_LLM

    /**
     * 远端生成一周计划。
     *
     * ⚠️ P1 起接口多了 [bodyWeightKg]（本地规则用它判断"体重 vs 目标体重"）。
     * 远端这条路**暂时不进提示词** —— 远端返回的计划无论如何都要过本地校验，
     * 而"把体重/目标体重写进提示词"属于 P3（下周计划生成）的范围；在此之前不要让
     * 远端和本地对同一个字段有两套说法。
     */
    override fun planWeek(
        profile: UserProfile,
        library: List<Exercise>,
        existing: List<WeekPlan>,
        history: List<ExerciseProgress>,
        today: LocalDate,
        bodyWeightKg: Float?,
    ): PlanProposal {
        val apiKey: String = credentials.apiKey()
            ?: throw RemoteAdvisorException("API Key 未配置")
        val response: String = completeOrThrow(
            systemPrompt = RemotePromptBuilder.buildPlanSystemPrompt(
                ProfileLimits.coerceTrainingDaysPerWeek(profile.trainingDaysPerWeek),
            ),
            userPrompt = RemotePromptBuilder.buildPlanUserPrompt(profile, library, existing, history),
            apiKey = apiKey,
        )
        return parseProposalJson(response, library, existing)
    }

    override fun suggestExercises(
        profile: UserProfile,
        candidates: List<Exercise>,
        existing: List<Exercise>,
    ): List<ExerciseSuggestion> {
        val apiKey: String = credentials.apiKey()
            ?: throw RemoteAdvisorException("API Key 未配置")
        val response: String = completeOrThrow(
            systemPrompt = RemotePromptBuilder.buildSuggestSystemPrompt(),
            userPrompt = RemotePromptBuilder.buildSuggestUserPrompt(profile, candidates),
            apiKey = apiKey,
        )
        return parseSuggestionsJson(response, candidates, existing)
    }

    /**
     * HTTP 调用统一收口：任何 IO 失败（网络 / 超时 / HTTP 非 2xx）都包装为
     * [RemoteAdvisorException] —— 远端层的失败**只以一种异常类型**对外呈现，
     * 委托层与单测的回落判据因此唯一。（消息只含技术细节，绝不含 Key。）
     */
    private fun completeOrThrow(systemPrompt: String, userPrompt: String, apiKey: String): String =
        try {
            api.complete(systemPrompt = systemPrompt, userPrompt = userPrompt, apiKey = apiKey)
        } catch (e: IOException) {
            throw RemoteAdvisorException("远端调用失败（网络 / 超时 / HTTP 错误）", e)
        }
}

/** 建议理由：模型给的名字对应不上的 reason 一律回落 GOAL_SUPPORT（不猜）。 */
private fun reasonFor(remote: RemoteSuggestion?): SuggestionReason = SuggestionReason.entries
    .firstOrNull { it.name == remote?.reason?.trim()?.uppercase() }
    ?: SuggestionReason.GOAL_SUPPORT

/** 理由 → `strings.xml` 资源名（与 `LocalRuleAdvisor` 同口径）。 */
private fun noteKeyFor(reason: SuggestionReason): String = when (reason) {
    SuggestionReason.INJURY_SWAP -> "note_ai_injury_swap"
    SuggestionReason.EQUIPMENT_FIT -> "note_ai_equipment_fit"
    SuggestionReason.GOAL_SUPPORT -> "note_ai_goal_support"
}

// ---------------- 响应 DTO（内部私有；**必填字段不带默认值** → 缺字段即抛可识别异常） ----------------

@Serializable
private data class RemoteProposal(
    val days: List<RemotePlanDay>,
    val analysis: String? = null,
)

@Serializable
private data class RemotePlanDay(
    val dayOfWeek: Int,
    val items: List<RemotePlanItem>,
    /** 可选：未知 / 缺省 → FULL_BODY。 */
    val focus: String? = null,
)

@Serializable
private data class RemotePlanItem(
    val exerciseId: Long,
    val targetSets: Int,
    val targetReps: Int,
    /** 可选：缺省 → 自重（null）。 */
    val targetWeightKg: Float? = null,
)

@Serializable
private data class RemoteSuggestions(
    val suggestions: List<RemoteSuggestion>,
)

@Serializable
private data class RemoteSuggestion(
    val name: String,
    /** 可选：未知 / 缺省 → GOAL_SUPPORT。 */
    val reason: String? = null,
)

private val lenientJson: Json = Json { ignoreUnknownKeys = true }

private const val DEFAULT_SETS: Int = 3
private const val DEFAULT_REPS: Int = 12
private const val MIN_DAY_OF_WEEK: Int = 1
private const val MAX_DAY_OF_WEEK: Int = 7
private const val MIN_SETS: Int = 1

/**
 * 组数上界 = [com.ironhabit.app.domain.model.MAX_SETS]（31）。
 *
 * 与 `CheckIn` 的逐组打卡位图同宽：`completed_sets_mask` 是 `Int`，第 32 组无法表示。
 * 若这里放宽到 50（历史值），AI 会写出"目标 40 组、但最多只能勾满 31 组"的行，
 * 该行的 `lastSetsCompleted >= lastTargetSets` 永远不成立 → 渐进超负荷永久失效。
 * 同时与表单校验（`InputLimits.MAX_SETS`）保持同一口径。
 */
private const val MAX_SETS: Int = 31
private const val MIN_REPS: Int = 1
private const val MAX_REPS: Int = 100

/** 秒 → 分换算基数（有氧动作 `defaultDurationSec` → 计划 `targetDurationMin`，修复 C3）。 */
private const val SECONDS_PER_MINUTE: Int = 60

/** 有氧时长合法下界（分钟）；不足 1 分钟视为无有效时长（`null`）。 */
private const val MIN_DURATION_MIN: Int = 1
