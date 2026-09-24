package com.ironhabit.app.domain.model

/**
 * 「计划 / 建议」相关的**领域模型**全集（纯 Kotlin，零 Android / 零 IO）。
 *
 * ⚠️ **包位置约定（重要）**：这些类型**必须**留在 `com.ironhabit.app.domain.model`。
 * UI 层（`AiCoachScreen` / `AiCoachViewModel`）直接 `import com.ironhabit.app.domain.model.*`
 * 取用 [ExerciseSuggestion] / [SuggestionReason] / [PlanNote] / [AdoptResult]，
 * 挪走会让 UI 编译失败。规则引擎本身（`domain/ai/LocalRuleAdvisor`）只是**消费者**。
 */
// 本文件为"模型"文件，类型统一在此声明，故无额外 import（同包引用 [ExerciseCategory]）。

/**
 * 计划 / 建议的**来源**（诚实标注：本地规则 vs 将来的联网模型）。
 *
 * 从第一天起就落在模型里 → 将来 UI 可明确标注"本地规则" / "AI 联网生成"，
 * 不会出现用户分不清是规则还是模型的情况。
 */
enum class AdviceSource {
    /** 本地确定性规则（本版唯一实现）。 */
    LOCAL_RULES,

    /** 将来的联网大模型（本期不实现）。 */
    REMOTE_LLM,

    /**
     * 用户拿去**外部 AI**（ChatGPT / DeepSeek 网页版…）问出来、再粘回 app 的文档。
     *
     * ⚠️ 单独立一个值，不复用 [REMOTE_LLM]：那两条的差别用户看得见也管不着 ——
     * [REMOTE_LLM] 是 **app 自己发的网络请求**（花用户的钱、受「AI 联网增强」开关与 Key 管），
     * 而这条路**app 一次网络都没发、一个 token 都没花**。标成"AI 生成"会让人以为
     * 是 app 排的计划，而它其实是用户在别处聊出来的。
     */
    EXTERNAL_AI_IMPORT,
}

/**
 * 一周某天的训练重点。
 *
 * UI 层按 `focus_*` 资源映射文案，**枚举本身不含中文**。
 */
enum class TrainingFocus {
    LOWER_BODY,
    UPPER_PUSH,
    UPPER_PULL,
    FULL_BODY,
    CARDIO_CORE,
}

/**
 * 「为什么这一条被排进来」。
 *
 * UI 层按 `reason_*` 资源映射文案，**枚举本身不含中文**。
 *
 * - [INJURY_SAFE]：**P1 起真的会产出** —— 判据是"去掉伤病后这一天就不会有这个动作"
 *   （见 `LocalRuleAdvisor.buildDay`）。旧注释说"本版不产出它"，已过期（复核报告 F-6）。
 */
enum class PlanReason {
    /** 主项动作（按目标挑的大动作）。 */
    PRIMARY_LIFT,

    /** 辅助动作。 */
    SUPPLEMENT,

    /** 因"用户有此器械"而可行。 */
    EQUIPMENT_MATCHED,

    /**
     * 因"避让伤病"才排进来的动作。
     *
     * 判定（P1）：把同一天按**不做伤病过滤**再选一遍，两次之差就是这个标记 ——
     * 也就是"没有伤病的话，这个动作不会出现在今天"。**不按肌群白名单挑**。
     */
    INJURY_SAFE,

    /** 因"上次做满且 RPE 有余量"而加重。 */
    PROGRESSIVE_OVERLOAD,

    /** 因"上次没做满 / 偏吃力"而维持。 */
    MAINTAIN,
}

/** 补充动作的建议理由（UI 层按 `note_ai_*` 资源映射文案）。 */
enum class SuggestionReason {
    /** 按伤病筛出的低冲击替代。 */
    INJURY_SWAP,

    /** 与用户现有器械匹配。 */
    EQUIPMENT_FIT,

    /** 贴合目标方向，用于补强薄弱环节。 */
    GOAL_SUPPORT,
}

/**
 * 「收入一条补充动作」的结果（幂等语义，供 UI 直接区分提示）。
 *
 * ⚠️ **刻意只有两个取值**：`AiCoachViewModel` 用 `when (result)` **穷尽匹配且不写 `else`**，
 * 一旦新增第三态会**直接编译失败** —— 这是故意设的护栏：
 * "要加状态就一起把 UI 的提示文案想清楚"，避免悄悄吞掉一种结果。
 *
 * - [ADDED]：本次真的写入了动作库；
 * - [ALREADY_EXISTS]：库里已有（**含已停用**）/ 空名字 / 不在候选池 → **一律不写入**
 *   （幂等命中，不产生重复行，也不靠数据库约束抛异常来表达）。
 */
enum class AdoptResult {
    /** 本次真的写入了动作库。 */
    ADDED,

    /** 未写入（已存在 / 空名字 / 非候选），幂等命中。 */
    ALREADY_EXISTS,
}

/**
 * 远端（DeepSeek）不可用时回落本地规则的原因（联网一期 §6.2 N4）。
 *
 * 由 [com.ironhabit.app.domain.ai.DelegatingPlanAdvisor] 记录，供 UI 显示
 * "本次来自本地规则（联网失败）"之类的**诚实提示**。
 */
enum class RemoteFallbackReason {
    /** 用户未开启「AI 联网生成」开关（默认关）。 */
    REMOTE_DISABLED,

    /** 未配置 API Key（或已清空）。 */
    KEY_NOT_CONFIGURED,

    /** 远端调用失败：网络 / 超时 / HTTP 非 200 / 响应解析失败。 */
    REMOTE_ERROR,
}

/**
 * 补充动作建议 + **来源标注**（联网一期：UI 据此区分"本地规则 / AI 联网生成"）。
 *
 * @property suggestions 建议列表（已排除库里已有的）
 * @property source 本次实际使用的来源
 * @property fallbackReason 走本地规则时的回落原因；`null` = 未发生回落
 */
data class SuggestionResult(
    val suggestions: List<ExerciseSuggestion>,
    val source: AdviceSource,
    val fallbackReason: RemoteFallbackReason? = null,
)

/**
 * 计划草案（**纯数据**，不落库、不改状态）。
 *
 * "要不要写、怎么写"由 UseCase 决定（见 `docs/ai-coach-local.md` §4.2）。
 *
 * @property days 各训练日的草案（只含**可写槽位**）
 * @property preservedUserEditedIds 被完整保留的既有**用户手改行** id（**含软删除行**），供 UI 展示"已保留 N 条"
 * @property notes "为什么这样排"的确定性理由
 * @property source 本次草案的实际来源（本地规则 / AI 联网生成；联网失败回落时为 LOCAL_RULES）
 */
data class PlanProposal(
    val days: List<PlannedDay> = emptyList(),
    val preservedUserEditedIds: List<Long> = emptyList(),
    val notes: List<PlanNote> = emptyList(),
    val analysis: String? = null,
    val basis: List<PlanBasisItem> = emptyList(),
    val source: AdviceSource = AdviceSource.LOCAL_RULES,
)

/** 某一天的训练草案。 */
data class PlannedDay(
    /** 星期，`1` = 周一 … `7` = 周日（与 [WeekPlan.dayOfWeek] 同口径）。 */
    val dayOfWeek: Int,
    val focus: TrainingFocus,
    val items: List<PlanItemDraft>,
)

/** 单条「天 × 动作」的目标草案。 */
data class PlanItemDraft(
    val exerciseId: Long,
    val targetSets: Int,
    val targetReps: Int,
    val targetWeightKg: Float?,
    /**
     * 目标时长（分钟），可空（有氧动作用）。
     *
     * 修复 C3：生成链路此前丢掉了有氧动作的时长 —— 规则层 / 远端解析都不产出它，
     * 落库时 `week_plans.target_duration_min` 恒为 `null`，训练页/今日卡片因此不显示"约 N 分钟"。
     */
    val targetDurationMin: Int? = null,
    val reason: PlanReason,
    /**
     * 外部 AI 文档给这一条写的**一句话解释**（自由文本）。
     *
     * ⚠️ **不落库**：`week_plans` 没有对应列，采纳之后这句话就没了 —— 它是"这次决定的理由"，
     * 不是动作的永久备注。为它加一列要动数据库迁移 + 备份 schema + 实体 + DAO，
     * 换来的是一条 AI 写的注释常驻在计划行上，不值。
     * 内置生成与本地规则恒为 `null`（它们不产中文，见 `LocalRuleAdvisor` 只吐资源名）。
     */
    val explanation: String? = null,
)

/** 一条"为什么这样排"（纯数据，UI 只渲染）。 */
data class PlanNote(
    val kind: PlanReason,
    val exerciseId: Long,
    val detail: PlanNoteDetail,
)

/**
 * [PlanNote] 的结构化参数（少量、可序列化，**不含文案**）。
 *
 * 文案一律在 UI 层用 `strings.xml` 拼（架构 §7.5 禁止硬编码中文）。
 */
sealed interface PlanNoteDetail {

    /** 重量变化：`oldWeightKg` → `newWeightKg`。 */
    data class WeightDelta(val oldWeightKg: Float?, val newWeightKg: Float?) : PlanNoteDetail

    /** 组数变化：`oldSets` → `newSets`（自重动作无重量时的加重方式）。 */
    data class SetsDelta(val oldSets: Int, val newSets: Int) : PlanNoteDetail

    /** 无附加参数（如"维持"）。 */
    data object None : PlanNoteDetail
}

/**
 * 本地规则的「生成依据」要点（结构化，不含文案）。
 *
 * `key` 对应 `strings.xml` 的资源**名称**（UI 层经 `basisKeyRes` 映射成资源 id），
 * `args` 为格式化参数（类型须与对应资源占位符一致，避免 `IllegalFormatConversionException`）。
 */
data class PlanBasisItem(
    val key: String,
    val args: List<Any> = emptyList(),
)

/**
 * 补充动作建议（纯数据）。
 *
 * ⚠️ [name] **即幂等键** —— 与 `exercises.name`（UNIQUE）对应；
 * 落地时用"命中即跳过"，**不依赖数据库约束兜底**。
 *
 * @property noteKey 对应 `strings.xml` 的 note 资源**名称**（如 `note_ai_injury_swap`），**不内联中文**
 */
data class ExerciseSuggestion(
    val name: String,
    val category: ExerciseCategory,
    /** 有序肌群列表，首个 = 主肌群。 */
    val muscleGroups: List<String>,
    /** 所需器械（v7 新增）；随建议一起被"收入"写进动作库，否则收入后的行只能回落到分类判据。 */
    val equipment: List<Equipment> = emptyList(),
    val defaultSets: Int,
    val defaultReps: Int,
    val noteKey: String,
    val reason: SuggestionReason,
)

/**
 * 某个动作"最近一次"的完成情况（渐进超负荷的输入，由 UseCase 从既有打卡 + RPE 组装）。
 *
 * @property lastSetsCompleted 上次完成组数
 * @property lastTargetSets 上次目标组数（做满与否的判据）；**`null` = 该次打卡未关联计划**，
 *   即"没有可信目标"——此时**不得**假定做满（修复 C4：旧实现用兜底常量 3 顶替，导致误判做满 → 误加重）
 * @property lastRpe 上次主观强度 `1..10`，`null` = 未评级
 * @property lastWeightKg 上次使用重量；`null` = 自重动作
 */
data class ExerciseProgress(
    val exerciseId: Long,
    val lastSetsCompleted: Int,
    val lastTargetSets: Int?,
    val lastRpe: Int?,
    val lastWeightKg: Float?,
)
