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
import com.ironhabit.app.domain.model.PlanItemDraft
import com.ironhabit.app.domain.model.PlanNote
import com.ironhabit.app.domain.model.PlanNoteDetail
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.PlannedDay
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
 * - [MuscleTag]：**肌群标签**数据词汇，与内置动作 `muscleGroups` 的列值一致（属**数据**，非文案）；
 * - [INJURY_AGGRAVATED_TAGS] / [FOCUS_TAGS]：伤病避让与训练重点的机械映射。
 *
 * ⚠️ 上述标签是**数据匹配用**（属 `BuiltInExercises` 那类预置数据，架构 §7.5 允许写在 Kotlin 中的唯一例外）；
 * **所有面向用户的文案**一律不在此出现，由 UI 层经 `strings.xml` 映射。
 *
 * ## 已知精度边界（诚实登记）
 * 实体层**没有**"动作所需器械"字段，因此器械约束按**分类**判定（见 [equipmentAllowed]）：
 * `STRENGTH` 需至少一件力量器械，其余分类无需器械。将来给 `exercises` 增加器械字段后，
 * 只需替换 [equipmentAllowed] 一处，`planWeek` 签名不变。
 */
object LocalRuleAdvisor : PlanAdvisor {

    override val source: AdviceSource = AdviceSource.LOCAL_RULES

    /**
     * 每周训练天数（**设计钉死的常量**，见 `docs/ai-coach-local.md` §11 裁定 #2）。
     *
     * 本版**不做**"用户自选天数"；将来把它改为读 `UserProfile` 的一项即可（`planWeek` 签名不变）。
     */
    const val DEFAULT_TRAINING_DAYS: Int = 3

    /** 每天最多排入的动作数。 */
    private const val ITEMS_PER_DAY: Int = 4

    /** 缺省目标组数（动作未给默认值时）。 */
    private const val DEFAULT_SETS: Int = 3

    /** 缺省目标每组次数（动作未给默认值时）。 */
    private const val DEFAULT_REPS: Int = 12

    /** 目标合法域下界（防御脏数据，避免产出 0 组 / 0 次的荒谬值）。 */
    private const val MIN_SETS: Int = 1

    private const val MIN_REPS: Int = 1

    /** 渐进超负荷的重量步长（kg），对应预览 `bumpW()`。 */
    private const val OVERLOAD_WEIGHT_STEP_KG: Float = 2.5f

    /** 自重动作（无重量）时的渐进超负荷组数步长。 */
    private const val OVERLOAD_SET_STEP: Int = 1

    /** "有余量"的 RPE 上限（`≤ 6` → 可加重）。 */
    private const val RPE_EASY_MAX: Int = 6

    /** "已经吃力"的 RPE 上限（`7..8` → 维持）。 */
    private const val RPE_HARD_MAX: Int = 8

    /** 偏好的训练日（周一 / 周三 / 周五）。 */
    private val PREFERRED_TRAINING_DAYS: List<Int> = listOf(1, 3, 5)

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

    /** 视为"力量训练所需"的器械集合。 */
    private val STRENGTH_GEAR: Set<Equipment> = setOf(
        Equipment.DUMBBELL,
        Equipment.BARBELL,
        Equipment.MACHINE,
        Equipment.RESISTANCE_BAND,
        Equipment.PULLUP_BAR,
    )

    // ------------------------------------------------------------------
    // 数据词汇：肌群标签（与内置动作 muscleGroups 的列值一致）
    // ------------------------------------------------------------------

    /** 肌群标签（**数据**，非文案；集中在此便于与种子数据对齐）。 */
    private object MuscleTag {
        const val CHEST: String = "胸部"
        const val UPPER_CHEST: String = "上胸"
        const val BACK: String = "背部"
        const val REAR_DELT: String = "后肩"
        const val SHOULDER: String = "肩部"
        const val BICEPS: String = "肱二头肌"
        const val TRICEPS: String = "肱三头肌"
        const val LEG: String = "腿部"
        const val GLUTE: String = "臀部"
        const val GLUTE_LEG: String = "臀腿"
        const val HAMSTRING: String = "腿后链"
        const val CORE: String = "核心"
        const val ABS: String = "腹部"
        const val FULL_BODY: String = "全身"
        const val CARDIO: String = "有氧"
    }

    /** 伤病部位 → **会被刺激到**的肌群标签（用于机械排除）。 */
    private val INJURY_AGGRAVATED_TAGS: Map<InjuryArea, Set<String>> = mapOf(
        InjuryArea.KNEE to setOf(MuscleTag.LEG, MuscleTag.GLUTE_LEG, MuscleTag.FULL_BODY),
        InjuryArea.ANKLE to setOf(MuscleTag.LEG, MuscleTag.GLUTE_LEG, MuscleTag.FULL_BODY),
        InjuryArea.HIP to setOf(MuscleTag.GLUTE, MuscleTag.GLUTE_LEG, MuscleTag.LEG, MuscleTag.FULL_BODY),
        InjuryArea.LOWER_BACK to setOf(MuscleTag.BACK, MuscleTag.HAMSTRING, MuscleTag.FULL_BODY),
        InjuryArea.SHOULDER to setOf(
            MuscleTag.SHOULDER,
            MuscleTag.REAR_DELT,
            MuscleTag.UPPER_CHEST,
            MuscleTag.CHEST,
        ),
        InjuryArea.NECK to setOf(MuscleTag.SHOULDER, MuscleTag.REAR_DELT, MuscleTag.UPPER_CHEST),
        InjuryArea.WRIST to setOf(MuscleTag.CHEST, MuscleTag.SHOULDER),
        InjuryArea.ELBOW to setOf(MuscleTag.BICEPS, MuscleTag.TRICEPS, MuscleTag.BACK),
        InjuryArea.CARDIO to setOf(MuscleTag.CARDIO, MuscleTag.FULL_BODY),
    )

    /** 训练重点 → 该重点对应的肌群标签。 */
    private val FOCUS_TAGS: Map<TrainingFocus, Set<String>> = mapOf(
        TrainingFocus.FULL_BODY to setOf(MuscleTag.FULL_BODY, MuscleTag.CORE, MuscleTag.CARDIO),
        TrainingFocus.LOWER_BODY to setOf(
            MuscleTag.LEG,
            MuscleTag.GLUTE_LEG,
            MuscleTag.GLUTE,
            MuscleTag.HAMSTRING,
        ),
        TrainingFocus.UPPER_PUSH to setOf(
            MuscleTag.CHEST,
            MuscleTag.UPPER_CHEST,
            MuscleTag.SHOULDER,
            MuscleTag.TRICEPS,
        ),
        TrainingFocus.UPPER_PULL to setOf(MuscleTag.BACK, MuscleTag.REAR_DELT, MuscleTag.BICEPS),
        TrainingFocus.CARDIO_CORE to setOf(MuscleTag.CORE, MuscleTag.ABS, MuscleTag.CARDIO),
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
    ): PlanProposal {
        // 1) 手改行（含软删除行）→ 完整保留，且其「天 × 动作」槽位不再生成新条目。
        val preserved: List<WeekPlan> = existing.filter { it.isUserEdited }
        val occupiedByDay: Map<Int, Set<Long>> = preserved
            .groupBy { it.dayOfWeek }
            .mapValues { (_, rows) -> rows.map { it.exerciseId }.toSet() }

        // 2) 器械 / 伤病过滤后的可用动作池。
        val forbidden: Set<String> = aggravatedTagsFor(profile.injuryAreas)
        val eligible: List<Exercise> = library
            .filter { it.id != 0L }
            .filter { it.isActive }
            .filter { !it.aggravates(forbidden) }
            .filter { equipmentAllowed(profile, it) }
            .sortedBy { it.id }

        val historyByExercise: Map<Long, ExerciseProgress> = history.associateBy { it.exerciseId }

        // 3) 逐日生成草案（先保留中间产物，便于汇总 note）。
        //    训练重点由「本周轮换表」决定（见 focusSchedule）：**每周必含背日**，
        //    并按 trainingDays 的顺序与三个训练日一一对应。
        val schedule: List<TrainingFocus> = focusSchedule(today)
        val drafts: List<DayDraft> = trainingDays(today)
            .mapIndexed { index, dayOfWeek ->
                buildDay(
                    dayOfWeek = dayOfWeek,
                    focus = schedule[index % schedule.size],
                    eligible = eligible,
                    blockedIds = occupiedByDay[dayOfWeek].orEmpty(),
                    historyByExercise = historyByExercise,
                )
            }
            .filter { draft -> draft.day.items.isNotEmpty() }

        val notes: List<PlanNote> = drafts.flatMap { draft -> draft.notesOf() }
        val overloadCount: Int = notes.count { it.kind == PlanReason.PROGRESSIVE_OVERLOAD }
        val basisItems: List<PlanBasisItem> = buildList {
            add(PlanBasisItem(key = "basis_frequency"))
            add(PlanBasisItem(key = "basis_goal"))
            if (profile.gender != null && profile.age != null && profile.heightCm != null) {
                add(PlanBasisItem(key = "basis_profile"))
            }
            if (profile.injuryAreas.isNotEmpty()) {
                add(PlanBasisItem(key = "basis_injury"))
            }
            if (profile.equipment.isNotEmpty()) {
                add(PlanBasisItem(key = "basis_equipment"))
            }
            if (overloadCount > 0) {
                add(PlanBasisItem(key = "basis_overload", args = listOf(overloadCount)))
            } else if (history.isEmpty()) {
                add(PlanBasisItem(key = "basis_history_none"))
            }
        }

        return PlanProposal(
            days = drafts.map { draft -> draft.day },
            preservedUserEditedIds = preserved.map { it.id },
            notes = notes,
            analysis = null,
            basis = basisItems,
        )
    }

    /** 生成单日草案（内部用：携带 item 级别的 note 详情以便汇总）。 */
    private fun buildDay(
        dayOfWeek: Int,
        focus: TrainingFocus,
        eligible: List<Exercise>,
        blockedIds: Set<Long>,
        historyByExercise: Map<Long, ExerciseProgress>,
    ): DayDraft {
        val focusTags: Set<String> = FOCUS_TAGS[focus].orEmpty()
        val usable: List<Exercise> = eligible.filter { it.id !in blockedIds }

        // 优先命中该训练重点的肌群；一个都没有时退回全部可用动作（全天不至于空）。
        val matching: List<Exercise> = usable.filter { it.muscleGroups.any { tag -> tag in focusTags } }
        val pool: List<Exercise> = matching.ifEmpty { usable }

        // 修复 C1：不再简单 `take(4)`（会把同一主肌群的动作排满一整天）——
        // 改为「优先一主肌群一个」挑满 ITEMS_PER_DAY，不足时才按序补足（允许重复肌群）。
        val selected: List<Exercise> = selectForDay(pool, ITEMS_PER_DAY)

        val items: List<Pair<PlanItemDraft, PlanNoteDetail>> =
            selected.mapIndexed { itemIndex, exercise ->
                val baseSets: Int = (exercise.defaultSets ?: DEFAULT_SETS).coerceAtLeast(MIN_SETS)
                val baseReps: Int = (exercise.defaultReps ?: DEFAULT_REPS).coerceAtLeast(MIN_REPS)
                val decision: LoadDecision = decideLoad(
                    exercise = exercise,
                    progress = historyByExercise[exercise.id],
                    baseSets = baseSets,
                )
                val reason: PlanReason = decision.reason ?: baseReason(itemIndex, exercise)
                val item = PlanItemDraft(
                    exerciseId = exercise.id,
                    targetSets = decision.targetSets,
                    targetReps = baseReps,
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
        )
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
        if (previousWeight != null) {
            val newWeight: Float = previousWeight + OVERLOAD_WEIGHT_STEP_KG
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
     * 本周的训练日：从**今天**起向内排布（今天是周一 → 周一/周三/周五；今天是周四 → 周五/周一/周三）。
     *
     * 固定为 [PREFERRED_TRAINING_DAYS] 这三天（**集合不变** → 重复生成落在同一批槽位，幂等不会攒垃圾）；
     * [today] 只影响**顺序**，进而决定各天的训练重点轮换。
     */
    private fun trainingDays(today: LocalDate): List<Int> {
        // DayOfWeek 声明顺序为 MONDAY..SUNDAY，故 ordinal + 1 即 ISO 星期（`1` = 周一 … `7` = 周日）。
        // （本机依赖的 kotlinx-datetime 未暴露 `isoDayNumber`，故不用它，避免版本耦合。）
        val todayIso: Int = today.dayOfWeek.ordinal + 1
        return PREFERRED_TRAINING_DAYS
            .sortedBy { day -> (day - todayIso + WEEK_DAYS) % WEEK_DAYS }
            .take(DEFAULT_TRAINING_DAYS)
    }

    /**
     * 本周的 3 个训练重点（**修复 C1**）：从 [FOCUS_ROTATION_POOL] 按 [weekIndex] 顺延取
     * [ROTATION_PER_WEEK] 个，再**恒补一个** [TrainingFocus.UPPER_PULL]（背日）。
     *
     * - 输出长度 = [DEFAULT_TRAINING_DAYS]（3），与 [trainingDays] 一一对应；
     * - `UPPER_PULL` 永远在表中（保证"背"排得到）；
     * - [weekIndex] 只由 [today] 所在周决定 → **同一周内重复生成得到同一套重点**（幂等），
     *   跨周则轮换（例如下周换成 下肢 / 上肢推 / 背）。
     *
     * @param today 今天（决定"第几周"，从而决定轮换偏移）
     */
    private fun focusSchedule(today: LocalDate): List<TrainingFocus> {
        val pool: List<TrainingFocus> = FOCUS_ROTATION_POOL
        val start: Int = weekIndex(today) + ROTATION_PHASE
        val rotating: List<TrainingFocus> = (0 until ROTATION_PER_WEEK).map { step ->
            // Math.floorMod 保证负数周号也能安全落到 [0, size) 区间（不依赖 `%` 的符号）。
            pool[Math.floorMod(start + step, pool.size)]
        }
        return (rotating + TrainingFocus.UPPER_PULL).take(DEFAULT_TRAINING_DAYS)
    }

    /**
     * 以**周一为桶首**的周序号（同周内恒定、跨周 +1）。
     *
     * epochDay `0` = 1970-01-01（周四）→ 该周的周一是 `-3`；故 `epochDay + 3` 后整除 7
     * 即得以周一为界、每 7 天 +1 的稳定周号（用 `Math.floorDiv` 正确处理负的 epochDay）。
     */
    private fun weekIndex(today: LocalDate): Int =
        Math.floorDiv(today.toEpochDays() + MONDAY_ALIGN_OFFSET, WEEK_DAYS)

    /**
     * 从 [pool] 中挑至多 [limit] 个动作，**优先保证主肌群多样化**（修复 C1 的单日同质化）。
     *
     * 规则（确定性、零随机）：
     * 1. 第一轮：按 [pool] 顺序，每个**尚未出现过的主肌群**取一个，直到凑满 [limit] 或遍历完；
     * 2. 第二轮：若第一轮不足 [limit]（可用动作的肌群种类本就少于上限），按 [pool] 顺序
     *    补足剩余名额（允许重复肌群）—— 保证"动作够就排满"，不会因去重把一天排空。
     *
     * 主肌群为空的动作视为"各自独立"（不参与去重），避免误合并无标签动作。
     */
    private fun selectForDay(pool: List<Exercise>, limit: Int): List<Exercise> {
        if (pool.size <= limit) return pool
        val chosen: MutableList<Exercise> = ArrayList(limit)
        val seenGroups: MutableSet<String> = HashSet()
        // 第一轮：一主肌群一个。
        for (exercise in pool) {
            if (chosen.size >= limit) break
            val group: String? = exercise.primaryMuscleGroup?.takeIf { it.isNotBlank() }
            if (group != null && group in seenGroups) continue
            chosen.add(exercise)
            if (group != null) seenGroups.add(group)
        }
        // 第二轮：补足剩余名额（允许重复肌群）。
        if (chosen.size < limit) {
            val chosenIds: Set<Long> = chosen.map { it.id }.toSet()
            for (exercise in pool) {
                if (chosen.size >= limit) break
                if (exercise.id !in chosenIds) chosen.add(exercise)
            }
        }
        return chosen
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
     * 器械可行性：**只有 `STRENGTH` 需要器械**，其余分类（自重 / 有氧 / 自建）无需器械。
     *
     * 实体层无"所需器械"字段，故按分类判定（见类注释的"已知精度边界"）。
     */
    private fun equipmentAllowed(profile: UserProfile, exercise: Exercise): Boolean {
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

    /** 一天的生成中间产物（携带 item → note 详情的配对）。 */
    private data class DayDraft(
        val day: PlannedDay,
        val notePairs: List<Pair<PlanItemDraft, PlanNoteDetail>>,
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
