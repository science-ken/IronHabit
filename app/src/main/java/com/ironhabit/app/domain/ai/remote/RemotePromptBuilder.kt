package com.ironhabit.app.domain.ai.remote

import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 远端（DeepSeek）提示词组装：**system + user 两段**。
 *
 * ## 硬要求（写死在 system 段，对应派工单防线）
 * 1. `exerciseId` **只能**从提供的动作 id 列表里选（防幻觉出不存在的 id）；
 *    补充动作的 `name` **只能**从候选池的名单里选；
 * 2. 伤病部位必须避开对应肌群；
 * 3. **只输出 JSON**，不要解释文字、不要 Markdown 围栏。
 *
 * ⚠️ 本文件的提示词是给**模型**的指令，不是用户界面文案（不进 `strings.xml`，
 * strings.xml 由 team-lead 维护）；载荷里的中文（动作名 / 肌群标签）是**数据**。
 */
internal object RemotePromptBuilder {

    /** 一周计划的 system 段。 */
    fun buildPlanSystemPrompt(): String = PLAN_SYSTEM_PROMPT

    /** 补充动作建议的 system 段。 */
    fun buildSuggestSystemPrompt(): String = SUGGEST_SYSTEM_PROMPT

    /**
     * 一周计划的 user 段：档案 + 动作库（仅 id/名称/分类/肌群）+ 现有计划（**非手改槽位**）+ 近次完成情况。
     *
     * 手改行（含软删除行）**不出现在提示词里** —— 它们由 UseCase 层二次过滤兜底，
     * 不该给模型"可以改这里"的暗示。
     */
    fun buildPlanUserPrompt(
        profile: UserProfile,
        library: List<Exercise>,
        existing: List<WeekPlan>,
        history: List<ExerciseProgress>,
    ): String {
        // 用显式 serializer 的重载：载荷 DTO 是文件私有类型，不能走 reified 泛型重载。
        val payload = PlanContextPayload(
            profile = ProfilePayload(
                gender = profile.gender?.name,
                age = profile.age,
                heightCm = profile.heightCm,
                goal = profile.goal.name,
                equipment = profile.equipment.map { it.name },
                injuryAreas = profile.injuryAreas.map { it.name },
                injuryNote = profile.injuryNote,
            ),
            library = library.map { exercise ->
                ExercisePayload(
                    id = exercise.id,
                    name = exercise.name,
                    category = exercise.category.name,
                    muscleGroups = exercise.muscleGroups,
                    defaultSets = exercise.defaultSets,
                    defaultReps = exercise.defaultReps,
                )
            },
            existing = existing
                .filter { !it.isUserEdited }
                .map { row ->
                    PlanRowPayload(
                        dayOfWeek = row.dayOfWeek,
                        exerciseId = row.exerciseId,
                        targetSets = row.targetSets,
                        targetReps = row.targetReps,
                        targetWeightKg = row.targetWeightKg,
                    )
                },
            history = history.map { progress ->
                HistoryPayload(
                    exerciseId = progress.exerciseId,
                    lastSetsCompleted = progress.lastSetsCompleted,
                    lastTargetSets = progress.lastTargetSets,
                    lastRpe = progress.lastRpe,
                    lastWeightKg = progress.lastWeightKg,
                )
            },
        )
        return planJson.encodeToString(PlanContextPayload.serializer(), payload)
    }

    /** 补充动作建议的 user 段：档案 + 候选池名单（**只能从中选择**）。 */
    fun buildSuggestUserPrompt(profile: UserProfile, candidates: List<Exercise>): String {
        val payload = SuggestContextPayload(
            profile = ProfilePayload(
                gender = profile.gender?.name,
                age = profile.age,
                heightCm = profile.heightCm,
                goal = profile.goal.name,
                equipment = profile.equipment.map { it.name },
                injuryAreas = profile.injuryAreas.map { it.name },
                injuryNote = profile.injuryNote,
            ),
            candidates = candidates.map { exercise ->
                ExercisePayload(
                    id = exercise.id,
                    name = exercise.name,
                    category = exercise.category.name,
                    muscleGroups = exercise.muscleGroups,
                    defaultSets = exercise.defaultSets,
                    defaultReps = exercise.defaultReps,
                )
            },
        )
        return suggestJson.encodeToString(SuggestContextPayload.serializer(), payload)
    }

    // ---------------- 载荷 DTO（内部私有） ----------------

    @Serializable
    private data class PlanContextPayload(
        val profile: ProfilePayload,
        val library: List<ExercisePayload>,
        val existing: List<PlanRowPayload>,
        val history: List<HistoryPayload>,
    )

    @Serializable
    private data class SuggestContextPayload(
        val profile: ProfilePayload,
        val candidates: List<ExercisePayload>,
    )

    @Serializable
    private data class ProfilePayload(
        val gender: String?,
        val age: Int?,
        val heightCm: Int?,
        val goal: String,
        val equipment: List<String>,
        val injuryAreas: List<String>,
        val injuryNote: String?,
    )

    @Serializable
    private data class ExercisePayload(
        val id: Long,
        val name: String,
        val category: String,
        val muscleGroups: List<String>,
        val defaultSets: Int?,
        val defaultReps: Int?,
    )

    @Serializable
    private data class PlanRowPayload(
        val dayOfWeek: Int,
        val exerciseId: Long,
        val targetSets: Int,
        val targetReps: Int,
        val targetWeightKg: Float?,
    )

    @Serializable
    private data class HistoryPayload(
        val exerciseId: Long,
        val lastSetsCompleted: Int,
        /** `null` = 该次打卡未关联计划，无可信目标（修复 C4）；不得让模型据此判定"做满"。 */
        val lastTargetSets: Int? = null,
        val lastRpe: Int?,
        val lastWeightKg: Float?,
    )

    // ---------------- 提示词常量 ----------------

    private const val PLAN_SYSTEM_PROMPT: String = """
你是一名专业的健身教练，根据用户的身体档案、可用器械、伤病部位与既有动作库，为用户安排一周训练计划。

硬性规则：
1. 只输出一个 JSON 对象（不要使用 Markdown 代码块围栏）；允许在 JSON 顶层附带一个 `analysis` 字段（自然语言分析，见第 8 条）。
2. exerciseId 只能从"动作库"里给出的 id 中选择，禁止使用列表之外的 id，禁止编造 id。
3. 用户伤病的部位必须避开其对应肌群（如膝伤避开腿部/臀腿/全身类动作）。
4. 每周安排 3 个训练日，优先周一(1)、周三(3)、周五(5)；dayOfWeek 取值 1..7（1=周一，7=周日）。
5. focus 只能取：FULL_BODY / LOWER_BODY / UPPER_PUSH / UPPER_PULL / CARDIO_CORE。
6. targetSets 为 1..31 的整数（31 是逐组打卡位图的上限），targetReps 为 1..100 的整数；自重动作 targetWeightKg 填 null。
7. 优先使用用户做过的动作并参考 history：上次做满且 RPE<=6 可小幅加重（约 +2.5kg），否则维持。
8. analysis：1~3 句简体中文，说明这份计划如何结合用户的身体档案（性别/年龄/身高/目标/伤病/可用器械）与近次完成情况来安排训练日、训练重点与动作，让用户理解"为什么这样练"；若档案信息不足，说明"已用基础目标值，练几次后会自动进阶"。

输出格式：
{"analysis":"结合你的增肌目标与上周深蹲做满且强度有余量，本周安排周一全身、周三下肢、周五上肢推，并对深蹲小幅加重。","days":[{"dayOfWeek":1,"focus":"FULL_BODY","items":[{"exerciseId":12,"targetSets":3,"targetReps":12,"targetWeightKg":20.0}]}]}
"""

    private const val SUGGEST_SYSTEM_PROMPT: String = """
你是一名专业的健身教练，从给定的候选动作池中，为用户挑选适合补充进动作库的动作。

硬性规则：
1. 只输出一个 JSON 对象，不要输出任何解释文字、不要使用 Markdown 代码块围栏。
2. name 只能从"候选池"里给出的名称中选择（逐字一致），禁止编造名称。
3. 用户伤病的部位必须避开其对应肌群；用户没有的器械所要求的动作不要选。
4. 最多推荐 5 个；reason 只能取：INJURY_SWAP / EQUIPMENT_FIT / GOAL_SUPPORT。

输出格式：
{"suggestions":[{"name":"动作名","reason":"GOAL_SUPPORT"}]}
"""

    private val planJson: Json = Json { encodeDefaults = true }
    private val suggestJson: Json = Json { encodeDefaults = true }
}
