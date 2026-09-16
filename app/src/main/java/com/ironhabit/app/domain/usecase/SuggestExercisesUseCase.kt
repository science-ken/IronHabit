package com.ironhabit.app.domain.usecase

import com.ironhabit.app.di.IoDispatcher
import com.ironhabit.app.domain.ai.PlanAdvisor
import com.ironhabit.app.domain.model.AdoptResult
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseSource
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.SuggestionResult
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * **补充动作建议 + 一键收入**（对应预览 `AI_SUG` / `adoptAi()`，`docs/ai-coach-local.md` §4.5）。
 *
 * - [suggest]：**只读**，产出"还能收入哪些"（规则层已按 `name` 去掉库里已有的）；
 * - [adopt]：**幂等写入**，命中即跳过，`source = [ExerciseSource.AI_SUGGESTED]`。
 *
 * ## 🔒 幂等不变量（有单测）
 * 连续两次 `adopt("同一个 name")`：
 * - 第一次 → [AdoptResult.ADDED]；
 * - 第二次 → [AdoptResult.ALREADY_EXISTS]，**不产生重复行**。
 *
 * 幂等键 = `Exercise.name`（`exercises.name` 在库里 UNIQUE）。
 * **应用层"命中即跳过"优先**，数据库唯一约束只作兜底 —— 不靠抛异常来表达"已存在"。
 *
 * @param advisor 注入接口，不 new 具体实现
 */
class SuggestExercisesUseCase @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val settingsRepository: SettingsRepository,
    private val advisor: PlanAdvisor,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 只读：产出"还能收入哪些补充动作"（**带来源标注**，联网一期接口变更）。
     *
     * 顾问调用包 `withContext(ioDispatcher)`：本地 = 纯计算，远端 = 阻塞 HTTP。
     */
    suspend fun suggest(): SuggestionResult {
        val profile = settingsRepository.profile().first()
        val library = library()
        val suggestions: List<ExerciseSuggestion> = withContext(ioDispatcher) {
            advisor.suggestExercises(
                profile = profile,
                candidates = SupplementPool.entries,
                existing = library,
            )
        }
        return SuggestionResult(
            suggestions = suggestions,
            source = advisor.source,
            fallbackReason = advisor.lastFallbackReason,
        )
    }

    /**
     * 收入一条（**幂等**）。
     *
     * 判序：空名字 → 库里已有（**含已停用**）→ 不在候选池 → 才真的写入。
     * 前三种一律返回 [AdoptResult.ALREADY_EXISTS] 且**不产生任何写入**。
     */
    suspend fun adopt(name: String): AdoptResult {
        val target = name.trim()
        if (target.isEmpty()) return AdoptResult.ALREADY_EXISTS

        val profile = settingsRepository.profile().first()
        val library = library()

        // 幂等第一道闸：名字已在库里（含已停用动作）→ 命中即跳过，不重复写入。
        if (library.any { it.name.trim() == target }) return AdoptResult.ALREADY_EXISTS

        // 幂等第二道闸：不在"还能收入"的候选里（非法输入）→ 不写入。
        val suggestion: ExerciseSuggestion = withContext(ioDispatcher) {
            advisor.suggestExercises(profile, SupplementPool.entries, library)
        }.firstOrNull { it.name == target }
            ?: return AdoptResult.ALREADY_EXISTS

        exerciseRepository.upsert(
            Exercise(
                name = suggestion.name,
                category = suggestion.category,
                // 与内置 / 自定义**同等可用**：可编辑、可排计划。
                source = ExerciseSource.AI_SUGGESTED,
                muscleGroups = suggestion.muscleGroups,
                isActive = true,
                defaultSets = suggestion.defaultSets,
                defaultReps = suggestion.defaultReps,
            )
        )
        return AdoptResult.ADDED
    }

    /**
     * 用户动作库（**全量快照**）：历史停用行（如老备份恢复带入）仍在库里、仍占 `name` 唯一槽位，
     * 只看启用列表会导致"重复收入一个已存在动作"。
     */
    private suspend fun library(): List<Exercise> = exerciseRepository.getAll()
}

/**
 * 补充动作**候选池**（内置常量表，对应预览 `AI_SUG`）。
 *
 * 属**数据预置**（动作名 / 肌群标签），与 `BuiltInExercises` 同类 —— 架构 §7.5 允许直接写在 Kotlin 中。
 * 规则层只消费它的数据结构；所有面向用户的文案由 `noteKey` 指到 `strings.xml`。
 */
private object SupplementPool {

    /** 候选全集（顺序即默认展示顺序；最终排序由规则层按 `goal` 微调）。 */
    val entries: List<Exercise> = listOf(
        Exercise(
            name = "靠墙静蹲",
            category = ExerciseCategory.BODYWEIGHT,
            muscleGroups = listOf("腿部"),
            defaultSets = 3,
            defaultReps = 1,
            defaultDurationSec = 45,
        ),
        Exercise(
            name = "单腿臀桥",
            category = ExerciseCategory.BODYWEIGHT,
            muscleGroups = listOf("臀部"),
            defaultSets = 3,
            defaultReps = 12,
        ),
        Exercise(
            name = "鸟狗式",
            category = ExerciseCategory.BODYWEIGHT,
            muscleGroups = listOf("核心"),
            defaultSets = 3,
            defaultReps = 12,
        ),
        Exercise(
            name = "死虫式",
            category = ExerciseCategory.BODYWEIGHT,
            muscleGroups = listOf("腹部"),
            defaultSets = 3,
            defaultReps = 12,
        ),
        Exercise(
            name = "弹力带臀外展",
            category = ExerciseCategory.STRENGTH,
            muscleGroups = listOf("臀部"),
            defaultSets = 3,
            defaultReps = 15,
        ),
        Exercise(
            name = "弹力带面拉",
            category = ExerciseCategory.STRENGTH,
            muscleGroups = listOf("后肩"),
            defaultSets = 3,
            defaultReps = 15,
        ),
        Exercise(
            name = "哑铃肩上推举",
            category = ExerciseCategory.STRENGTH,
            muscleGroups = listOf("肩部"),
            defaultSets = 3,
            defaultReps = 10,
        ),
        Exercise(
            name = "坐姿提踵",
            category = ExerciseCategory.STRENGTH,
            muscleGroups = listOf("腿部"),
            defaultSets = 3,
            defaultReps = 20,
        ),
        Exercise(
            name = "椭圆机稳态",
            category = ExerciseCategory.CARDIO,
            muscleGroups = listOf("有氧"),
            defaultSets = 1,
            defaultReps = 1,
            defaultDurationSec = 1200,
        ),
    )
}
