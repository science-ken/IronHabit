package com.ironhabit.app.domain.ai.external

import com.ironhabit.app.domain.ai.remote.stripCodeFence
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.PlanItemDraft
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.PlannedDay
import com.ironhabit.app.domain.model.TrainingFocus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **外部 AI 文档 → 一周计划草案** 的解析（零网络、零 Android 依赖，纯函数）。
 *
 * ## 这条路在干什么
 * 用户把 app 生成的提问模板连同数据包一起复制给他自己的 AI（ChatGPT / DeepSeek 网页版…），
 * 拿回一段文档，粘回 app。app 在这里**不联网、不花 token**，只把那段文档读成草案。
 *
 * ## 与内置远端解析（`RemoteLlmAdvisor.parseProposalJson`）的关系
 * - **形状刻意同构**：`days / items` 字段名和内置输出一致，只有 `exerciseId` → `exercise`（名字）。
 *   外部 AI 只看得见数据包里的**动作名**（`ironhabit-week-package/v1` 的 `library` 没有 id），
 *   所以回程必须按名字反查；
 * - **防线同一份口径**：组次上限取自 [InputLimits]（与表单校验、打卡位图同源），不另立数字；
 * - **必须分开的一份**：本函数**不做**"手改槽位不碰""模板归属日不写"这两条保护 —— 那是
 *   [com.ironhabit.app.domain.usecase.PlanDraftProjector] 的活，两条来源共用一个投影器。
 *   在这里再挡一次会让"为什么这条没进来"出现两套答案。
 *
 * ## 为什么拒收而不是尽力解析
 * 自然语言 / Markdown 表格识别是无底洞，且永远没法用测试钉住。所以合同写死在模板里，
 * 对不上就**明说对不上**（[ExternalDocRefusal]），并把模板再给一次。
 * 唯一宽容的两处：```` ```json ````围栏与前后寒暄（[stripCodeFence] + 截 JSON 子串）、
 * 未知字段（`ignoreUnknownKeys`，模型爱加什么加什么）。
 */
object ExternalPlanDocumentParser {

    /** 一条外部文档**最多**允许多少字符：超过直接拒收，不给它分配序列化开销。 */
    const val MAX_DOC_BYTES: Int = 512 * 1024

    /**
     * 单日条目上限。
     *
     * 按打卡界面的可用性定的（一天十几条动作用户真的做不完），不是随便取整：
     * 外部 AI 看不见"本周已经排了什么"以外的约束，也不受内置提示词里那句硬上限管束，
     * 冒出"周一 22 个动作"是常态。**超出的部分不静默截断**，一律进 [ExternalPlanNote.Kind.OVER_DAILY_LIMIT]。
     */
    const val MAX_ITEMS_PER_DAY: Int = 12

    /**
     * 把用户粘回来的文本读成草案。
     *
     * @param text 粘贴的原始文本（可能带围栏、前后有模型的解释话）
     * @param library 用户**启用中**的动作库（= 名字白名单，也是"库里没有"的判据）
     */
    fun parse(text: String, library: List<Exercise>): ExternalDocOutcome {
        // 长度是字节数下界（UTF-8 每字符 ≥1 字节）：先做 O(1) 的粗筛，避免为巨型粘贴分配字节数组。
        if (text.length > MAX_DOC_BYTES) return ExternalDocOutcome.Refused(ExternalDocRefusal.TOO_LARGE)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_DOC_BYTES) {
            return ExternalDocOutcome.Refused(ExternalDocRefusal.TOO_LARGE)
        }

        val document: ExternalDocument = decode(text)
            ?: return ExternalDocOutcome.Refused(
                if (stripCodeFence(text).isEmpty()) ExternalDocRefusal.EMPTY_DOCUMENT
                else ExternalDocRefusal.NOT_A_DOCUMENT,
            )

        // schema 不回显就拒收：粘进来的东西没有边界，用户可能粘的是聊天记录、别家的 JSON、
        // 或上一版模板的输出。标签是唯一便宜的判据，而"尽力读读看"会把上一次的计划混进这一周。
        if (document.schema?.trim() != ExternalPlanSchema.SCHEMA) {
            return ExternalDocOutcome.Refused(ExternalDocRefusal.WRONG_SCHEMA)
        }

        val byName: Map<String, Exercise> = library
            .mapNotNull { exercise -> exercise.name.trim().takeIf { it.isNotEmpty() }?.let { it.lowercase() to exercise } }
            .toMap()

        val notes = mutableListOf<ExternalPlanNote>()
        val days: List<PlannedDay> = document.days
            .map { day -> day.dayOfWeek.coerceIn(MIN_DAY_OF_WEEK, MAX_DAY_OF_WEEK) to day }
            .mapNotNull { (dayOfWeek, day) ->
                val items = resolveItems(dayOfWeek, day.items, byName, notes)
                if (items.isEmpty()) null
                else PlannedDay(
                    dayOfWeek = dayOfWeek,
                    focus = TrainingFocus.entries.firstOrNull { it.name == day.focus?.trim()?.uppercase() }
                        ?: TrainingFocus.FULL_BODY,
                    items = items,
                )
            }

        val (profilePatch, profileNotes) = resolveProfile(document.profile)
        notes += profileNotes

        if (days.isEmpty()) {
            // 和内置 B-3 同口径：一条都不剩 ≠ "这周什么都不练"。当成有效结果送去采纳会把整周清空。
            //
            // 但两种"空"要给两句话，因为下一步完全不同：
            // - 文档里**根本没写动作**（days 为空 / 每天 items 都空）→ 多半是它没收到 library，
            //   该重发模板；这时候说"动作名对不上"是把用户往错方向支。
            // - 写了动作但**每条都被防线挡掉** → 才是名字对不上，逐条清单在这里最值钱。
            val hadAnyItem: Boolean = document.days.any { day -> day.items.isNotEmpty() }
            return ExternalDocOutcome.Refused(
                reason = if (hadAnyItem) ExternalDocRefusal.NO_USABLE_ITEMS else ExternalDocRefusal.EMPTY_PLAN,
                notes = notes.toList(),
                analysis = document.analysis?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        return ExternalDocOutcome.Parsed(
            ExternalPlanDraft(
                proposal = PlanProposal(
                    days = days,
                    // 来源如实标注：app 没生成它，也没联网。
                    source = AdviceSource.EXTERNAL_AI_IMPORT,
                    analysis = document.analysis?.trim()?.takeIf { it.isNotEmpty() },
                ),
                notes = notes.toList(),
                profile = profilePatch,
            ),
        )
    }

    /** 一天内的条目：名字反查 → 去重 → 单日上限 → 数值钳制（顺序即丢弃原因优先级）。 */
    private fun resolveItems(
        dayOfWeek: Int,
        rawItems: List<ExternalItem>,
        byName: Map<String, Exercise>,
        notes: MutableList<ExternalPlanNote>,
    ): List<PlanItemDraft> {
        val seen = mutableSetOf<Long>()
        val resolved = mutableListOf<PlanItemDraft>()

        for (raw: ExternalItem in rawItems) {
            val name: String = raw.exercise.trim()
            if (name.isEmpty()) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.BLANK_EXERCISE_NAME, dayOfWeek)
                continue
            }
            val exercise: Exercise? = byName[name.lowercase()]
            if (exercise == null) {
                // 幻觉名 / 已停用 / 用户改了名 —— 一律"库里找不到"，不猜、不新建（建库外动作是另一刀）。
                notes += ExternalPlanNote(ExternalPlanNote.Kind.UNKNOWN_EXERCISE, dayOfWeek, name)
                continue
            }
            if (!seen.add(exercise.id)) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.DUPLICATE_EXERCISE, dayOfWeek, name)
                continue
            }
            if (resolved.size >= MAX_ITEMS_PER_DAY) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.OVER_DAILY_LIMIT, dayOfWeek, name)
                continue
            }

            val sets: Int = raw.targetSets.coerceIn(InputLimits.MIN_SETS, InputLimits.MAX_SETS)
            val reps: Int = raw.targetReps.coerceIn(InputLimits.MIN_REPS, InputLimits.MAX_REPS)
            if (raw.targetSets != sets) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.SETS_CLAMPED, dayOfWeek, name, listOf(raw.targetSets, sets))
            }
            if (raw.targetReps != reps) {
                notes += ExternalPlanNote(ExternalPlanNote.Kind.REPS_CLAMPED, dayOfWeek, name, listOf(raw.targetReps, reps))
            }

            resolved += PlanItemDraft(
                exerciseId = exercise.id,
                targetSets = sets,
                targetReps = reps,
                // 与内置同口径：≤0 视为自重（null），不是"0 公斤"。
                targetWeightKg = raw.targetWeightKg?.takeIf { it > 0f },
                // 时长不信模型：回本地动作库取默认时长换算（修复 C3 的同一件事）。
                targetDurationMin = exercise.defaultDurationSec
                    ?.let { seconds -> seconds / SECONDS_PER_MINUTE }
                    ?.takeIf { minutes -> minutes >= MIN_DURATION_MIN },
                reason = if (resolved.isEmpty()) PlanReason.PRIMARY_LIFT else PlanReason.SUPPLEMENT,
            )
        }
        return resolved
    }

    /**
     * 档案段：允许的字段读成补丁，**身体实测/身份字段点名拒收**。
     *
     * 实测值不收 —— 那是**测量值不是建议**，模型填进来就是数据污染，
     * 而且档案数字会喂给以后每一次本地生成。
     */
    private fun resolveProfile(profile: ExternalProfile?): Pair<ExternalProfilePatch, List<ExternalPlanNote>> {
        if (profile == null) return ExternalProfilePatch() to emptyList()

        val notes = mutableListOf<ExternalPlanNote>()
        val forbidden = buildList {
            profile.heightCm?.let { add(ExternalPlanSchema.FIELD_HEIGHT_CM) }
            profile.age?.let { add(ExternalPlanSchema.FIELD_AGE) }
            profile.bodyFatPct?.let { add(ExternalPlanSchema.FIELD_BODY_FAT_PCT) }
            (profile.weightKg ?: profile.currentWeightKg)?.let { add(ExternalPlanSchema.FIELD_WEIGHT_KG) }
            profile.gender?.let { add(ExternalPlanSchema.FIELD_GENDER) }
        }
        notes += forbidden.map { field ->
            ExternalPlanNote(ExternalPlanNote.Kind.PROFILE_FIELD_FORBIDDEN, null, field)
        }

        val goal: Goal? = profile.goal?.let { raw -> enumNameOrNull<Goal>(raw) }
            .also { if (it == null && !profile.goal.isNullOrBlank()) notes += rejected("goal") }

        val equipment: Set<Equipment>? = profile.equipment?.let { names ->
            resolveNames(names) { raw -> enumNameOrNull<Equipment>(raw) }
                .also { it.second.forEach { name -> notes += rejected("equipment[$name]") } }
                .first
        }
        val injuryAreas: Set<InjuryArea>? = profile.injuryAreas?.let { names ->
            resolveNames(names) { raw -> enumNameOrNull<InjuryArea>(raw) }
                .also { it.second.forEach { name -> notes += rejected("injuryAreas[$name]") } }
                .first
        }

        return ExternalProfilePatch(
            goal = goal,
            goalWeightKg = profile.goalWeightKg,
            trainingDaysPerWeek = profile.trainingDaysPerWeek,
            equipment = equipment,
            injuryAreas = injuryAreas,
            injuryNote = profile.injuryNote,
        ) to notes
    }

    /**
     * 名字列表 → 枚举集合。**认不出的逐条回报，不猜**（`SHAPE` 不等于 `TONING`）。
     *
     * 全部认不出时返回 `null`（= 这一项不采纳）而不是空集合 —— 空集合在档案里是有意义的值
     * （"我没有伤病了" / "只用自重"），拿"模型编了三个假名字"去触发它，等于静默清空用户的约束。
     * 用户真想要空集合，就写一个空数组 `[]`，那是明确指令。
     */
    private fun <T> resolveNames(
        names: List<String>,
        resolve: (String) -> T?,
    ): Pair<Set<T>?, List<String>> {
        if (names.isEmpty()) return emptySet<T>() to emptyList()
        val resolved = names.mapNotNull { name -> resolve(name.trim())?.let { name.trim() to it } }
        if (resolved.isEmpty()) return null to names.map { it.trim() }
        val unknown = names.map { it.trim() } - resolved.map { it.first }.toSet()
        return resolved.map { it.second }.toSet() to unknown
    }

    private inline fun <reified T : Enum<T>> enumNameOrNull(raw: String): T? =
        enumValues<T>().firstOrNull { it.name == raw.trim().uppercase() }

    private fun rejected(field: String) =
        ExternalPlanNote(ExternalPlanNote.Kind.PROFILE_VALUE_REJECTED, null, field)

    /** 去围栏 → 抽出 JSON 子串 → 宽松反序列化；任何失败都返回 `null`（调用方转成可识别拒收原因）。 */
    private fun decode(raw: String): ExternalDocument? {
        val candidate: String = extractJsonObject(stripCodeFence(raw)) ?: return null
        return try {
            json.decodeFromString(ExternalDocument.serializer(), candidate)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * 模型经常把 JSON 夹在"好的，这是你要的计划："和一句收尾之间 —— 取第一个 `{` 到最后一个 `}`。
     *
     * 这不是"尽力解析自然语言"：中间那段仍然必须是合法 JSON 才过得去，缺必填字段照样失败。
     */
    private fun extractJsonObject(text: String): String? {
        val start: Int = text.indexOf('{')
        val end: Int = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    private val json: Json = Json { ignoreUnknownKeys = true }

    private const val MIN_DAY_OF_WEEK: Int = 1
    private const val MAX_DAY_OF_WEEK: Int = 7
    private const val SECONDS_PER_MINUTE: Int = 60
    private const val MIN_DURATION_MIN: Int = 1
}

/** 冻结的回程合同标识（改结构必须换版本号，模板与解析器同时改）。 */
object ExternalPlanSchema {
    const val SCHEMA: String = "ironhabit-plan-import/v1"

    // 拒收名单里的字段名：给界面显示"哪一项被拒了"用，**不是**中文文案（架构禁止硬编码中文）。
    const val FIELD_HEIGHT_CM: String = "heightCm"
    const val FIELD_AGE: String = "age"
    const val FIELD_BODY_FAT_PCT: String = "bodyFatPct"
    const val FIELD_WEIGHT_KG: String = "weightKg"
    const val FIELD_GENDER: String = "gender"
}

/** 解析结果：要么拿到一份草案，要么**明确**说明为什么整份不收。 */
sealed interface ExternalDocOutcome {
    data class Parsed(val draft: ExternalPlanDraft) : ExternalDocOutcome

    /**
     * @property notes 拒收时**也带**逐条说明：`NO_USABLE_ITEMS` 的含义是"读通了，但每一条都被防线挡掉"，
     *   此时用户最需要知道的恰恰是被挡掉的是哪几条、为什么（多半是他库里没有那个动作）。
     *   格式类拒收（不是 JSON / schema 不对 / 太大）没有清单可言，恒为空。
     */
    data class Refused(
        val reason: ExternalDocRefusal,
        val notes: List<ExternalPlanNote> = emptyList(),
        /** 模型自己在 `analysis` 里写的话。拒收时尤其要看它 —— 它常常直接说了为什么没排。 */
        val analysis: String? = null,
    ) : ExternalDocOutcome
}

/** 整份拒收的原因（部分有效不算拒收 —— 见 [ExternalPlanNote]）。 */
enum class ExternalDocRefusal {
    /** 什么都没粘。 */
    EMPTY_DOCUMENT,

    /** 不是合法 JSON / 缺必填字段 / 抽不出 JSON 对象。 */
    NOT_A_DOCUMENT,

    /** `schema` 缺失或不是本版本认的那个值。 */
    WRONG_SCHEMA,

    /** 超过 [ExternalPlanDocumentParser.MAX_DOC_BYTES]。 */
    TOO_LARGE,

    /** 文档结构上就没写任何动作（`days` 空 / 每天 `items` 都空）—— 与下面那条不同，多半是它没收到动作库。 */
    EMPTY_PLAN,

    /** 写了动作，但每一条都被防线挡掉（全对不上动作库）—— 与内置 B-3 同口径，不能当有效结果落库。 */
    NO_USABLE_ITEMS,
}

/** 解析成功的一份草案 + 逐条"为什么这条没进来 / 哪个数字被动过"。 */
data class ExternalPlanDraft(
    /** 归一到内置同形状，直接交给 `PlanDraftProjector` 投影。 */
    val proposal: PlanProposal,
    val notes: List<ExternalPlanNote>,
    /** 档案段（已过滤 + 已钳制前的原值）。空补丁 = 文档没提档案。 */
    val profile: ExternalProfilePatch = ExternalProfilePatch(),
)

/**
 * 一条"文档里有什么被丢了 / 被改了"。
 *
 * 界面**必须**摊开这些，一条都不许静默吞掉 —— 用户看不见动作库 id、也不知道自己哪些动作停用过，
 * 只有如实列出来他才知道"缺的那两条是他自己库里没有"，而不是"app 偷偷少写了"。
 *
 * @property args 给 `strings.xml` 占位符用的参数（类型与资源占位符一致）
 */
data class ExternalPlanNote(
    val kind: Kind,
    /** 所属星期 `1..7`；档案类说明与天无关，为 `null`。 */
    val dayOfWeek: Int? = null,
    /** 相关动作名 / 档案字段名（原样回显，让用户对得上他自己写的那份文档）。 */
    val subject: String? = null,
    val args: List<Any> = emptyList(),
) {
    enum class Kind {
        /** 名字在动作库里找不到（幻觉名 / 已停用 / 改名）。 */
        UNKNOWN_EXERCISE,

        /** 空动作名。 */
        BLANK_EXERCISE_NAME,

        /** 同一天重复出现的同一动作（保留第一条）。 */
        DUPLICATE_EXERCISE,

        /** 该天超出 [ExternalPlanDocumentParser.MAX_ITEMS_PER_DAY] 之后的条目。 */
        OVER_DAILY_LIMIT,

        /** `targetSets` 越界，已钳制（args：原值、钳后值）。 */
        SETS_CLAMPED,

        /** `targetReps` 越界，已钳制（args：原值、钳后值）。 */
        REPS_CLAMPED,

        /** 文档改了身体实测/身份字段，**拒收**（subject = 字段名）。 */
        PROFILE_FIELD_FORBIDDEN,

        /** 档案字段写了个认不出的值（`goal:"TONING"`、编造的器械名…），**不猜、不采纳**（subject = 字段名）。 */
        PROFILE_VALUE_REJECTED,
    }
}

// ---------------- 回程文档 DTO（必填字段不带默认值 → 缺字段即整份拒收） ----------------

@Serializable
private data class ExternalDocument(
    /** 必须回显 `ironhabit-plan-import/v1`，否则整份不收。 */
    val schema: String? = null,
    val days: List<ExternalDay>,
    /** 可选：外部 AI 的"为什么这么排"，原样透传给预览页显示。 */
    val analysis: String? = null,
    /** 可选：档案改动（本版只登记、不应用）。 */
    val profile: ExternalProfile? = null,
)

@Serializable
private data class ExternalDay(
    val dayOfWeek: Int,
    val items: List<ExternalItem>,
    val focus: String? = null,
)

@Serializable
private data class ExternalItem(
    /** **名字**而不是 id：数据包里的 `library` 本来就没有 id。 */
    val exercise: String,
    val targetSets: Int,
    val targetReps: Int,
    val targetWeightKg: Float? = null,
)

/**
 * 档案段。允许的字段收进来但本版不应用；**拒收的字段必须显式声明**，
 * 否则 `ignoreUnknownKeys` 会让它们凭空消失，用户以为改了、其实什么都没发生。
 */
@Serializable
private data class ExternalProfile(
    val goal: String? = null,
    val goalWeightKg: Float? = null,
    val trainingDaysPerWeek: Int? = null,
    val equipment: List<String>? = null,
    val injuryAreas: List<String>? = null,
    val injuryNote: String? = null,
    val heightCm: Int? = null,
    val age: Int? = null,
    val bodyFatPct: Float? = null,
    val weightKg: Float? = null,
    val currentWeightKg: Float? = null,
    val gender: String? = null,
)
