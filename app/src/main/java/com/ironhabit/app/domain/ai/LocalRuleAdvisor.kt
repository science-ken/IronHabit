package com.ironhabit.app.domain.ai

import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.PlanBasisItem
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.ExerciseSuggestion
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.MuscleGroup
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
import kotlinx.datetime.LocalDate

/**
 * 本地规则引擎（**纯函数集合**）：零 Android / 零 IO / 零网络 / 零随机。
 *
 * 同输入必同输出 → JVM 单测可完整覆盖（无需设备），与既有 `StreakCalculator` 同构。
 *
 * ## 本版的两块"知识表"
 * - [MuscleGroup]：**肌群标签**数据词汇（唯一真源，与内置动作 `muscleGroups` 的列值一致；属**数据**，非文案）；
 * - [INJURY_AGGRAVATED_TAGS] / [FOCUS_TAGS]：伤病避让与训练重点的机械映射。
 *
 * ⚠️ 上述标签是**数据匹配用**（属 `BuiltInExercises` 那类预置数据，架构 §7.5 允许写在 Kotlin 中的唯一例外）；
 * **所有面向用户的文案**一律不在此出现，由 UI 层经 `strings.xml` 映射。
 *
 * ## 器械约束的精度（v7 起分两档）
 * v6 及以前实体层没有"动作所需器械"字段，器械约束只能按**分类**猜（`STRENGTH` 需至少一件力量器械）。
 * v7 起 [Exercise.equipment] 存在且内置动作全量标注，[equipmentAllowed] 优先按标注判定；
 * **未标注**的行（用户自建、老备份恢复）继续走旧的分类判据 —— 所以本引擎对内置库是精确的，
 * 对自建动作仍是粗粒度的。
 */
object LocalRuleAdvisor : PlanAdvisor {

    override val source: AdviceSource = AdviceSource.LOCAL_RULES

    /**
     * 每周训练天数**默认值**（天）。
     *
     * P1 起不再"钉死"：实际天数读 `UserProfile.trainingDaysPerWeek`（`3–6`）。
     * 保留这个常量是因为它同时是**默认值**与老行为的锚点：
     * 没设过天数的用户 → 仍是 3 天 → 升级前排出来的计划一模一样。
     */
    const val DEFAULT_TRAINING_DAYS: Int = ProfileLimits.DEFAULT_TRAINING_DAYS_PER_WEEK

    /**
     * 「每周训练天数 → 训练日集合」（周一为 `1` … 周日为 `7`）。
     *
     * 设计取舍（确定性 + 可解释）：
     * - **3 天 = 周一/周三/周五**（与 P1 之前完全一致，老用户无感）；
     * - **4 天 = 一/二/四/五**（上下肢各连排两天，中间留周三恢复）；
     * - **5 天 = 一/二/三/五/六**；**6 天 = 一~六**（周日恒为休息日）；
     * - 天数越多越密 —— 但"不在连续两天练同一肌群"由 [focusSchedule] 的**相邻重点不相交**规则保证。
     */
    internal val TRAINING_DAY_SETS: Map<Int, List<Int>> = mapOf(
        3 to listOf(1, 3, 5),
        4 to listOf(1, 2, 4, 5),
        5 to listOf(1, 2, 3, 5, 6),
        6 to listOf(1, 2, 3, 4, 5, 6),
    )

    /** 每天最多排入的动作数（缺省值；实际由 [LoadPolicy.itemsPerDay] 决定）。 */
    private const val ITEMS_PER_DAY: Int = ProfileLoadPolicy.DEFAULT_ITEMS_PER_DAY

    /** 缺省目标组数（动作未给默认值时）。 */
    private const val DEFAULT_SETS: Int = 3

    /** 缺省目标每组次数（动作未给默认值时）。 */
    private const val DEFAULT_REPS: Int = 12

    /** 目标合法域下界（防御脏数据，避免产出 0 组 / 0 次的荒谬值）。 */
    private const val MIN_SETS: Int = 1

    private const val MIN_REPS: Int = 1

    /** 自重动作（无重量）时的渐进超负荷组数步长。 */
    private const val OVERLOAD_SET_STEP: Int = 1

    /** "有余量"的 RPE 上限（`≤ 6` → 可加重）。 */
    private const val RPE_EASY_MAX: Int = 6

    /** "已经吃力"的 RPE 上限（`7..8` → 维持）。 */
    private const val RPE_HARD_MAX: Int = 8

    /**
     * 训练重点**轮换池**（每周从中顺延取 [ROTATION_PER_WEEK] 个，再恒补一个 [TrainingFocus.UPPER_PULL]）。
     *
     * ⚠️ **修复 C1**：旧实现用 `FOCUS_BY_INDEX[index % size]`（一个 5 元素固定表）给每天取重点，
     * 而每周只排 [DEFAULT_TRAINING_DAYS] = 3 天、`index` 只到 `0/1/2` ——
     * 于是下标 3 的"背（[TrainingFocus.UPPER_PULL]）"与下标 4 的"有氧 + 核心（[TrainingFocus.CARDIO_CORE]）"
     * **永远排不到**。现改为"轮换池 + 每周必含背日"，保证一周内**必然**练到背与有氧/核心，
     * 且不同周的重点按 [weekIndex] 轮换（同一周内重复生成结果稳定 → 幂等）。
     */
    private val FOCUS_ROTATION_POOL: List<TrainingFocus> = listOf(
        TrainingFocus.LOWER_BODY,
        TrainingFocus.UPPER_PUSH,
        TrainingFocus.FULL_BODY,
        TrainingFocus.CARDIO_CORE,
    )

    /** 每周从 [FOCUS_ROTATION_POOL] 轮换取几个重点（其余一个位置恒留给 UPPER_PULL）。 */
    private const val ROTATION_PER_WEEK: Int = 2

    /** 轮换**相位**：对齐周的起始位置（纯常量；保证同一周稳定、跨周推进一格）。 */
    private const val ROTATION_PHASE: Int = 1

    /**
     * 视为"力量训练所需"的器械集合（**仅在动作未标注 [Exercise.equipment] 时**用作回落判据）。
     *
     * 不含 [Equipment.YOGA_MAT] / [Equipment.TREADMILL]：前者不是力量门槛，后者属于有氧器材。
     */
    private val STRENGTH_GEAR: Set<Equipment> = setOf(
        Equipment.DUMBBELL,
        Equipment.BARBELL,
        Equipment.MACHINE,
        Equipment.RESISTANCE_BAND,
        Equipment.PULLUP_BAR,
        Equipment.CABLE,
    )

    // ------------------------------------------------------------------
    // 数据词汇：肌群标签见 `domain/model/MuscleGroup.kt`（唯一真源，本文件只引用不复制）
    // ------------------------------------------------------------------

    /** 伤病部位 → **会被刺激到**的肌群标签（用于机械排除）。 */
    private val INJURY_AGGRAVATED_TAGS: Map<InjuryArea, Set<String>> = mapOf(
        InjuryArea.KNEE to setOf(MuscleGroup.LEG, MuscleGroup.GLUTE_LEG, MuscleGroup.FULL_BODY),
        InjuryArea.ANKLE to setOf(MuscleGroup.LEG, MuscleGroup.GLUTE_LEG, MuscleGroup.FULL_BODY),
        InjuryArea.HIP to setOf(MuscleGroup.GLUTE, MuscleGroup.GLUTE_LEG, MuscleGroup.LEG, MuscleGroup.FULL_BODY),
        InjuryArea.LOWER_BACK to setOf(MuscleGroup.BACK, MuscleGroup.HAMSTRING, MuscleGroup.FULL_BODY),
        InjuryArea.SHOULDER to setOf(
            MuscleGroup.SHOULDER,
            MuscleGroup.REAR_DELT,
            MuscleGroup.UPPER_CHEST,
            MuscleGroup.CHEST,
        ),
        InjuryArea.NECK to setOf(MuscleGroup.SHOULDER, MuscleGroup.REAR_DELT, MuscleGroup.UPPER_CHEST),
        InjuryArea.WRIST to setOf(MuscleGroup.CHEST, MuscleGroup.SHOULDER),
        InjuryArea.ELBOW to setOf(MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.BACK),
        InjuryArea.CARDIO to setOf(MuscleGroup.CARDIO, MuscleGroup.FULL_BODY),
    )

    /**
     * 训练重点 → 该重点对应的肌群标签（`internal` 便于单测直接断言"相邻两天重点不相交"）。
     *
     * ⚠️ P1 早期还有一张 `SAFE_SUBSTITUTION_TAGS`（"伤病 → 安全邻近肌群"白名单），
     * **已删除**：替代的判定改成"去掉伤病后这一天还会不会有这个动作"（见 `buildDay`），
     * 不再按肌群白名单挑。留着那张表会让下一个读代码的人以为规则还是白名单式的。
     */
    internal val FOCUS_TAGS: Map<TrainingFocus, Set<String>> = mapOf(
        TrainingFocus.FULL_BODY to setOf(MuscleGroup.FULL_BODY, MuscleGroup.CORE, MuscleGroup.CARDIO),
        TrainingFocus.LOWER_BODY to setOf(
            MuscleGroup.LEG,
            MuscleGroup.GLUTE_LEG,
            MuscleGroup.GLUTE,
            MuscleGroup.HAMSTRING,
        ),
        TrainingFocus.UPPER_PUSH to setOf(
            MuscleGroup.CHEST,
            MuscleGroup.UPPER_CHEST,
            MuscleGroup.SHOULDER,
            MuscleGroup.TRICEPS,
        ),
        TrainingFocus.UPPER_PULL to setOf(MuscleGroup.BACK, MuscleGroup.REAR_DELT, MuscleGroup.BICEPS),
        TrainingFocus.CARDIO_CORE to setOf(MuscleGroup.CORE, MuscleGroup.ABS, MuscleGroup.CARDIO),
    )

    /** 目标 → 优先的分类（用于补充动作的排序，`0` 表示最优先）。 */
    private val GOAL_PREFERRED_CATEGORY: Map<Goal, ExerciseCategory> = mapOf(
        Goal.CUT to ExerciseCategory.CARDIO,
        Goal.SHAPE to ExerciseCategory.CARDIO,
        Goal.BULK to ExerciseCategory.STRENGTH,
        Goal.RECOMP to ExerciseCategory.STRENGTH,
        Goal.MAINTAIN to ExerciseCategory.BODYWEIGHT,
    )

    // ------------------------------------------------------------------
    // 纯函数 ①：一周训练计划草案
    // ------------------------------------------------------------------

    override fun planWeek(
        profile: UserProfile,
        library: List<Exercise>,
        existing: List<WeekPlan>,
        history: List<ExerciseProgress>,
        today: LocalDate,
        bodyWeightKg: Float?,
    ): PlanProposal {
        // 0) P1：档案 → 训练量参数（目标 / 体脂 / 体重 vs 目标体重 / 年龄 真正参与排课）。
        val policy: LoadPolicy = ProfileLoadPolicy.of(profile, bodyWeightKg)

        // 1) 手改行（含软删除行）→ 完整保留，且其「天 × 动作」槽位不再生成新条目。
        val preserved: List<WeekPlan> = existing.filter { it.isUserEdited }
        val occupiedByDay: Map<Int, Set<Long>> = preserved
            .groupBy { it.dayOfWeek }
            .mapValues { (_, rows) -> rows.map { it.exerciseId }.toSet() }

        // 2) 器械 / 伤病过滤后的可用动作池。
        val forbidden: Set<String> = aggravatedTagsFor(profile.injuryAreas)
        val eligibleWithoutInjury: List<Exercise> = library
            .filter { it.id != 0L }
            .filter { it.isActive }
            .filter { equipmentAllowed(profile, it) }
            .sortedBy { it.id }
        val eligible: List<Exercise> = eligibleWithoutInjury.filter { !it.aggravates(forbidden) }

        // 2b) P1：被伤病挡掉的动作 → 用"替代"补位（见 buildDay 里的定义级判据）。
        //     ⚠️ 诚实登记：实体层只有"肌群标签"这一层粒度（没有关节角度 / 动作风险字段），
        //     所以避让是**粗粒度**的（例如膝伤时用臀部 / 核心替代腿部），**不是医学建议**；
        //     真正个性化的避让由用户手改（`isUserEdited` 行永远优先）。

        val historyByExercise: Map<Long, ExerciseProgress> = history.associateBy { it.exerciseId }

        // 3) 逐日生成草案（先保留中间产物，便于汇总 note）。
        //    训练日数量与周内分布由档案决定（3–6 天）；训练重点由「本周轮换表」决定
        //    （见 focusSchedule）：**每周必含背日**，且**相邻两天的重点肌群不相交**。
        val trainingDays: List<Int> = trainingDays(policy)
        val schedule: List<TrainingFocus> = focusSchedule(today, trainingDays.size)
        // P1：「不连排同肌群」——按**日历顺序**逐日生成，并把"前一天实际排到的肌群"传给下一天。
        // 日历顺序很关键：链上相邻 = 日历相邻；唯一没被约束的那一对是"最后一个训练日 → 第一个训练日"，
        // 而它中间恒跨着周日（见 TRAINING_DAY_SETS：周日恒为休息日）→ 天然满足 ≥48 小时。
        var previousDayGroups: Set<String> = emptySet()
        val drafts: MutableList<DayDraft> = ArrayList(trainingDays.size)
        trainingDays.forEachIndexed { index, dayOfWeek ->
            val draft: DayDraft = buildDay(
                dayOfWeek = dayOfWeek,
                focus = schedule[index % schedule.size],
                eligible = eligible,
                eligibleWithoutInjury = eligibleWithoutInjury,
                blockedIds = occupiedByDay[dayOfWeek].orEmpty(),
                historyByExercise = historyByExercise,
                policy = policy,
                bannedGroups = previousDayGroups,
                forbiddenTags = forbidden,
                // P1：有氧配额 —— 前 `cardioPerWeek` 天各保证 1 个有氧动作（每天至多 1 个，
                // 这样既满足"每周至少 N 个有氧"，又不会同一天里出现两个同肌群的有氧）。
                cardioQuotaForThisDay = if (index < policy.cardioPerWeek) 1 else 0,
            )
            previousDayGroups = groupsOf(draft, eligible)
            if (draft.day.items.isNotEmpty()) drafts += draft
        }

        val notes: List<PlanNote> = drafts.flatMap { draft -> draft.notesOf() }
        val overloadCount: Int = notes.count { it.kind == PlanReason.PROGRESSIVE_OVERLOAD }
        val substitutedCount: Int = drafts.sumOf { draft -> draft.substitutedIds.size }
        val narrowLibraryDays: Int = drafts.count { draft -> draft.repeatedGroupCount > 0 }
        val basisItems: List<PlanBasisItem> =
            buildBasis(profile, policy, overloadCount, substitutedCount, narrowLibraryDays, history)

        return PlanProposal(
            days = drafts.map { draft -> draft.day },
            preservedUserEditedIds = preserved.map { it.id },
            notes = notes,
            analysis = null,
            basis = basisItems,
        )
    }

    /** 某一天实际排到的动作，其肌群标签并集（用于"不连排同肌群"）。 */
    private fun groupsOf(draft: DayDraft, eligible: List<Exercise>): Set<String> =
        draft.day.items
            .flatMap { item ->
                eligible.firstOrNull { exercise -> exercise.id == item.exerciseId }
                    ?.muscleGroups
                    .orEmpty()
            }
            .toSet()

    /**
     * 生成「生成依据」（P1 扩写：每一条都对应一个**真的用了的**档案字段）。
     *
     * ⚠️ 参数类型契约：这些 key 走的是 `List<Any>`（实际全是 `Int`）通道，
     * 所以对应的 `strings.xml` 只能用 `%d` 占位符 —— **不能用 `%s`**
     * （见 `StringResourcePlaceholderContractTest`，历史上因 `%d` 收到 String 崩过）。
     */
    private fun buildBasis(
        profile: UserProfile,
        policy: LoadPolicy,
        overloadCount: Int,
        substitutedCount: Int,
        narrowLibraryDays: Int,
        history: List<ExerciseProgress>,
    ): List<PlanBasisItem> = buildList {
        add(PlanBasisItem(key = KEY_BASIS_FREQUENCY, args = listOf(policy.trainingDaysPerWeek)))
        add(PlanBasisItem(key = KEY_BASIS_GOAL))
        add(
            PlanBasisItem(
                key = KEY_BASIS_VOLUME,
                args = listOf(
                    policy.repsRange.first,
                    policy.repsRange.last,
                    policy.setsRange.first,
                    policy.setsRange.last,
                ),
            ),
        )
        add(PlanBasisItem(key = KEY_BASIS_CARDIO, args = listOf(policy.cardioPerWeek)))
        if (profile.gender != null && profile.age != null && profile.heightCm != null) {
            add(PlanBasisItem(key = KEY_BASIS_PROFILE))
        }
        if (PolicyReason.RECOVERY_AGE in policy.reasons && profile.age != null) {
            add(PlanBasisItem(key = KEY_BASIS_RECOVERY_AGE, args = listOf(profile.age, policy.itemsPerDay)))
        }
        if (PolicyReason.AGE_VOLUME in policy.reasons && profile.age != null) {
            add(PlanBasisItem(key = KEY_BASIS_AGE_VOLUME, args = listOf(profile.age, policy.itemsPerDay)))
        }
        when {
            PolicyReason.BODY_FAT_HIGH in policy.reasons ->
                add(PlanBasisItem(key = KEY_BASIS_BODY_FAT_HIGH, args = listOf(policy.cardioPerWeek)))

            PolicyReason.BODY_FAT_LOW in policy.reasons ->
                add(PlanBasisItem(key = KEY_BASIS_BODY_FAT_LOW, args = listOf(policy.cardioPerWeek)))
        }
        when {
            PolicyReason.WEIGHT_TO_CUT in policy.reasons ->
                add(PlanBasisItem(key = KEY_BASIS_WEIGHT_CUT, args = listOf(policy.cardioPerWeek)))

            PolicyReason.WEIGHT_TO_GAIN in policy.reasons ->
                add(PlanBasisItem(key = KEY_BASIS_WEIGHT_GAIN, args = listOf(policy.setsRange.last)))
        }
        if (profile.injuryAreas.isNotEmpty()) {
            add(PlanBasisItem(key = KEY_BASIS_INJURY))
            if (substitutedCount > 0) {
                add(PlanBasisItem(key = KEY_BASIS_INJURY_SWAP, args = listOf(substitutedCount)))
            }
        }
        if (profile.equipment.isNotEmpty()) {
            add(PlanBasisItem(key = KEY_BASIS_EQUIPMENT))
        }
        // 可用动作不够（伤病 + 器械过滤后主肌群种类 < 每天动作数）→ **如实说出来**：
        // "这天不得不排两个同肌群动作"是用户该知道的事，不该让规则在这里静默。
        if (narrowLibraryDays > 0) {
            add(PlanBasisItem(key = KEY_BASIS_LIBRARY_TOO_NARROW, args = listOf(narrowLibraryDays)))
        }
        if (overloadCount > 0) {
            add(PlanBasisItem(key = KEY_BASIS_OVERLOAD, args = listOf(overloadCount)))
        } else if (history.isEmpty()) {
            add(PlanBasisItem(key = KEY_BASIS_HISTORY_NONE))
        }
    }

    /** 生成单日草案（内部用：携带 item 级别的 note 详情以便汇总）。 */
    private fun buildDay(
        dayOfWeek: Int,
        focus: TrainingFocus,
        eligible: List<Exercise>,
        /** 同一批动作，但**没做伤病过滤**（只做器械过滤）：用来判断"哪些动作是因为伤病才进来的"。 */
        eligibleWithoutInjury: List<Exercise>,
        blockedIds: Set<Long>,
        historyByExercise: Map<Long, ExerciseProgress>,
        policy: LoadPolicy,
        bannedGroups: Set<String>,
        forbiddenTags: Set<String>,
        cardioQuotaForThisDay: Int,
    ): DayDraft {
        val focusTags: Set<String> = FOCUS_TAGS[focus].orEmpty()
        val usable: List<Exercise> = eligible.filter { it.id !in blockedIds }

        // 优先命中该训练重点的肌群（[matching]）；若一个都没命中，则整批交由 [usable] 兜底补足。
        val matching: List<Exercise> = usable.filter { it.muscleGroups.any { tag -> tag in focusTags } }

        // 修复 C1（单日去同质化）+ 修复 D2（跨重点补足）+ P1（不连排同肌群）+ P1 有氧配额：
        // 抽成 [pickForDay] —— **替代判定的基线也必须走同一条路径**，
        // 否则"有氧配额"这类后置步骤会让基线少一个动作，凭空多出一堆"替代"。
        val selected: List<Exercise> = pickForDay(
            usable = usable,
            focusTags = focusTags,
            policy = policy,
            bannedGroups = bannedGroups,
            cardioQuotaForThisDay = cardioQuotaForThisDay,
        )

        // P1-② 伤病替代：**"没有伤病时这一天会排什么"也算一遍，两次之差才是替代。**
        //
        // ⚠️ 旧实现用的是「这一天有**任意一个**重点标签被禁忌命中 → 就把所有安全肌群动作标成替代」。
        // 那条判据会**编理由**：肩伤的禁忌里有"后肩"，而背日（UPPER_PULL）的标签是
        // {背部, 后肩, 肱二头肌} —— 于是肩伤用户的背日里，正常的背部动作（如「超人式」）被标成
        // "因避让肩伤而替换"，可它本来就是背日该练的东西。
        //（这个缺陷由另一方 agent 的对抗性复核在 16 周 × 每周的样本上发现，见 REVIEW-p1p2.md F-1。）
        //
        // 现在的判据是**定义级**的：一个动作只有在"去掉伤病后就不会出现在这一天"时才算替代。
        val substitutedIds: Set<Long> = if (forbiddenTags.isEmpty()) {
            emptySet()
        } else {
            val baselineIds: Set<Long> = pickForDay(
                usable = eligibleWithoutInjury.filter { it.id !in blockedIds },
                focusTags = focusTags,
                policy = policy,
                bannedGroups = bannedGroups,
                cardioQuotaForThisDay = cardioQuotaForThisDay,
            ).mapTo(LinkedHashSet()) { exercise -> exercise.id }
            selected
                .map { exercise -> exercise.id }
                .filterNot { id -> id in baselineIds }
                .toCollection(LinkedHashSet())
        }

        val items: List<Pair<PlanItemDraft, PlanNoteDetail>> =
            selected.mapIndexed { itemIndex, exercise ->
                // P1：基础组次**由档案推导**（`LoadPolicy`），再被动作自身的默认值夹在区间内。
                val baseSets: Int = (exercise.defaultSets ?: policy.setsRange.first)
                    .coerceIn(policy.setsRange.first, policy.setsRange.last)
                val targetReps: Int = (exercise.defaultReps ?: policy.repsRange.first)
                    .coerceIn(policy.repsRange.first, policy.repsRange.last)
                val decision: LoadDecision = decideLoad(
                    exercise = exercise,
                    progress = historyByExercise[exercise.id],
                    baseSets = baseSets,
                    weightStepKg = policy.weightStepKg,
                )
                // 理由优先级：超负荷/维持判定 > 「因避让伤病而排的替代」> 位置（主项/辅助）。
                val reason: PlanReason = decision.reason
                    ?: if (exercise.id in substitutedIds) PlanReason.INJURY_SAFE else baseReason(itemIndex, exercise)
                val item = PlanItemDraft(
                    exerciseId = exercise.id,
                    targetSets = decision.targetSets,
                    targetReps = targetReps,
                    targetWeightKg = decision.targetWeightKg,
                    // 修复 C3：有氧动作的时长从「默认时长（秒）」换算成分钟带入生成链路（秒→分，<1 分记 null）。
                    targetDurationMin = exercise.defaultDurationSec?.let { sec -> sec / SECONDS_PER_MINUTE }
                        ?.takeIf { minutes -> minutes >= MIN_DURATION_MIN },
                    reason = reason,
                )
                item to decision.detail
            }

        return DayDraft(
            day = PlannedDay(dayOfWeek = dayOfWeek, focus = focus, items = items.map { it.first }),
            notePairs = items.map { (item, detail) -> item to detail },
            substitutedIds = substitutedIds,
            // 同一天里重复出现的"主肌群"个数（>0 = 可用动作不够，是被迫重复，不是规则想这样排）。
            repeatedGroupCount = selected.size - selected.mapTo(HashSet()) { groupKey(it) }.size,
        )
    }

    /**
     * 「这一天排哪些动作」的**唯一**实现（选动作 + 有氧配额）。
     *
     * 抽出来的原因：伤病替代的判定要"把同一天按不做伤病过滤再算一遍"，
     * 而两边**必须走完全相同的路径** —— 否则后置步骤（有氧配额）会让基线少一个动作，
     * 于是凭空多出一堆"替代"（真机回归：定义级断言在周1 多标了一个 id）。
     */
    private fun pickForDay(
        usable: List<Exercise>,
        focusTags: Set<String>,
        policy: LoadPolicy,
        bannedGroups: Set<String>,
        cardioQuotaForThisDay: Int,
    ): List<Exercise> {
        val matching: List<Exercise> = usable.filter { exercise ->
            exercise.muscleGroups.any { tag -> tag in focusTags }
        }
        val selected: MutableList<Exercise> = selectForDay(
            primary = matching,
            fallback = usable,
            limit = policy.itemsPerDay,
            bannedGroups = bannedGroups,
        ).toMutableList()

        // P1-① 有氧配额：这一天若还没排到有氧，用有氧动作补/换一个（当天至多一个）。
        // ⚠️ 这里是唯一**允许**"连续两天都有有氧"的地方：有氧不是需要 48 小时恢复的力量训练，
        // 而"每周至少 N 个有氧"是档案推出来的硬配额 —— 配额优先于"不连排同肌群"。
        if (cardioQuotaForThisDay > 0 && selected.none { it.category == ExerciseCategory.CARDIO }) {
            val seenGroups: Set<String> = selected.mapTo(HashSet()) { groupKey(it) }
            fun isUsableCardio(exercise: Exercise, avoidBanned: Boolean): Boolean =
                exercise.category == ExerciseCategory.CARDIO &&
                    groupKey(exercise) !in seenGroups &&
                    (!avoidBanned || exercise.muscleGroups.none { tag -> tag in bannedGroups })

            val candidate: Exercise? = usable.firstOrNull { isUsableCardio(it, avoidBanned = true) }
                ?: usable.firstOrNull { isUsableCardio(it, avoidBanned = false) }
            if (candidate != null) {
                if (selected.size < policy.itemsPerDay) {
                    selected += candidate
                } else if (selected.isNotEmpty()) {
                    // 满员：换掉最后一条（总数不变、仍不超上限）。
                    selected[selected.lastIndex] = candidate
                }
            }
        }
        return selected
    }

    /** 无历史数据时按位置给理由：首位 = 主项；力量动作 = 器械匹配；其余 = 辅助。 */
    private fun baseReason(itemIndex: Int, exercise: Exercise): PlanReason = when {
        itemIndex == 0 -> PlanReason.PRIMARY_LIFT
        exercise.category == ExerciseCategory.STRENGTH -> PlanReason.EQUIPMENT_MATCHED
        else -> PlanReason.SUPPLEMENT
    }

    /**
     * 渐进超负荷判定（照 `docs/ai-coach-local.md` §4.4 表格，数值为 pdf 常量）。
     *
     * | 条件 | 动作 | 理由 |
     * |------|------|------|
     * | 组数做满 且 RPE ≤ 6 | 重量 +2.5kg（自重则 +1 组） | [PlanReason.PROGRESSIVE_OVERLOAD] |
     * | 组数做满 且 RPE 7–8 | 维持 | [PlanReason.MAINTAIN] |
     * | 组数做满 且 RPE ≥ 9 | 维持（表格外的保守处理） | [PlanReason.MAINTAIN] |
     * | 组数没做满 | 维持 | [PlanReason.MAINTAIN] |
     * | 无 RPE 但组数做满 | **待评级（不猜）** → 维持 | [PlanReason.MAINTAIN] |
     * | 无历史记录 | 不改（由位置给理由） | `null` |
     */
    private fun decideLoad(
        exercise: Exercise,
        progress: ExerciseProgress?,
        baseSets: Int,
        weightStepKg: Float,
    ): LoadDecision {
        // 无历史 → 不做任何超负荷推断（"不猜"是设计原则）。
        if (progress == null) {
            return LoadDecision(
                targetSets = baseSets,
                targetWeightKg = null,
                reason = null,
                detail = PlanNoteDetail.None,
            )
        }

        // 修复 C4：上次**没有关联计划**（`lastTargetSets == null`）→ **不能**假定"做满"。
        // 旧实现用兜底常量 3 顶替缺失的目标组数，只要完成数 ≥ 3 就误判"做满"，
        // 再叠加"有余量"就凭空加重 2.5kg。现改为：缺失判据 = 未做满 → 维持（不猜）。
        val completedAll: Boolean = progress.lastTargetSets?.let { target ->
            progress.lastSetsCompleted >= target
        } ?: false
        val previousWeight: Float? = progress.lastWeightKg
        val rpe: Int? = progress.lastRpe

        if (!completedAll) {
            return LoadDecision(
                targetSets = baseSets,
                targetWeightKg = previousWeight,
                reason = PlanReason.MAINTAIN,
                detail = PlanNoteDetail.None,
            )
        }

        val canOverload: Boolean = rpe != null && rpe <= RPE_EASY_MAX
        if (!canOverload) {
            // ① 无 RPE（待评级，不猜）② RPE 在 7..8（已吃力）③ RPE ≥ 9 → 一律维持。
            // （表格只覆盖到 8；≥ 9 属表格外情形，保守维持，绝不加重。）
            return LoadDecision(
                targetSets = baseSets,
                targetWeightKg = previousWeight,
                reason = PlanReason.MAINTAIN,
                detail = PlanNoteDetail.None,
            )
        }

        // 组数做满 + RPE ≤ 6 → 有余量，加重（自重动作则加组）。
        // P1：加重步长由档案决定（`LoadPolicy.weightStepKg`：增肌 2.5kg / 减脂塑形 1.25kg）。
        if (previousWeight != null) {
            val newWeight: Float = previousWeight + weightStepKg
            return LoadDecision(
                targetSets = baseSets,
                targetWeightKg = newWeight,
                reason = PlanReason.PROGRESSIVE_OVERLOAD,
                detail = PlanNoteDetail.WeightDelta(
                    oldWeightKg = previousWeight,
                    newWeightKg = newWeight,
                ),
            )
        }
        val newSets: Int = baseSets + OVERLOAD_SET_STEP
        return LoadDecision(
            targetSets = newSets,
            targetWeightKg = null,
            reason = PlanReason.PROGRESSIVE_OVERLOAD,
            detail = PlanNoteDetail.SetsDelta(oldSets = baseSets, newSets = newSets),
        )
    }

    /**
     * 本周的训练日：由档案的 [LoadPolicy.trainingDaysPerWeek] 决定**哪几天**（见 [TRAINING_DAY_SETS]），
     * 按**日历顺序**（周一 → 周日）返回。
     *
     * 集合与顺序都**不含"今天"** → 同一周内重复生成结果完全一致（幂等，不会攒垃圾行）。
     *
     * ⚠️ P1 之前这里是"从今天起向内排布"（今天周四 → 周五/周一/周三）：顺序只用来决定各天拿到哪个
     * 训练重点。改成日历顺序是因为"不连排同肌群"要沿**日历相邻**的日子传递约束 ——
     * 从今天起排的链在链尾/链首处会跨过日历相邻的那一对，导致周五/周六仍可能撞同一块肌群。
     * 日历顺序下链上相邻恒等于日历相邻，唯一未约束的"最后一个训练日 → 第一个训练日"中间恒跨周日。
     */
    private fun trainingDays(policy: LoadPolicy): List<Int> =
        trainingDaysOf(policy.trainingDaysPerWeek).sorted()

    /** 天数 → 训练日集合（越界天数先钳制到 `3–6`，未知天数回落默认 3 天）。 */
    internal fun trainingDaysOf(days: Int): List<Int> =
        TRAINING_DAY_SETS[ProfileLimits.coerceTrainingDaysPerWeek(days)]
            ?: TRAINING_DAY_SETS.getValue(DEFAULT_TRAINING_DAYS)

    /**
     * 本周的训练重点（**修复 C1** + P1 扩展到 3–6 天）。
     *
     * 前 3 个与 P1 之前**完全一致**：从 [FOCUS_ROTATION_POOL] 按 [weekIndex] 顺延取
     * [ROTATION_PER_WEEK] 个，再恒补一个 [TrainingFocus.UPPER_PULL]（背日）。
     * 天数 > 3 时继续从轮换池里取，**跳过与前一天有共同肌群标签的重点**
     * （这就是"不连排同肌群"在规则层的落点：连着的两天练不到同一块）。
     *
     * - `UPPER_PULL` 永远在表中（保证"背"排得到）；
     * - [weekIndex] 只由 [today] 所在周决定 → **同一周内重复生成得到同一套重点**（幂等），跨周则轮换。
     */
    private fun focusSchedule(today: LocalDate, days: Int): List<TrainingFocus> {
        val pool: List<TrainingFocus> = FOCUS_ROTATION_POOL
        val start: Int = weekIndex(today) + ROTATION_PHASE
        // ⚠️ 轮换池内部也要做相邻相交检查：池子顺序是
        // [LOWER_BODY, UPPER_PUSH, FULL_BODY, CARDIO_CORE]，其中 **FULL_BODY 与 CARDIO_CORE 的标签相交**
        // （都含"核心/有氧"）。旧实现只对"天数 > 3 追加的重点"查相交，前两个直接取相邻两项 ——
        // 于是某些周号下会排出「周一 FULL_BODY / 周二 CARDIO_CORE」这种相邻两天同肌群的组合。
        // （该缺陷由另一方 agent 的对抗性复核精确定位，见 REVIEW-p1p2.md F-4。）
        val rotating: List<TrainingFocus> = buildList {
            var step: Int = 0
            while (size < ROTATION_PER_WEEK && step < FOCUS_SEARCH_GUARD) {
                val candidate: TrainingFocus = pool[Math.floorMod(start + step, pool.size)]
                if (isEmpty() || sharesNoMuscleTag(last(), candidate)) add(candidate)
                step++
            }
        }
        val base: List<TrainingFocus> = rotating + TrainingFocus.UPPER_PULL
        if (days <= base.size) return base.take(days)

        val result: MutableList<TrainingFocus> = base.toMutableList()
        var step: Int = ROTATION_PER_WEEK
        var guard: Int = 0
        while (result.size < days && guard < FOCUS_SEARCH_GUARD) {
            val candidate: TrainingFocus = pool[Math.floorMod(start + step, pool.size)]
            if (sharesNoMuscleTag(result.last(), candidate)) result += candidate
            step += 1
            guard += 1
        }
        // 兜底：把轮换池按序补满（保证输出长度 == 天数，绝不因为找不到"不相交重点"就少排一天）。
        while (result.size < days) {
            val candidate: TrainingFocus = pool[Math.floorMod(start + step, pool.size)]
            result += candidate
            step += 1
        }
        return result
    }

    /** 两个训练重点的肌群标签**完全不相交**（= 连着两天不会练到同一块）。 */
    private fun sharesNoMuscleTag(a: TrainingFocus, b: TrainingFocus): Boolean =
        FOCUS_TAGS[a].orEmpty().intersect(FOCUS_TAGS[b].orEmpty()).isEmpty()

    /** [focusSchedule] 的搜索上限（轮换池只有 4 个重点，避免任何形式的死循环）。 */
    private const val FOCUS_SEARCH_GUARD: Int = 64

    /**
     * 以**周一为桶首**的周序号（同周内恒定、跨周 +1）。
     *
     * epochDay `0` = 1970-01-01（周四）→ 该周的周一是 `-3`；故 `epochDay + 3` 后整除 7
     * 即得以周一为界、每 7 天 +1 的稳定周号（用 `Math.floorDiv` 正确处理负的 epochDay）。
     */
    private fun weekIndex(today: LocalDate): Int =
        Math.floorDiv(today.toEpochDays() + MONDAY_ALIGN_OFFSET, WEEK_DAYS)

    /**
     * 从 [primary]（当日训练重点**命中**的动作）优先挑选，不足时**跨重点**从 [fallback]
     * （当日**全部可用**动作）补足，且**同一主肌群每天至多出现一次**。
     *
     * 规则（确定性、零随机；输出顺序 = 入参顺序）：
     * 1. 先遍历 [primary]，每个**尚未出现过的主肌群**取一个（训练重点优先）；
     * 2. 不足 [limit] 时遍历 [fallback]，仍按「一主肌群一个」补足 —— 这一步即 **D2 的跨重点补足**：
     *    例如背日只有「背 / 后肩 / 肱二头肌」3 个可匹配肌群时，第 4 个名额改由跨重点池里的
     *    （如「胸」）动作补上，而**不是**把「背」重复排第二次（旧实现正是在此同质化）；
     *    P1 起这一步还会**跳过 [bannedGroups]**（前一天刚练过的肌群）→ 不连排同肌群；
     * 3. **退化兜底**：仅当 [fallback] 里的主肌群种类本就少于 [limit] 时，才按序补齐剩余名额
     *    （**允许重复肌群**）—— 否则会把一天排空。调用方会用
     *    [DayDraft.repeatedGroupCount] 把这种情况**如实报出来**（生成依据 `basis_library_too_narrow`）。
     *
     *    ⚠️ 旧 KDoc 声称"真实内置库有 12–15 种主肌群，第 3 步**永不触发**"—— **这句是错的**：
     *    可用动作池是**伤病 + 器械过滤之后**的池子，极端情况下（例如 9 处伤病全选）可能只剩
     *    「核心 / 腹部」两个主肌群，第 3 步必然触发。该缺陷由另一方 agent 的对抗性复核发现
     *    （见 REVIEW-p1p2.md F-2）。
     *
     * 主肌群为空的动作按**动作名**各自独立（不参与「同肌群去重」），避免误合并无标签动作。
     *
     * @param bannedGroups 前一天已排过的肌群标签。P1 起它是**次级优先**（不是硬排除）：
     *   先挑"没和昨天撞"的动作，凑不满再放宽 —— 因为"不排空一天"和"单日不重复主肌群"
     *   这两条规矩比"不连排同肌群"更硬（退化库只有一两个肌群时，硬排除会把一天排成同质或排空）。
     */
    private fun selectForDay(
        primary: List<Exercise>,
        fallback: List<Exercise>,
        limit: Int,
        bannedGroups: Set<String> = emptySet(),
    ): List<Exercise> {
        if (limit <= 0) return emptyList()
        val chosen: MutableList<Exercise> = ArrayList(limit)
        val seenGroups: MutableSet<String> = HashSet()
        val seenIds: MutableSet<Long> = HashSet()

        // 取一个「主肌群未出现过且该动作未选过」的动作；命中才计入。
        fun takeDistinct(exercise: Exercise, avoidBanned: Boolean) {
            if (chosen.size >= limit) return
            if (exercise.id in seenIds) return
            if (avoidBanned && exercise.muscleGroups.any { tag -> tag in bannedGroups }) return
            val group: String = groupKey(exercise)
            if (group in seenGroups) return
            chosen.add(exercise)
            seenIds.add(exercise.id)
            seenGroups.add(group)
        }

        // 1) 训练重点命中优先（先避开昨天练过的肌群）。
        for (exercise in primary) takeDistinct(exercise, avoidBanned = true)
        // 2) 跨重点补足（仍是「一主肌群一个」；同样先避开昨天的肌群）。
        if (chosen.size < limit) {
            for (exercise in fallback) takeDistinct(exercise, avoidBanned = true)
        }
        // 3) 放宽 bannedGroups 再来一轮 —— 顺序很重要：**先**把"没和昨天撞"的整池（重点 + 跨重点）
        //    都用完，**再**放宽；否则同一个池子会被提前放宽，等于约束没生效。
        if (chosen.size < limit) {
            for (exercise in primary) takeDistinct(exercise, avoidBanned = false)
        }
        if (chosen.size < limit) {
            for (exercise in fallback) takeDistinct(exercise, avoidBanned = false)
        }
        // 4) 退化兜底：只有当可用动作的主肌群种类 < limit 时才允许重复肌群，避免把一天排空。
        //    （这一步**不看** bannedGroups：排空一天是更严重的问题。）
        if (chosen.size < limit) {
            for (exercise in fallback) {
                if (chosen.size >= limit) break
                if (seenIds.add(exercise.id)) chosen.add(exercise)
            }
        }
        return chosen
    }

    /** 主肌群去重键：有标签用标签，无标签用动作名（各自独立，互不合并）。 */
    private fun groupKey(exercise: Exercise): String {
        val group: String? = exercise.primaryMuscleGroup?.takeIf { it.isNotBlank() }
        return group ?: "#${exercise.name.trim()}"
    }

    // ------------------------------------------------------------------
    // 纯函数 ②：补充动作建议
    // ------------------------------------------------------------------

    override fun suggestExercises(
        profile: UserProfile,
        candidates: List<Exercise>,
        existing: List<Exercise>,
    ): List<ExerciseSuggestion> {
        val existingNames: Set<String> = existing.map { it.name.trim() }.toSet()
        val forbidden: Set<String> = aggravatedTagsFor(profile.injuryAreas)

        return candidates
            .withIndex()
            .filter { (_, candidate) -> candidate.name.isNotBlank() }
            .filter { (_, candidate) -> candidate.name.trim() !in existingNames }
            .filter { (_, candidate) -> !candidate.aggravates(forbidden) }
            .filter { (_, candidate) -> equipmentAllowed(profile, candidate) }
            .map { (index, candidate) -> Pair(goalBoost(profile.goal, candidate), index) }
            .sortedWith(compareBy<Pair<Int, Int>>({ it.first }, { it.second }))
            .map { (_, index) -> candidates[index].toSuggestion(profile) }
    }

    /** 候选 → 建议（幂等键即 `name`；`noteKey` 只给资源名，不含中文）。 */
    private fun Exercise.toSuggestion(profile: UserProfile): ExerciseSuggestion {
        val reason: SuggestionReason = reasonFor(profile, this)
        return ExerciseSuggestion(
            name = name.trim(),
            category = category,
            muscleGroups = muscleGroups,
            equipment = equipment,
            defaultSets = (defaultSets ?: DEFAULT_SETS).coerceAtLeast(MIN_SETS),
            defaultReps = (defaultReps ?: DEFAULT_REPS).coerceAtLeast(MIN_REPS),
            noteKey = noteKeyFor(reason),
            reason = reason,
        )
    }

    /** 建议理由：**有伤病且该候选非力量类** → 视为低冲击替代；力量类且用户有器材 → 器械匹配；否则目标补强。 */
    private fun reasonFor(profile: UserProfile, candidate: Exercise): SuggestionReason = when {
        profile.injuryAreas.isNotEmpty() && candidate.category != ExerciseCategory.STRENGTH ->
            SuggestionReason.INJURY_SWAP
        candidate.category == ExerciseCategory.STRENGTH && equipmentAllowed(profile, candidate) ->
            SuggestionReason.EQUIPMENT_FIT
        else -> SuggestionReason.GOAL_SUPPORT
    }

    /** 理由 → `strings.xml` 的资源**名称**（UI 层据此映射文案）。 */
    private fun noteKeyFor(reason: SuggestionReason): String = when (reason) {
        SuggestionReason.INJURY_SWAP -> NOTE_KEY_INJURY_SWAP
        SuggestionReason.EQUIPMENT_FIT -> NOTE_KEY_EQUIPMENT_FIT
        SuggestionReason.GOAL_SUPPORT -> NOTE_KEY_GOAL_SUPPORT
    }

    /** 目标 → 排序权重（优先给出贴目标的那类动作，`0` 最优先）。 */
    private fun goalBoost(goal: Goal, candidate: Exercise): Int {
        val preferred: ExerciseCategory = GOAL_PREFERRED_CATEGORY[goal] ?: return 1
        return if (candidate.category == preferred) 0 else 1
    }

    // ------------------------------------------------------------------
    // 过滤原语
    // ------------------------------------------------------------------

    /** 伤病集合 → 需排除的肌群标签并集。 */
    private fun aggravatedTagsFor(injuries: Set<InjuryArea>): Set<String> =
        injuries.flatMapTo(HashSet()) { injury -> INJURY_AGGRAVATED_TAGS[injury].orEmpty() }

    /** 动作是否会刺激到伤病？任意肌群标签命中即排除（机械判定）。 */
    private fun Exercise.aggravates(forbidden: Set<String>): Boolean =
        forbidden.isNotEmpty() && muscleGroups.any { tag -> tag in forbidden }

    /** 可用器械（空集 → 视为 `{NONE}`，即仅自重）。 */
    private fun effectiveEquipment(profile: UserProfile): Set<Equipment> =
        profile.equipment.ifEmpty { setOf(Equipment.NONE) }

    /**
     * 器械可行性。
     *
     * **优先看动作自己的标注**（`Exercise.equipment`，v7 起内置动作全量标注）：标注里的每一件
     * 都必须出现在用户可用清单里（[Equipment.NONE] 恒可用）。于是「绳索下压」不会再排给只有
     * 哑铃的人，「高位下拉」也不会被当成和「哑铃卧推」等价。
     *
     * **未标注**（存量行 / 用户自建动作 / 恢复的老备份）时回落到 v2.0.8 的旧判据：只有
     * `STRENGTH` 分类要求"至少一件力量器械"，其余分类一律放行。回落是有意的 —— 把"没标注"
     * 当成"不需要器械"等于让自建动作绕过全部约束，而当成"什么器械都需要"等于让自建动作永远排不进。
     */
    private fun equipmentAllowed(profile: UserProfile, exercise: Exercise): Boolean {
        val required: Set<Equipment> = exercise.equipment.toSet()
        if (required.isNotEmpty()) {
            val owned: Set<Equipment> = effectiveEquipment(profile)
            return required.all { it == Equipment.NONE || it in owned }
        }
        if (exercise.category != ExerciseCategory.STRENGTH) return true
        return (effectiveEquipment(profile) intersect STRENGTH_GEAR).isNotEmpty()
    }

    private const val WEEK_DAYS: Int = 7

    /** epochDay `0`（1970-01-01，周四）距其所在周的周一（`-3`）的偏移；用于周一对齐的周号。 */
    private const val MONDAY_ALIGN_OFFSET: Int = 3

    /** 秒 → 分换算基数（有氧动作 `defaultDurationSec` → 计划 `targetDurationMin`）。 */
    private const val SECONDS_PER_MINUTE: Int = 60

    /** 有氧时长合法下界（分钟）；不足 1 分钟视为"无有效时长"（`null`），不写 0。 */
    private const val MIN_DURATION_MIN: Int = 1

    private const val NOTE_KEY_INJURY_SWAP: String = "note_ai_injury_swap"
    private const val NOTE_KEY_EQUIPMENT_FIT: String = "note_ai_equipment_fit"
    private const val NOTE_KEY_GOAL_SUPPORT: String = "note_ai_goal_support"

    // ---- 生成依据（basis_*）：key 即 `strings.xml` 资源名；参数一律是 Int（只能用 %d 占位符）----
    private const val KEY_BASIS_FREQUENCY: String = "basis_frequency"
    private const val KEY_BASIS_GOAL: String = "basis_goal"
    private const val KEY_BASIS_VOLUME: String = "basis_volume"
    private const val KEY_BASIS_CARDIO: String = "basis_cardio"
    private const val KEY_BASIS_PROFILE: String = "basis_profile"
    private const val KEY_BASIS_RECOVERY_AGE: String = "basis_recovery_age"
    private const val KEY_BASIS_AGE_VOLUME: String = "basis_age_volume"
    private const val KEY_BASIS_BODY_FAT_HIGH: String = "basis_bodyfat_high"
    private const val KEY_BASIS_BODY_FAT_LOW: String = "basis_bodyfat_low"
    private const val KEY_BASIS_WEIGHT_CUT: String = "basis_weight_cut"
    private const val KEY_BASIS_WEIGHT_GAIN: String = "basis_weight_gain"
    private const val KEY_BASIS_INJURY: String = "basis_injury"
    private const val KEY_BASIS_INJURY_SWAP: String = "basis_injury_swap"
    private const val KEY_BASIS_EQUIPMENT: String = "basis_equipment"
    private const val KEY_BASIS_LIBRARY_TOO_NARROW: String = "basis_library_too_narrow"
    private const val KEY_BASIS_OVERLOAD: String = "basis_overload"
    private const val KEY_BASIS_HISTORY_NONE: String = "basis_history_none"

    /** 一天的生成中间产物（携带 item → note 详情的配对）。 */
    private data class DayDraft(
        val day: PlannedDay,
        val notePairs: List<Pair<PlanItemDraft, PlanNoteDetail>>,
        /** P1：这一天里"因避让伤病而作为替代排入"的动作 id。 */
        val substitutedIds: Set<Long> = emptySet(),
        /**
         * 这一天里**被迫重复**的主肌群个数（>0 = 可用动作（伤病 + 器械过滤后）不够填满一天）。
         *
         * 用途：把"为什么今天只有这几个动作 / 怎么重复了"如实告诉用户（生成依据
         * `basis_library_too_narrow`），而不是让规则在这里静默。
         */
        val repeatedGroupCount: Int = 0,
    )

    /** `decideLoad` 的输出：目标组数 / 重量 / 理由 / note 参数。 */
    private data class LoadDecision(
        val targetSets: Int,
        val targetWeightKg: Float?,
        /** `null` = 无历史，不推断（沿用位置理由）。 */
        val reason: PlanReason?,
        val detail: PlanNoteDetail,
    )

    private fun DayDraft.notesOf(): List<PlanNote> =
        notePairs.map { (item, detail) ->
            PlanNote(kind = item.reason, exerciseId = item.exerciseId, detail = detail)
        }
}
