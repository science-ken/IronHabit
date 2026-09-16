package com.ironhabit.app.domain.ai.remote

import com.ironhabit.app.domain.model.DietTarget
import com.ironhabit.app.domain.usecase.CoachContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 自由问答（子项 A）的**远端提示词组装 + 回答解析**，纯函数、零 IO（JVM 单测可直接覆盖）。
 *
 * ## 为什么是 JSON
 * 复用的 [DeepSeekApi.complete] 强制 `response_format = json_object`（见 [DeepSeekClient]），
 * 模型**必然**返回 JSON。因此这里要求模型把回答放进 `{"answer": "..."}`，
 * 再用 [parseChatAnswer] 取出正文 —— **不改动既有 [DeepSeekApi] 接口**。
 *
 * ⚠️ 本文件的提示词是给**模型**的指令；载荷里的中文（动作名等）是**数据**，不进 `strings.xml`。
 */
internal object RemoteChatPromptBuilder {

    /** 自由问答的 system 段。 */
    fun buildChatSystemPrompt(): String = CHAT_SYSTEM_PROMPT

    /**
     * 自由问答的 user 段：用户问题 + 一份用户现状上下文（档案 / 本周计划 / 近期打卡 / 今日饮食）。
     *
     * ⚠️ **绝不把 API Key 拼进提示词**（Key 只进 Authorization header，见红线）。
     */
    fun buildChatUserPrompt(question: String, context: CoachContext): String {
        val payload = ChatPayload(
            profile = ChatProfilePayload(
                gender = context.profile.gender?.name,
                age = context.profile.age,
                heightCm = context.profile.heightCm,
                goal = context.profile.goal.name,
                equipment = context.profile.equipment.map { it.name },
                injuryAreas = context.profile.injuryAreas.map { it.name },
                dietaryAvoid = context.profile.dietaryAvoid.map { it.name },
                injuryNote = context.profile.injuryNote,
            ),
            weeklyPlan = context.weeklyPlan.map { line ->
                ChatPlanLinePayload(
                    dayOfWeek = line.dayOfWeek,
                    exerciseName = line.exerciseName,
                    targetSets = line.targetSets,
                    targetReps = line.targetReps,
                )
            },
            recentCheckIn = ChatCheckInPayload(
                windowDays = context.windowDays,
                count = context.checkInCount,
                averageRpe = context.averageRpe,
                currentStreak = context.currentStreak,
            ),
            weightDeltaKg = context.weightDeltaKg,
            todayDiet = ChatDietPayload(
                intakeKcal = context.todayIntakeKcal,
                planKcal = context.todayPlanKcal,
            ),
            question = question,
        )
        return chatJson.encodeToString(ChatPayload.serializer(), payload)
    }

    /**
     * 「为什么这样吃」的 system 段（子项 B）：只出**文字分析**，数值一律以本地为准。
     */
    fun buildDietSystemPrompt(): String = DIET_SYSTEM_PROMPT

    /**
     * 「为什么这样吃」的 user 段：用户档案 + 近期打卡 + 今日饮食现状 + **本地算出的目标**。
     *
     * 🔒 目标数值由本地纯函数给出（[DietTarget]），提示词里明确要求模型**不要改数字**，
     * 因此离线数值永远可信，远端只负责"讲清楚为什么"。
     */
    fun buildDietUserPrompt(context: CoachContext, target: DietTarget): String {
        val payload = DietAnalysisPayload(
            profile = ChatProfilePayload(
                gender = context.profile.gender?.name,
                age = context.profile.age,
                heightCm = context.profile.heightCm,
                goal = context.profile.goal.name,
                equipment = context.profile.equipment.map { it.name },
                injuryAreas = context.profile.injuryAreas.map { it.name },
                dietaryAvoid = context.profile.dietaryAvoid.map { it.name },
                injuryNote = context.profile.injuryNote,
            ),
            recentCheckIn = ChatCheckInPayload(
                windowDays = context.windowDays,
                count = context.checkInCount,
                averageRpe = context.averageRpe,
                currentStreak = context.currentStreak,
            ),
            todayDiet = ChatDietPayload(
                intakeKcal = context.todayIntakeKcal,
                planKcal = context.todayPlanKcal,
            ),
            targetKcal = target.targetKcal,
            targetProtein = target.targetProtein,
            usedDefaults = target.usedDefaults,
        )
        return chatJson.encodeToString(DietAnalysisPayload.serializer(), payload)
    }

    /**
     * 「进度解读」的 system 段（子项 C）：只解读数字、给下一步建议，不得改数字、不做医疗诊断。
     */
    fun buildInsightSystemPrompt(): String = INSIGHT_SYSTEM_PROMPT

    /**
     * 「进度解读」的 user 段：近 N 天的**本地聚合数字**（打卡次数 / 平均 RPE / 体重变化 /
     * 连续天数 / 今日饮食）。模型只负责解读，**不得改数字**。
     */
    fun buildInsightUserPrompt(context: CoachContext): String {
        val payload = InsightPayload(
            profile = ChatProfilePayload(
                gender = context.profile.gender?.name,
                age = context.profile.age,
                heightCm = context.profile.heightCm,
                goal = context.profile.goal.name,
                equipment = context.profile.equipment.map { it.name },
                injuryAreas = context.profile.injuryAreas.map { it.name },
                dietaryAvoid = context.profile.dietaryAvoid.map { it.name },
                injuryNote = context.profile.injuryNote,
            ),
            recentCheckIn = ChatCheckInPayload(
                windowDays = context.windowDays,
                count = context.checkInCount,
                averageRpe = context.averageRpe,
                currentStreak = context.currentStreak,
            ),
            weightDeltaKg = context.weightDeltaKg,
            todayDiet = ChatDietPayload(
                intakeKcal = context.todayIntakeKcal,
                planKcal = context.todayPlanKcal,
            ),
        )
        return chatJson.encodeToString(InsightPayload.serializer(), payload)
    }

    /**
     * 从模型返回文本里取出回答正文（`{"answer": "..."}`）。
     *
     * 宽松策略：
     * ① 去 ``` 围栏 → ② 是合法 JSON → **以 `answer` 字段为准**（缺字段 / 空串 = 本次回答无效 → `null`，
     * 绝不把 JSON 原文当回答显示给用户）→ ③ 不是 JSON（模型偶尔直接输出正文）→ 退回原文
     * → ④ 结果 trim 后为空则返回 `null`（由调用方转成可识别的**空响应失败**）。
     */
    fun parseChatAnswer(raw: String): String? {
        val cleaned: String = stripFence(raw).trim()
        if (cleaned.isEmpty()) return null
        val parsed: ChatAnswer? = runCatching {
            chatJson.decodeFromString(ChatAnswer.serializer(), cleaned)
        }.getOrNull()
        val text: String = when {
            parsed != null -> parsed.answer?.trim().orEmpty()
            else -> cleaned
        }
        return text.takeIf { it.isNotEmpty() }
    }

    /** 容忍模型违规包裹 ``` 围栏（system 段已禁止，但双保险）。 */
    private fun stripFence(raw: String): String {
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

    // ---------------- 载荷 / 回答 DTO（内部私有） ----------------

    @Serializable
    private data class ChatPayload(
        val profile: ChatProfilePayload,
        val weeklyPlan: List<ChatPlanLinePayload>,
        val recentCheckIn: ChatCheckInPayload,
        val weightDeltaKg: Float? = null,
        val todayDiet: ChatDietPayload,
        val question: String,
    )

    @Serializable
    private data class ChatProfilePayload(
        val gender: String?,
        val age: Int?,
        val heightCm: Int?,
        val goal: String,
        val equipment: List<String>,
        val injuryAreas: List<String>,
        val dietaryAvoid: List<String>,
        val injuryNote: String?,
    )

    @Serializable
    private data class ChatPlanLinePayload(
        val dayOfWeek: Int,
        val exerciseName: String,
        val targetSets: Int,
        val targetReps: Int,
    )

    @Serializable
    private data class ChatCheckInPayload(
        val windowDays: Int,
        val count: Int,
        val averageRpe: Double? = null,
        val currentStreak: Int,
    )

    @Serializable
    private data class ChatDietPayload(
        val intakeKcal: Int,
        val planKcal: Int,
    )

    /** 「为什么这样吃」的载荷（子项 B）：档案 + 近期打卡 + 今日饮食 + **本地目标（不可改）**。 */
    @Serializable
    private data class DietAnalysisPayload(
        val profile: ChatProfilePayload,
        val recentCheckIn: ChatCheckInPayload,
        val todayDiet: ChatDietPayload,
        val targetKcal: Int,
        val targetProtein: Int,
        val usedDefaults: Boolean,
    )

    /** 「进度解读」的载荷（子项 C）：档案 + 近 N 天数字 + 今日饮食。 */
    @Serializable
    private data class InsightPayload(
        val profile: ChatProfilePayload,
        val recentCheckIn: ChatCheckInPayload,
        val weightDeltaKg: Float? = null,
        val todayDiet: ChatDietPayload,
    )

    @Serializable
    private data class ChatAnswer(
        val answer: String? = null,
    )

    private val chatJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private const val CHAT_SYSTEM_PROMPT: String = """
你是一名用户的私人健身教练，只回答与训练、饮食、恢复、坚持健身相关的问题。

硬性规则：
1. 只回答训练 / 饮食 / 恢复 / 坚持健身相关的问题；与之无关的问题（政治、法律、编程、闲聊等）一律礼貌拒答，并说明你只聊健身。
2. 不做医疗诊断：涉及伤病、疾病、用药、孕期等问题，只给一般性建议并明确提示"请咨询医生"，绝不给出诊断或处方。
3. 必须结合下面提供的用户上下文（身体档案 / 本周训练计划 / 近期打卡 / 今日饮食）来回答，给出贴合他本人的具体建议，不要泛泛而谈。
4. 用简体中文回答。
5. 回答控制在 200 字以内，直接给可执行的建议（做什么、几组几次、怎么吃、注意什么）。
6. 只输出一个 JSON 对象（不要使用 Markdown 代码块围栏），形如 {"answer":"你的回答"}，把简体中文回答正文放进 answer 字段。

输出格式：
{"answer":"结合你上周的训练与今日饮食，建议……"}
"""

    /** 「为什么这样吃」的 system 段：只解释、不改数值、不做医疗诊断。 */
    private const val DIET_SYSTEM_PROMPT: String = """
你是一名用户的私人健身教练，本次只做一件事：用简体中文解释「这份今日饮食计划为什么这样安排」。

硬性规则：
1. 营养数值（热量 / 蛋白质）**以下面给出的目标为准**：不要重新计算、不要改写数字、不要给出精确到克的食谱配方。
2. 必须结合用户档案（性别 / 年龄 / 身高 / 目标 / 忌口）与近期训练情况来说明：为什么是这些量、训练日与休息日的差别、怎样吃更容易达标。
3. 不做医疗诊断：涉及疾病、用药、孕期等问题，只给一般性建议并明确提示"请咨询医生"。
4. 用简体中文回答。
5. 回答控制在 200 字以内，直接给可执行的吃法建议（几餐、优先吃什么、注意什么）。
6. 只输出一个 JSON 对象（不要使用 Markdown 代码块围栏），形如 {"answer":"你的分析"}，把简体中文分析正文放进 answer 字段。

输出格式：
{"answer":"你今天的目标是……"}
"""

    /** 「进度解读」的 system 段：先讲进展、再给 1~2 条可执行的下一步；数字不得改。 */
    private const val INSIGHT_SYSTEM_PROMPT: String = """
你是一名用户的私人健身教练，本次只做一件事：用简体中文写一段「最近进展 + 下一步建议」。

硬性规则：
1. 下面给出的数字（打卡次数、平均 RPE、体重变化、连续天数、今日饮食）**都是事实：不得改动、不得重新计算**。
2. 先说最近做得怎么样（出勤、强度、体重趋势），再给 1~2 条**具体可执行**的下一步建议（加多少重量或组数、怎么吃、怎么恢复）。
3. 不做医疗诊断：涉及伤病、疾病、用药、孕期等内容只给一般性建议并明确提示"请咨询医生"。
4. 只聊训练 / 饮食 / 恢复 / 坚持；其它话题礼貌拒答。
5. 用简体中文回答，200 字以内。
6. 只输出一个 JSON 对象（不要使用 Markdown 代码块围栏），形如 {"answer":"你的解读"}，把分析正文放进 answer 字段。

输出格式：
{"answer":"最近两周你出勤……"}
"""
}
