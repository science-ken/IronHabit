package com.ironhabit.app.verify

import com.ironhabit.app.data.preset.BuiltInExercises
import com.ironhabit.app.domain.ai.LocalRuleAdvisor
import com.ironhabit.app.domain.model.BodyMetricType
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.Gender
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.ProfileLimits
import com.ironhabit.app.domain.model.TrainingFocus
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import com.ironhabit.app.domain.usecase.GenerateTrainingPlanUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1（排课规则）对抗性复核 —— 目标是**证伪**，不是复述注释里的承诺。
 *
 * 约束：
 * 1. 只调用**公开 API**：[LocalRuleAdvisor.planWeek]（[PlanAdvisor] 的实现）与
 *    [GenerateTrainingPlanUseCase.preview] + [GenerateTrainingPlanUseCase.commit]；
 *    不碰任何 `internal` / `private` 成员。
 * 2. 不修改任何既有文件；断言不掺水（没有 `assertTrue(true)`、没有注释掉的断言）。
 * 3. 期望值来自**文档化的契约**（`TRAINING_DAY_SETS` 的 KDoc 表、`ProfileLimits` 的钳制域、
 *    `INJURY_AGGRAVATED_TAGS` / `SAFE_SUBSTITUTION_TAGS` 的语义），不是来自"跑一遍看它输出什么"。
 */
class P1AdversarialTest {

    // ---------------- 夹具 ----------------

    /** 2026-09-16 是周三，本周周一 = 2026-09-14。 */
    private val today: LocalDate = LocalDate(2026, 9, 16)

    /** 内置动作库（51 个），补上自增 id —— `planWeek` 会丢掉 `id == 0` 的动作。 */
    private val builtIn: List<Exercise> = BuiltInExercises.all.mapIndexed { index, exercise ->
        exercise.copy(id = index.toLong() + 1L)
    }

    /** KDoc 里写死的「每周训练天数 → 训练日集合」契约（周一 = 1 … 周日 = 7）。 */
    private val documentedDaySets: Map<Int, List<Int>> = mapOf(
        3 to listOf(1, 3, 5),
        4 to listOf(1, 2, 4, 5),
        5 to listOf(1, 2, 3, 5, 6),
        6 to listOf(1, 2, 3, 4, 5, 6),
    )

    private val allEquipment: Set<Equipment> = Equipment.values().toSet()

    private fun profile(
        goal: Goal = Goal.MAINTAIN,
        age: Int? = 30,
        bodyFatPct: Float? = null,
        goalWeightKg: Float? = null,
        equipment: Set<Equipment> = allEquipment,
        injuryAreas: Set<InjuryArea> = emptySet(),
        trainingDaysPerWeek: Int = 3,
        gender: Gender? = Gender.MALE,
        heightCm: Int? = 178,
    ): UserProfile = UserProfile(
        gender = gender,
        age = age,
        heightCm = heightCm,
        bodyFatPct = bodyFatPct,
        goal = goal,
        goalWeightKg = goalWeightKg,
        equipment = equipment,
        injuryAreas = injuryAreas,
        injuryNote = null,
        trainingDaysPerWeek = trainingDaysPerWeek,
    )

    private fun plan(
        profile: UserProfile,
        library: List<Exercise> = builtIn,
        existing: List<WeekPlan> = emptyList(),
        history: List<ExerciseProgress> = emptyList(),
        today: LocalDate = this.today,
        bodyWeightKg: Float? = null,
    ): PlanProposal = LocalRuleAdvisor.planWeek(
        profile = profile,
        library = library,
        existing = existing,
        history = history,
        today = today,
        bodyWeightKg = bodyWeightKg,
    )

    private fun muscleTagUnion(day: com.ironhabit.app.domain.model.PlannedDay, library: List<Exercise>): Set<String> =
        day.items.flatMapTo(HashSet()) { item ->
            library.firstOrNull { it.id == item.exerciseId }?.muscleGroups.orEmpty()
        }

    // =================================================================
    // ① 训练日集合 3/4/5/6 + 周日永远休息
    // =================================================================

    @Test
    fun trainingDaySets_matchTheDocumentedTable_andSundayIsAlwaysRest() {
        for (days in 3..6) {
            for (equipment in listOf(allEquipment, emptySet(), setOf(Equipment.NONE))) {
                val proposal = plan(profile(trainingDaysPerWeek = days, equipment = equipment))
                val actual = proposal.days.map { it.dayOfWeek }
                assertEquals(
                    "days=$days equipment=$equipment → 训练日集合应与 KDoc 表一致",
                    documentedDaySets[days],
                    actual,
                )
                assertTrue(
                    "days=$days equipment=$equipment → 周日（7）必须是休息日",
                    7 !in actual,
                )
            }
        }
    }

    @Test
    fun sundayIsNeverPlanned_across24WeeksAndAllFrequencies() {
        val violations: MutableList<String> = ArrayList()
        for (week in 0 until 24) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            for (days in 3..6) {
                val proposal = plan(profile(trainingDaysPerWeek = days), today = day)
                val planned = proposal.days.map { it.dayOfWeek }
                if (7 in planned) violations += "week+$week days=$days → $planned"
                if (planned != documentedDaySets[days]) {
                    violations += "week+$week days=$days → $planned（期望 ${documentedDaySets[days]}）"
                }
            }
        }
        assertEquals("24 周 × 4 种频率里出现的周日/集合偏差", emptyList<String>(), violations)
    }

    @Test
    fun outOfRangeTrainingDays_areCoercedIntoTheDocumentedDomain() {
        for (raw in listOf(-10, -1, 0, 1, 2, 7, 8, 99, Int.MAX_VALUE)) {
            val coerced = ProfileLimits.coerceTrainingDaysPerWeek(raw)
            val proposal = plan(profile(trainingDaysPerWeek = raw))
            assertEquals(
                "trainingDaysPerWeek=$raw 应钳制到 $coerced 天",
                documentedDaySets[coerced],
                proposal.days.map { it.dayOfWeek },
            )
        }
    }

    // =================================================================
    // ② 极端档案输入：不许产出荒谬值
    // =================================================================

    @Test
    fun extremeProfiles_neverProduceZeroSetsZeroRepsIllegalWeekdayOrEmptyDay() {
        val problems: MutableList<String> = ArrayList()
        val ages = listOf(null, 14, 40, 50, 100)
        val bodyFats = listOf(null, 3f, 12f, 60f)
        val bodyWeights = listOf(null, 0f, -5f, 200f)
        val goalWeights = listOf(null, 30f, 300f)
        val equipments = listOf(emptySet(), allEquipment)

        for (days in 3..6) {
            for (goal in Goal.values()) {
                for (age in ages) {
                    for (bodyFat in bodyFats) {
                        for (bodyWeight in bodyWeights) {
                            for (goalWeight in goalWeights) {
                                for (equipment in equipments) {
                                    val p = profile(
                                        goal = goal,
                                        age = age,
                                        bodyFatPct = bodyFat,
                                        goalWeightKg = goalWeight,
                                        equipment = equipment,
                                        trainingDaysPerWeek = days,
                                    )
                                    val proposal = plan(p, bodyWeightKg = bodyWeight)
                                    val tag =
                                        "days=$days goal=$goal age=$age bf=$bodyFat bw=$bodyWeight gw=$goalWeight eq=${equipment.size}"
                                    if (proposal.days.size != days) {
                                        problems += "$tag → 排了 ${proposal.days.size} 天（期望 $days）"
                                    }
                                    for (day in proposal.days) {
                                        if (day.dayOfWeek !in 1..7) problems += "$tag → dayOfWeek=${day.dayOfWeek}"
                                        if (day.items.isEmpty()) problems += "$tag → day ${day.dayOfWeek} 被排空"
                                        val groups = day.items.map { item ->
                                            builtIn.first { it.id == item.exerciseId }.muscleGroups.first()
                                        }
                                        if (groups.size != groups.toSet().size) {
                                            problems += "$tag → day ${day.dayOfWeek} 同日内重复主肌群 $groups"
                                        }
                                        for (item in day.items) {
                                            if (item.targetSets <= 0) {
                                                problems += "$tag → exerciseId=${item.exerciseId} targetSets=${item.targetSets}"
                                            }
                                            if (item.targetReps <= 0) {
                                                problems += "$tag → exerciseId=${item.exerciseId} targetReps=${item.targetReps}"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals("极端档案组合下的荒谬输出（去重后）", emptyList<String>(), problems.distinct())
    }

    /** 9 个伤病部位的禁忌标签并集（数据来自 `INJURY_AGGRAVATED_TAGS` 的语义）。 */
    private val allInjuryForbidden: Set<String> = setOf(
        "腿部", "臀腿", "全身", "臀部", "背部", "腿后链",
        "肩部", "后肩", "上胸", "胸部", "肱二头肌", "肱三头肌", "有氧",
    )

    @Test
    fun allInjuriesSelected_neverSchedulesAnAggravatingMuscleGroup() {
        val violations: MutableList<String> = ArrayList()
        for (days in 3..6) {
            val proposal = plan(
                profile(injuryAreas = InjuryArea.values().toSet(), trainingDaysPerWeek = days),
            )
            for (d in proposal.days) {
                for (item in d.items) {
                    val hit = builtIn.first { it.id == item.exerciseId }.muscleGroups intersect allInjuryForbidden
                    if (hit.isNotEmpty()) violations += "days=$days 周${d.dayOfWeek} id=${item.exerciseId} → $hit"
                }
            }
        }
        assertEquals("9 处伤病全选后仍排出了会刺激伤病的动作", emptyList<String>(), violations)
    }

    @Test
    fun degenerateLibrary_duplicatesAreAllowedButMustBeExplained() {
        // 复核报告 F-2 的**回归版**（原文断言"不许重复"，实现方复核后重新划定了契约）。
        //
        // 事实不变：9 处伤病全选后，51 个内置动作里只剩「核心 / 腹部」两个主肌群是安全的，
        // `selectForDay` 会走到「退化兜底」允许重复肌群把一天填满。
        //
        // 复核后的决定：**保留"填满一天"的行为**（否则极端伤病用户一天只剩 2 个动作），
        // 但不许静默 —— 必须给出生成依据 `basis_library_too_narrow`，让界面能解释
        // "按你的伤病与器械过滤后可用动作不太够"。KDoc 里"第 3 步永不触发"的错误承诺已删除。
        val violations: MutableList<String> = ArrayList()
        for (days in 3..6) {
            val proposal = plan(
                profile(injuryAreas = InjuryArea.values().toSet(), trainingDaysPerWeek = days),
            )
            val repeatedDays = proposal.days.count { day ->
                val groups = day.items.map { item ->
                    builtIn.first { it.id == item.exerciseId }.muscleGroups.first()
                }
                groups.size != groups.toSet().size
            }
            val basis = proposal.basis.firstOrNull { it.key == "basis_library_too_narrow" }
            if (repeatedDays > 0 && basis == null) {
                violations += "days=$days 有 $repeatedDays 天重复了主肌群，却没有 basis_library_too_narrow"
            }
            if (basis != null && basis.args.single() != repeatedDays) {
                violations += "days=$days basis_library_too_narrow 参数=${basis.args.single()}，实际重复天数=$repeatedDays"
            }
        }
        assertEquals("退化库下重复了主肌群，却没有如实说明", emptyList<String>(), violations)
    }

    // =================================================================
    // ③ 不连排同肌群（有氧是文档化的唯一例外）
    // =================================================================

    /** 文档化允许在相邻两天重复出现的肌群：**只有有氧**("配额优先于不连排同肌群")。 */
    private val allowedConsecutiveOverlap: Set<String> = setOf("有氧")

    @Test
    fun consecutiveCalendarDays_doNotShareMuscleGroups_exceptDocumentedCardioException() {
        val violations: MutableList<String> = ArrayList()
        for (week in 0 until 16) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            for (days in 3..6) {
                for (goal in listOf(Goal.MAINTAIN, Goal.CUT, Goal.BULK)) {
                    val proposal = plan(profile(goal = goal, trainingDaysPerWeek = days), today = day)
                    val byDay = proposal.days.associateBy { it.dayOfWeek }
                    for (d in proposal.days) {
                        val next = byDay[d.dayOfWeek + 1] ?: continue   // 只查日历相邻的一对
                        val overlap = muscleTagUnion(d, builtIn) intersect muscleTagUnion(next, builtIn)
                        val unexpected = overlap - allowedConsecutiveOverlap
                        if (unexpected.isNotEmpty()) {
                            violations += "week+$week days=$days goal=$goal → 周${d.dayOfWeek}(${d.focus})/周${next.dayOfWeek}(${next.focus}) 撞了 $unexpected"
                        }
                    }
                }
            }
        }
        assertEquals(
            "相邻两天重复肌群（已排除有氧例外）",
            emptyList<String>(),
            violations,
        )
    }

    @Test
    fun cardioIsTheOnlyDeliberateConsecutiveOverlap_proveItIsActuallyExercised() {
        // 反证：如果"有氧例外"这条分支根本没被触发过，上一条测试就是空跑。
        // 减脂目标 + 高体脂 → 有氧配额上调；6 天训练 → 相邻两天都可能有有氧。
        var cardioOnConsecutiveDays = 0
        for (week in 0 until 16) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            val proposal = plan(
                profile(goal = Goal.CUT, bodyFatPct = 40f, trainingDaysPerWeek = 6),
                today = day,
            )
            val byDay = proposal.days.associateBy { it.dayOfWeek }
            for (d in proposal.days) {
                val next = byDay[d.dayOfWeek + 1] ?: continue
                val overlap = muscleTagUnion(d, builtIn) intersect muscleTagUnion(next, builtIn)
                if ("有氧" in overlap) cardioOnConsecutiveDays++
            }
        }
        assertTrue(
            "减脂 + 高体脂 + 6 天/周，本应能看到有氧连续两天出现（否则上面那条例外断言等于没测）",
            cardioOnConsecutiveDays > 0,
        )
    }

    /** 训练重点 → 该重点的肌群标签（数据契约，来自 `FOCUS_TAGS`）。 */
    private val focusTagContract: Map<TrainingFocus, Set<String>> = mapOf(
        TrainingFocus.LOWER_BODY to setOf("腿部", "臀腿", "臀部", "腿后链"),
        TrainingFocus.UPPER_PUSH to setOf("胸部", "上胸", "肩部", "肱三头肌"),
        TrainingFocus.UPPER_PULL to setOf("背部", "后肩", "肱二头肌"),
        TrainingFocus.FULL_BODY to setOf("全身", "核心", "有氧"),
        TrainingFocus.CARDIO_CORE to setOf("核心", "腹部", "有氧"),
    )

    @Test
    fun focusSchedule_adjacentFocusesNeverShareMuscleTags() {
        // 复核报告 F-4 的**回归版**（原文把缺陷本身写成期望值：断言"确实会撞"）。
        //
        // 原缺陷：`focusSchedule` 只对"天数 > 3 时往后追加"的重点做 `sharesNoMuscleTag` 检查，
        // 而 base = [轮换池取 2 个] + UPPER_PULL 里**前两个是直接取的**。轮换池顺序是
        // [LOWER_BODY, UPPER_PUSH, FULL_BODY, CARDIO_CORE]，取到 (FULL_BODY, CARDIO_CORE) 时
        // 两者共享 {核心, 有氧} —— "相邻两天重点肌群不相交"这条声明当时不成立
        //（4 天/周时正是日历相邻的周一/周二：2026-09-28、2026-10-26 两周）。
        //
        // 修复：轮换池内部也做相交检查（取不到就往后试下一个），并把既有测试的采样
        // 从 14 天扩到 56 天（8 周，覆盖全部 4 个轮换相位）。
        val offenders: MutableList<String> = ArrayList()
        for (week in 0 until 16) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            val proposal = plan(profile(trainingDaysPerWeek = 4), today = day)
            val monday = proposal.days[0]
            val tuesday = proposal.days[1]
            val shared = focusTagContract.getValue(monday.focus) intersect focusTagContract.getValue(tuesday.focus)
            if (shared.isNotEmpty()) {
                offenders += "$day 周一=${monday.focus} / 周二=${tuesday.focus} 共享 ${shared.sorted()}"
            }
        }
        assertEquals("相邻两天的训练重点肌群不得相交", emptyList<String>(), offenders)
    }

    @Test
    fun secondaryMuscleTagAlsoCountsAsTheSameGroupForTheConsecutiveDayRule() {
        // 判定问题：一个"腿部 + 肱三头肌"双标签动作（副标签是肱三头肌），
        // 第二天排"绳索下压（肱三头肌）"算不算撞肌群？
        // 契约判定：**算**。groupsOf() 取的是 muscleGroups 全量并集，takeDistinct 用 any{} 全量比对，
        // 所以副标签既会"产出"也会"被禁"。这条用例就是该判定的证据。
        val legPlusTriceps = Exercise(
            id = 901L,
            name = "腿举（带三头发力）",
            category = ExerciseCategory.STRENGTH,
            muscleGroups = listOf("腿部", "肱三头肌"),
            isActive = true,
            defaultSets = 3,
            defaultReps = 10,
        )
        // 库里只留这**一个**下肢动作 → 下肢日必然选中它；其余肌群留足（> itemsPerDay）以免走退化兜底。
        val library: List<Exercise> = builtIn.filter { it.muscleGroups.first() !in setOf("腿部", "臀腿", "臀部", "腿后链") } +
            legPlusTriceps

        var checked = 0
        val violations: MutableList<String> = ArrayList()
        for (week in 0 until 16) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            val proposal = plan(profile(trainingDaysPerWeek = 6), library = library, today = day)
            val byDay = proposal.days.associateBy { it.dayOfWeek }
            for (d in proposal.days) {
                val usedSecondary = d.items.any { it.exerciseId == 901L }
                if (!usedSecondary) continue
                val next = byDay[d.dayOfWeek + 1] ?: continue
                checked++
                val tricepsOnNextDay = next.items.filter { item ->
                    library.first { it.id == item.exerciseId }.muscleGroups.contains("肱三头肌")
                }
                if (tricepsOnNextDay.isNotEmpty()) {
                    violations += "week+$week 周${d.dayOfWeek} 用了「腿部+肱三头肌」，次日仍排了肱三头肌动作 " +
                        tricepsOnNextDay.map { it.exerciseId }
                }
            }
        }
        assertTrue("构造没生效：16 周里一次都没选中 901，本用例等于空跑", checked > 0)
        assertEquals("副标签必须同样参与「不连排同肌群」", emptyList<String>(), violations)
    }

    // =================================================================
    // ④ 伤病替代（含 KNEE + LOWER_BACK + SHOULDER 三处同时）
    // =================================================================

    @Test
    fun kneeLowerBackShoulder_neverSchedulesAnyAggravatingMuscleGroup() {
        // 文档化的禁忌标签：KNEE{腿,臀腿,全身} ∪ LOWER_BACK{背部,腿后链,全身} ∪ SHOULDER{肩部,后肩,上胸,胸部}
        val forbidden = setOf("腿部", "臀腿", "全身", "背部", "腿后链", "肩部", "后肩", "上胸", "胸部")
        val violations: MutableList<String> = ArrayList()
        for (week in 0 until 12) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            for (days in 3..6) {
                val proposal = plan(
                    profile(
                        injuryAreas = setOf(InjuryArea.KNEE, InjuryArea.LOWER_BACK, InjuryArea.SHOULDER),
                        trainingDaysPerWeek = days,
                    ),
                    today = day,
                )
                for (d in proposal.days) {
                    for (item in d.items) {
                        val groups = builtIn.first { it.id == item.exerciseId }.muscleGroups
                        val hit = groups intersect forbidden
                        if (hit.isNotEmpty()) {
                            violations += "week+$week days=$days 周${d.dayOfWeek} 排了 ${hit}（id=${item.exerciseId}）"
                        }
                    }
                }
            }
        }
        assertEquals("伤病禁忌肌群被排入计划", emptyList<String>(), violations)
    }

    @Test
    fun kneeInjury_substitutesWithSafeAdjacentGroups_andLabelsThemInjurySafe() {
        // 膝伤：{腿,臀腿,全身} 被挡 → 安全替代标签 {臀部, 核心, 腹部}。
        var foundLegDay = 0
        val labelled: MutableList<Long> = ArrayList()
        for (week in 0 until 16) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            val proposal = plan(
                profile(injuryAreas = setOf(InjuryArea.KNEE), trainingDaysPerWeek = 6),
                today = day,
            )
            for (d in proposal.days) {
                if (d.focus != TrainingFocus.LOWER_BODY) continue
                foundLegDay++
                val safe = d.items.filter { item ->
                    builtIn.first { it.id == item.exerciseId }.muscleGroups.any { it in setOf("臀部", "核心", "腹部") }
                }
                labelled += safe.filter { it.reason == PlanReason.INJURY_SAFE }.map { it.exerciseId }
            }
        }
        assertTrue("16 周里应至少出现过一次下肢日", foundLegDay > 0)
        assertTrue(
            "下肢日里来自安全邻近肌群（臀部/核心/腹部）的动作，必须被标注为 INJURY_SAFE，" +
                "否则用户在界面上看不到「为什么换成这个」",
            labelled.isNotEmpty(),
        )
    }

    @Test
    fun noInjury_neverLabelsAnythingAsInjurySafe() {
        // 没有伤病却打"因避让伤病而替换"的标记 = 编理由。
        val violations: MutableList<String> = ArrayList()
        for (week in 0 until 16) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            for (days in 3..6) {
                val proposal = plan(profile(trainingDaysPerWeek = days), today = day)
                for (d in proposal.days) {
                    for (item in d.items) {
                        if (item.reason == PlanReason.INJURY_SAFE) {
                            violations += "无伤病却标了 INJURY_SAFE：week+$week day=${d.dayOfWeek} id=${item.exerciseId}"
                        }
                    }
                }
                for (note in proposal.notes) {
                    if (note.kind == PlanReason.INJURY_SAFE) {
                        violations += "无伤病却产出 INJURY_SAFE 说明：week+$week id=${note.exerciseId}"
                    }
                }
            }
        }
        assertEquals("无伤病时的假理由", emptyList<String>(), violations)
    }

    @Test
    fun shoulderInjury_doesNotMislabelNormalBackDayExercisesAsInjurySafe() {
        // ❌ 已知缺陷（REVIEW-p1p2.md F-1）：肩伤的禁忌是 {肩部, 后肩, 上胸, 胸部}，**背部并不在其中**。
        // 但 UPPER_PULL 的重点标签是 {背部, 后肩, 肱二头肌}，只要"后肩"命中禁忌，
        // focusBlockedByInjury 就为真 → 于是**背部动作（高位下拉/划船…）被整批标成"因避让伤病而替换"**。
        // 这些动作本来就会排在背日，标 INJURY_SAFE 是**假理由** —— 正是代码注释里明说要避免的那种
        // （"不标注'没被挡掉的重点日'里的同类动作…标成因伤病而替代是假理由（宁可少标，也不编）"）。
        val backExercises: Set<Long> = builtIn
            .filter { it.muscleGroups.contains("背部") }
            .map { it.id }
            .toSet()
        assertTrue("内置库里应有背部动作，否则本用例无意义", backExercises.isNotEmpty())

        val mislabelled: MutableList<String> = ArrayList()
        for (week in 0 until 16) {
            val day = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + week * 7)
            val proposal = plan(profile(injuryAreas = setOf(InjuryArea.SHOULDER), trainingDaysPerWeek = 3), today = day)
            for (d in proposal.days) {
                if (d.focus != TrainingFocus.UPPER_PULL) continue
                for (item in d.items) {
                    if (item.exerciseId in backExercises && item.reason == PlanReason.INJURY_SAFE) {
                        mislabelled += "week+$week 背日 id=${item.exerciseId} 被标成 INJURY_SAFE"
                    }
                }
            }
        }
        assertEquals(
            "肩伤不挡背部 → 背部动作不该被标成「因避让伤病而替换」",
            emptyList<String>(),
            mislabelled,
        )
    }

    // =================================================================
    // ⑤ 幂等
    // =================================================================

    @Test
    fun planWeek_isIdempotent_sameInputsSameProposal() {
        val p = profile(goal = Goal.BULK, trainingDaysPerWeek = 5)
        val history = listOf(
            ExerciseProgress(exerciseId = 24L, lastSetsCompleted = 4, lastTargetSets = 4, lastRpe = 5, lastWeightKg = 60f),
        )
        val first = plan(p, history = history)
        val second = plan(p, history = history)
        assertEquals("同输入必同输出（含 notes / basis / preserved）", first, second)
    }

    @Test
    fun planWeek_isStableAcrossAllSevenDaysOfTheSameWeek() {
        val monday = LocalDate(2026, 9, 14)
        val p = profile(goal = Goal.RECOMP, trainingDaysPerWeek = 4)
        val reference = plan(p, today = monday)
        for (offset in 1..6) {
            val other = LocalDate.fromEpochDays(monday.toEpochDays() + offset)
            assertEquals(
                "同一周内的任意一天（${other}）生成结果必须与周一一致，否则重复点「生成」会攒垃圾行",
                reference,
                plan(p, today = other),
            )
        }
    }

    @Test
    fun generatePlanUseCase_isIdempotent_writesTheSameSlotsTwice() = runTest {
        val planRepository = mockk<PlanRepository>()
        val exerciseRepository = mockk<ExerciseRepository>()
        val checkInRepository = mockk<CheckInRepository>()
        val bodyMetricRepository = mockk<BodyMetricRepository>()
        val settingsRepository = mockk<SettingsRepository>()

        every { settingsRepository.profile() } returns flowOf(profile(trainingDaysPerWeek = 4))
        every { exerciseRepository.observeActive() } returns flowOf(builtIn)
        // P3：生成改为**按周**取现有行（getRowsForWeek），旧桩（全表）已不适用。
        coEvery { planRepository.getRowsForWeek(any()) } returns emptyList()
        // P0-3：生成还会读「每周相同」模板行做保护（getRepeatRows），此处没有模板。
        coEvery { planRepository.getRepeatRows() } returns emptyList()
        every { checkInRepository.latestProgressPerExercise() } returns flowOf(emptyList())
        coEvery { bodyMetricRepository.latest(BodyMetricType.WEIGHT) } returns null

        val written: MutableList<List<WeekPlan>> = ArrayList()
        coEvery { planRepository.upsertGenerated(any()) } answers {
            val batch = firstArg<List<WeekPlan>>()
            written += batch
            batch.size
        }
        coEvery { planRepository.deactivateGenerated(any()) } returns 0

        val useCase = GenerateTrainingPlanUseCase(
            planRepository = planRepository,
            exerciseRepository = exerciseRepository,
            checkInRepository = checkInRepository,
            bodyMetricRepository = bodyMetricRepository,
            settingsRepository = settingsRepository,
            advisor = LocalRuleAdvisor,
            clock = object : Clock {
                override fun now(): Instant = Instant.parse("2026-09-16T10:00:00Z")
            },
            timeZone = TimeZone.UTC,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val first = useCase.generateAll()
        val second = useCase.generateAll()

        assertEquals("两次写入的批次数", 2, written.size)
        assertEquals(
            "第二次写入的槽位必须与第一次完全一致（幂等，不攒垃圾行）",
            written[0].map { it.dayOfWeek to it.exerciseId },
            written[1].map { it.dayOfWeek to it.exerciseId },
        )
        assertEquals(first.writtenCount, second.writtenCount)
    }

    // =================================================================
    // ⑥ 用户改过的行（含软删除）永不被写入 / 复活
    // =================================================================

    @Test
    fun advisor_neverGeneratesIntoUserEditedSlots() {
        // 手改行：周一（id 11，启用）+ 周三（id 12，用户自己软删除）
        val squatId = builtIn.first { it.name == "杠铃深蹲" }.id
        val benchId = builtIn.first { it.name == "杠铃卧推" }.id
        val existing = listOf(
            WeekPlan(id = 11L, exerciseId = squatId, dayOfWeek = 1, isUserEdited = true),
            WeekPlan(id = 12L, exerciseId = benchId, dayOfWeek = 3, isActive = false, isUserEdited = true),
        )
        for (days in 3..6) {
            val proposal = plan(profile(trainingDaysPerWeek = days), existing = existing)
            assertEquals(
                "手改行必须被完整保留（含软删除行）",
                listOf(11L, 12L),
                proposal.preservedUserEditedIds,
            )
            for (d in proposal.days) {
                assertTrue(
                    "days=$days 周${d.dayOfWeek} 不得占用手改槽位（深蹲@周一 / 卧推@周三）",
                    d.items.none { (d.dayOfWeek == 1 && it.exerciseId == squatId) || (d.dayOfWeek == 3 && it.exerciseId == benchId) },
                )
            }
        }
    }

    @Test
    fun generatePlanUseCase_neverOverwritesOrResurrectsUserEditedRows() = runTest {
        val squatId = builtIn.first { it.name == "杠铃深蹲" }.id
        val benchId = builtIn.first { it.name == "杠铃卧推" }.id
        val existing = listOf(
            WeekPlan(id = 11L, exerciseId = squatId, dayOfWeek = 1, isUserEdited = true),
            WeekPlan(id = 12L, exerciseId = benchId, dayOfWeek = 3, isActive = false, isUserEdited = true),
            WeekPlan(id = 13L, exerciseId = 3L, dayOfWeek = 5, isUserEdited = false),   // 上版 AI 行
        )

        val planRepository = mockk<PlanRepository>()
        val exerciseRepository = mockk<ExerciseRepository>()
        val checkInRepository = mockk<CheckInRepository>()
        val bodyMetricRepository = mockk<BodyMetricRepository>()
        val settingsRepository = mockk<SettingsRepository>()

        every { settingsRepository.profile() } returns flowOf(profile(trainingDaysPerWeek = 3))
        every { exerciseRepository.observeActive() } returns flowOf(builtIn)
        // P3：生成改为**按周**取现有行（getRowsForWeek），旧桩（全表）已不适用。
        coEvery { planRepository.getRowsForWeek(any()) } returns existing
        // P0-3：生成还会读「每周相同」模板行做保护（getRepeatRows），此处没有模板。
        coEvery { planRepository.getRepeatRows() } returns emptyList()
        every { checkInRepository.latestProgressPerExercise() } returns flowOf(emptyList())
        coEvery { bodyMetricRepository.latest(BodyMetricType.WEIGHT) } returns null

        val written: MutableList<List<WeekPlan>> = ArrayList()
        coEvery { planRepository.upsertGenerated(any()) } answers {
            val batch = firstArg<List<WeekPlan>>()
            written += batch
            batch.size
        }
        val retired: MutableList<List<WeekPlan>> = ArrayList()
        coEvery { planRepository.deactivateGenerated(any()) } answers {
            val batch = firstArg<List<WeekPlan>>()
            retired += batch
            batch.size
        }

        val useCase = GenerateTrainingPlanUseCase(
            planRepository = planRepository,
            exerciseRepository = exerciseRepository,
            checkInRepository = checkInRepository,
            bodyMetricRepository = bodyMetricRepository,
            settingsRepository = settingsRepository,
            advisor = LocalRuleAdvisor,
            clock = object : Clock {
                override fun now(): Instant = Instant.parse("2026-09-16T10:00:00Z")
            },
            timeZone = TimeZone.UTC,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val summary = useCase.generateAll()

        val editedSlots = setOf(1 to squatId, 3 to benchId)
        val writtenSlots = written.flatMap { batch -> batch.map { it.dayOfWeek to it.exerciseId } }
        assertEquals(
            "绝不能往用户手改过的槽位写东西",
            emptyList<Pair<Int, Long>>(),
            writtenSlots.filter { it in editedSlots },
        )
        val retiredIds = retired.flatMap { batch -> batch.map { it.id } }
        assertTrue(
            "手改行（含软删除）永不被回收：实际回收了 $retiredIds",
            retiredIds.none { it == 11L || it == 12L },
        )
        assertEquals("保留条数应如实等于手改行数", 2, summary.preservedCount)

        coVerify(exactly = 0) { planRepository.delete(any()) }
        coVerify(exactly = 0) { planRepository.upsert(any()) }
    }
}

/**
 * 「生成 + 整周写入」—— **只服务于本文件**。
 *
 * 生产代码里这条通道原本是 `GenerateTrainingPlanUseCase.invoke()`，已删除：它是唯一能绕过
 * 「逐天预览 → 逐天采纳」闸门的写库路径。本文件要观察的是"落库落成什么样"，
 * 所以把它降级成 `preview()` + `commit(全周)` 的测试侧 helper。
 */
private suspend fun GenerateTrainingPlanUseCase.generateAll() =
    commit(preview(), ALL_WEEK_DAYS)

/** 整周采纳（`1..7`）。 */
private val ALL_WEEK_DAYS: Set<Int> = (1..7).toSet()
