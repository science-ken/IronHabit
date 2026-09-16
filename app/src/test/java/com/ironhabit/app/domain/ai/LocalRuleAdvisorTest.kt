package com.ironhabit.app.domain.ai

import com.ironhabit.app.data.preset.BuiltInExercises
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.Goal
import com.ironhabit.app.domain.model.InjuryArea
import com.ironhabit.app.domain.model.PlanItemDraft
import com.ironhabit.app.domain.model.PlanNoteDetail
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.PlanReason
import com.ironhabit.app.domain.model.TrainingFocus
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalRuleAdvisor] 纯 JVM 单测（零 Android、零 IO、零网络、零随机）。
 *
 * 覆盖（对应派工单的 8 条不变量）：
 * 1. 手改行保护（含软删除行）；4. 器械约束；5. 伤病排除；
 * 6. 渐进超负荷边界；7. 空档案兜底；8. 确定性（零随机）。
 *
 * 不变量 2（禁用 REPLACE / 不覆盖手改行）与 3（adopt 幂等）在
 * `GenerateTrainingPlanUseCaseTest` / `SuggestExercisesUseCaseTest` 中覆盖（需仓库协作）。
 */
class LocalRuleAdvisorTest {

    private val today: LocalDate = LocalDate(2026, 9, 14)

    // ---------------- 测试夹具 ----------------

    private fun exercise(
        id: Long,
        category: ExerciseCategory = ExerciseCategory.BODYWEIGHT,
        muscle: String,
        sets: Int = 3,
        reps: Int = 12,
        isActive: Boolean = true,
        durationSec: Int? = null,
    ): Exercise = Exercise(
        id = id,
        name = "ex-$id",
        category = category,
        muscleGroups = listOf(muscle),
        isActive = isActive,
        defaultSets = sets,
        defaultReps = reps,
        defaultDurationSec = durationSec,
    )

    /** 默认动作库：覆盖腿部 / 胸部 / 背部 / 腹部 / 核心 / 有氧 / 全身 + 2 个力量动作。 */
    private val library: List<Exercise> = listOf(
        exercise(1L, ExerciseCategory.BODYWEIGHT, "腿部"),
        exercise(2L, ExerciseCategory.BODYWEIGHT, "胸部"),
        exercise(3L, ExerciseCategory.BODYWEIGHT, "背部"),
        exercise(4L, ExerciseCategory.BODYWEIGHT, "腹部"),
        exercise(5L, ExerciseCategory.BODYWEIGHT, "核心"),
        exercise(6L, ExerciseCategory.CARDIO, "有氧"),
        exercise(7L, ExerciseCategory.BODYWEIGHT, "全身"),
        exercise(8L, ExerciseCategory.STRENGTH, "腿部"),
        exercise(9L, ExerciseCategory.STRENGTH, "胸部"),
    )

    /** 渐进超负荷专用库：4 个同标签动作，保证全部进入 FULL_BODY 日，便于精确定位。 */
    private fun overloadLibrary(): List<Exercise> = listOf(
        exercise(1L, ExerciseCategory.BODYWEIGHT, "全身"),
        exercise(2L, ExerciseCategory.BODYWEIGHT, "全身"),
        exercise(3L, ExerciseCategory.BODYWEIGHT, "全身"),
        exercise(4L, ExerciseCategory.BODYWEIGHT, "全身"),
    )

    private fun PlanProposal.itemFor(exerciseId: Long): PlanItemDraft? =
        days.flatMap { it.items }.firstOrNull { it.exerciseId == exerciseId }

    private fun PlanProposal.allExerciseIds(): Set<Long> =
        days.flatMap { it.items }.map { it.exerciseId }.toSet()

    // ---------------- 不变量 1：手改行（含软删除行）完整保留 ----------------

    @Test
    fun planWeek_preservesUserEditedRow_andNeverOverwritesItsSlot() {
        val editedRow = WeekPlan(id = 100L, exerciseId = 1L, dayOfWeek = 1, isUserEdited = true)
        val normalRow = WeekPlan(id = 101L, exerciseId = 2L, dayOfWeek = 1)

        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = library,
            existing = listOf(editedRow, normalRow),
            today = today,
        )

        assertEquals(
            "手改行必须完整保留（含其槽位），普通行不受此保护",
            listOf(100L),
            proposal.preservedUserEditedIds,
        )
        assertFalse(
            "手改行的「天 × 动作」槽位不得再生成条目（否则会被覆盖）",
            proposal.days
                .filter { it.dayOfWeek == 1 }
                .flatMap { it.items }
                .any { it.exerciseId == 1L },
        )
    }

    @Test
    fun planWeek_preservesSoftDeletedUserEditedRow_andNeverRevivesIt() {
        // 软删除行：isActive = false 且 isUserEdited = true（用户主动删掉的那条）。
        val softDeleted = WeekPlan(
            id = 200L,
            exerciseId = 1L,
            dayOfWeek = 1,
            isActive = false,
            isUserEdited = true,
        )

        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = library,
            existing = listOf(softDeleted),
            today = today,
        )

        assertEquals(
            "软删除的手改行同样计入「已保留」",
            listOf(200L),
            proposal.preservedUserEditedIds,
        )
        assertFalse(
            "软删除槽位绝不能重新生成 —— 否则等于把用户删掉的那条「复活」",
            proposal.days
                .filter { it.dayOfWeek == 1 }
                .flatMap { it.items }
                .any { it.exerciseId == 1L },
        )
    }

    // ---------------- 不变量 4：器械约束 ----------------

    @Test
    fun planWeek_excludesStrengthExercises_whenNoStrengthGear() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(equipment = emptySet()),   // 空集 → {NONE}，仅自重
            library = library,
            existing = emptyList(),
            today = today,
        )

        val ids = proposal.allExerciseIds()
        assertFalse("无力量器械时不得排入力量动作（id=8 杠铃深蹲）", 8L in ids)
        assertFalse("无力量器械时不得排入力量动作（id=9 卧推）", 9L in ids)
        assertTrue("自重动作仍应可用", 1L in ids || 2L in ids)
    }

    @Test
    fun planWeek_includesStrengthExercises_whenGearOwned() {
        // 修复 D2 后：「一天内主肌群至多一次」会让与自重动作**同主肌群**的力量动作让位
        //（如「腿部力量」vs「腿部自重」，后者 id 更小而先入）。故本用例改用**独占肌群**的
        // 力量动作，精确验证「有器械 → 力量动作可进入计划」这一不变量本身（与主肌群去重正交）。
        val gearLibrary = listOf(
            exercise(1L, ExerciseCategory.BODYWEIGHT, "腿部"),
            exercise(8L, ExerciseCategory.STRENGTH, "臀腿"),
            exercise(9L, ExerciseCategory.STRENGTH, "腿后链"),
        )
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(equipment = setOf(Equipment.DUMBBELL)),
            library = gearLibrary,
            existing = emptyList(),
            today = today,
        )

        assertTrue(
            "拥有哑铃后力量动作应进入计划（id=8 腿后链力量 / id=9 臀腿力量）",
            8L in proposal.allExerciseIds() || 9L in proposal.allExerciseIds(),
        )
    }

    // ---------------- 不变量 5：伤病排除 ----------------

    @Test
    fun planWeek_exercisesAggravatingInjuryAreExcluded() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(injuryAreas = setOf(InjuryArea.KNEE)),
            library = library,
            existing = emptyList(),
            today = today,
        )

        val ids = proposal.allExerciseIds()
        assertFalse("膝伤 → 腿部动作（id=1）必须排除", 1L in ids)
        assertFalse("膝伤 → 腿部力量动作（id=8）必须排除", 8L in ids)
        assertFalse("膝伤 → 全身动作（id=7）必须排除", 7L in ids)
        assertTrue("胸部动作不受膝伤影响", 2L in ids)
    }

    @Test
    fun planWeek_shoulderInjuryExcludesShoulderAndChestMovements() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(injuryAreas = setOf(InjuryArea.SHOULDER)),
            library = library,
            existing = emptyList(),
            today = today,
        )

        val ids = proposal.allExerciseIds()
        assertFalse("肩伤 → 胸部动作（id=2）必须排除", 2L in ids)
        assertFalse("肩伤 → 胸部力量动作（id=9）必须排除", 9L in ids)
        assertTrue("腿部动作不受肩伤影响", 1L in ids)
    }

    // ---------------- 不变量 6：渐进超负荷（§4.4 表格 + 边界）----------------

    @Test
    fun overload_allSetsCompletedWithEasyRpe_adds2Point5Kg() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = overloadLibrary(),
            existing = emptyList(),
            history = listOf(
                ExerciseProgress(
                    exerciseId = 2L,
                    lastSetsCompleted = 3,
                    lastTargetSets = 3,
                    lastRpe = 6,
                    lastWeightKg = 40f,
                ),
            ),
            today = today,
        )

        val item = proposal.itemFor(2L)!!
        assertEquals(42.5f, item.targetWeightKg!!, 0.0001f)
        assertEquals(PlanReason.PROGRESSIVE_OVERLOAD, item.reason)
        assertTrue(
            "加重应带出 note 参数（old → new）",
            proposal.notes.any {
                it.exerciseId == 2L &&
                    it.detail is PlanNoteDetail.WeightDelta &&
                    (it.detail as PlanNoteDetail.WeightDelta).oldWeightKg == 40f &&
                    it.detail.newWeightKg == 42.5f
            },
        )
    }

    @Test
    fun overload_rpe7And8_maintainWeight() {
        for (rpe in 7..8) {
            val proposal = LocalRuleAdvisor.planWeek(
                profile = UserProfile(),
                library = overloadLibrary(),
                existing = emptyList(),
                history = listOf(
                    ExerciseProgress(
                        exerciseId = 2L,
                        lastSetsCompleted = 3,
                        lastTargetSets = 3,
                        lastRpe = rpe,
                        lastWeightKg = 40f,
                    ),
                ),
                today = today,
            )
            val item = proposal.itemFor(2L)!!
            assertEquals("RPE $rpe（已吃力）→ 维持重量", 40f, item.targetWeightKg!!, 0.0001f)
            assertEquals("RPE $rpe → 理由为 MAINTAIN", PlanReason.MAINTAIN, item.reason)
        }
    }

    @Test
    fun overload_rpe9OrHigher_maintainsConservatively() {
        // §4.4 表格只覆盖到 8；≥9 属表格外情形，必须保守维持、绝不加重。
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = overloadLibrary(),
            existing = emptyList(),
            history = listOf(
                ExerciseProgress(
                    exerciseId = 2L,
                    lastSetsCompleted = 3,
                    lastTargetSets = 3,
                    lastRpe = 9,
                    lastWeightKg = 40f,
                ),
            ),
            today = today,
        )
        val item = proposal.itemFor(2L)!!
        assertEquals(40f, item.targetWeightKg!!, 0.0001f)
        assertEquals(PlanReason.MAINTAIN, item.reason)
    }

    @Test
    fun overload_setsNotCompleted_maintainsWeight() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = overloadLibrary(),
            existing = emptyList(),
            history = listOf(
                ExerciseProgress(
                    exerciseId = 2L,
                    lastSetsCompleted = 2,
                    lastTargetSets = 3,
                    lastRpe = 3,
                    lastWeightKg = 40f,
                ),
            ),
            today = today,
        )
        val item = proposal.itemFor(2L)!!
        assertEquals("没做满 → 维持重量，先把组次做满", 40f, item.targetWeightKg!!, 0.0001f)
        assertEquals(PlanReason.MAINTAIN, item.reason)
    }

    @Test
    fun overload_completedWithoutRpe_doesNotGuess_andMaintains() {
        // 「待评级（不猜）」：组数做满但没有 RPE → 维持，绝不擅自加重。
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = overloadLibrary(),
            existing = emptyList(),
            history = listOf(
                ExerciseProgress(
                    exerciseId = 2L,
                    lastSetsCompleted = 3,
                    lastTargetSets = 3,
                    lastRpe = null,
                    lastWeightKg = 40f,
                ),
            ),
            today = today,
        )
        val item = proposal.itemFor(2L)!!
        assertEquals(40f, item.targetWeightKg!!, 0.0001f)
        assertEquals("无 RPE → 不猜，维持", PlanReason.MAINTAIN, item.reason)
    }

    @Test
    fun overload_bodyweightWithoutWeight_addsOneSetInstead() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = overloadLibrary(),
            existing = emptyList(),
            history = listOf(
                ExerciseProgress(
                    exerciseId = 2L,
                    lastSetsCompleted = 3,
                    lastTargetSets = 3,
                    lastRpe = 5,
                    lastWeightKg = null,   // 自重动作：无重量可加
                ),
            ),
            today = today,
        )
        val item = proposal.itemFor(2L)!!
        assertEquals("自重动作加重方式 = 加组", 4, item.targetSets)
        assertEquals(PlanReason.PROGRESSIVE_OVERLOAD, item.reason)
        assertTrue(
            proposal.notes.any {
                it.exerciseId == 2L &&
                    it.detail is PlanNoteDetail.SetsDelta &&
                    (it.detail as PlanNoteDetail.SetsDelta).oldSets == 3 &&
                    it.detail.newSets == 4
            },
        )
    }

    @Test
    fun overload_noHistory_leavesReasonToPosition() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = overloadLibrary(),
            existing = emptyList(),
            history = emptyList(),
            today = today,
        )
        // 无历史 → 不推断，理由按位置给（首位 PRIMARY_LIFT / 其余 SUPPLEMENT）。
        for (item in proposal.days.flatMap { it.items }) {
            assertTrue(
                item.reason == PlanReason.PRIMARY_LIFT || item.reason == PlanReason.SUPPLEMENT,
            )
        }
    }

    // ---------------- 不变量 7：空档案兜底 ----------------

    @Test
    fun planWeek_emptyProfile_producesUsableProposalWithoutCrashing() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = library,
            existing = emptyList(),
            history = emptyList(),
            today = today,
        )

        assertTrue("空档案也必须产出可用草案（不得崩、不得为空）", proposal.days.isNotEmpty())
        assertTrue(proposal.preservedUserEditedIds.isEmpty())
        for (day in proposal.days) {
            assertTrue(day.dayOfWeek in 1..7)
            for (item in day.items) {
                assertTrue("目标组数不得为 0 或负数", item.targetSets >= 1)
                assertTrue("目标次数不得为 0 或负数", item.targetReps >= 1)
            }
        }
    }

    @Test
    fun planWeek_emptyLibrary_returnsEmptyProposalWithoutCrashing() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(equipment = setOf(Equipment.BARBELL)),
            library = emptyList(),
            existing = emptyList(),
            today = today,
        )
        assertTrue("空动作库 → 草案为空但不崩", proposal.days.isEmpty())
        assertTrue(proposal.notes.isEmpty())
    }

    @Test
    fun planWeek_defaultTrainingDays_isThree() {
        assertEquals("设计钉死：每周默认 3 天", 3, LocalRuleAdvisor.DEFAULT_TRAINING_DAYS)

        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = library,
            existing = emptyList(),
            today = today,
        )
        assertEquals(LocalRuleAdvisor.DEFAULT_TRAINING_DAYS, proposal.days.size)
        assertEquals(
            "训练日固定在周一 / 周三 / 周五（集合不变 → 重复生成落在同一批槽位，幂等）",
            setOf(1, 3, 5),
            proposal.days.map { it.dayOfWeek }.toSet(),
        )
    }

    // ---------------- 不变量 8：零随机（确定性）----------------

    @Test
    fun planWeek_sameInputYieldsSameOutput() {
        val profile = UserProfile(injuryAreas = setOf(InjuryArea.KNEE))
        val existing = listOf(WeekPlan(id = 7L, exerciseId = 2L, dayOfWeek = 3, isUserEdited = true))
        val history = listOf(
            ExerciseProgress(
                exerciseId = 1L,
                lastSetsCompleted = 3,
                lastTargetSets = 3,
                lastRpe = 5,
                lastWeightKg = 30f,
            ),
        )

        val first = LocalRuleAdvisor.planWeek(profile, library, existing, history, today)
        val second = LocalRuleAdvisor.planWeek(profile, library, existing, history, today)

        assertEquals("零随机：同输入必同输出（可复现、可审计）", first, second)
    }

    // ---------------- 不变量 3（规则层部分）：suggestExercises 幂等去重 ----------------

    @Test
    fun suggestExercises_skipsNamesAlreadyInLibrary() {
        val candidates = listOf(
            exercise(1L, ExerciseCategory.BODYWEIGHT, "核心"),
            exercise(2L, ExerciseCategory.STRENGTH, "肩部"),
            exercise(3L, ExerciseCategory.CARDIO, "有氧"),
        )
        val existing = listOf(exercise(101L, ExerciseCategory.BODYWEIGHT, "核心"))

        val suggestions = LocalRuleAdvisor.suggestExercises(
            // 给足器械：本用例只验证「按 name 去重」，不掺入器械过滤（后者另有单测）。
            profile = UserProfile(equipment = setOf(Equipment.DUMBBELL)),
            candidates = candidates.map { it.copy(name = "cand-${it.id}") },
            existing = existing.map { it.copy(name = "cand-1") },
        )

        assertFalse(
            "库里已有的名字不得再推荐（按 name 去重 = 幂等键）",
            suggestions.any { it.name == "cand-1" },
        )
        assertEquals(setOf("cand-2", "cand-3"), suggestions.map { it.name }.toSet())
    }

    @Test
    fun suggestExercises_excludesInjuredAndUnavailableStrength() {
        val candidates = listOf(
            exercise(1L, ExerciseCategory.BODYWEIGHT, "腿部").copy(name = "leg"),
            exercise(2L, ExerciseCategory.STRENGTH, "肩部").copy(name = "press"),
            exercise(3L, ExerciseCategory.CARDIO, "有氧").copy(name = "cardio"),
        )

        val suggestions = LocalRuleAdvisor.suggestExercises(
            profile = UserProfile(
                injuryAreas = setOf(InjuryArea.KNEE),   // 排除腿部
                equipment = emptySet(),                  // 空集 → 排除力量动作
            ),
            candidates = candidates,
            existing = emptyList(),
        )

        assertEquals(
            "伤病排除腿部 + 无器械排除力量 → 只剩有氧",
            listOf("cardio"),
            suggestions.map { it.name },
        )
    }

    @Test
    fun suggestExercises_noteKeyIsResourceNameNotChinese() {
        val candidates = listOf(
            exercise(1L, ExerciseCategory.CARDIO, "有氧").copy(name = "cardio"),
        )
        val suggestions = LocalRuleAdvisor.suggestExercises(
            profile = UserProfile(),
            candidates = candidates,
            existing = emptyList(),
        )
        val suggestion = suggestions.single()
        assertTrue(
            "noteKey 必须是 strings.xml 的资源名（不得内联中文）",
            suggestion.noteKey.matches(Regex("[a-z0-9_]+")),
        )
    }

    // ---------------- 生成依据 basis（v1.9）----------------

    @Test
    fun planWeek_localBasis_includesExpectedKeys_andAnalysisIsNull() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(
                gender = com.ironhabit.app.domain.model.Gender.MALE,
                age = 30,
                heightCm = 175,
                goal = com.ironhabit.app.domain.model.Goal.BULK,
                injuryAreas = setOf(InjuryArea.SHOULDER),
                equipment = setOf(Equipment.DUMBBELL),
            ),
            library = overloadLibrary(),
            existing = emptyList(),
            history = listOf(
                ExerciseProgress(
                    exerciseId = 2L,
                    lastSetsCompleted = 3,
                    lastTargetSets = 3,
                    lastRpe = 5,
                    lastWeightKg = 40f,
                ),
            ),
            today = today,
        )

        val keys = proposal.basis.map { it.key }
        assertTrue("应包含 basis_frequency", "basis_frequency" in keys)
        assertTrue("应包含 basis_goal", "basis_goal" in keys)
        assertTrue("应包含 basis_injury（有伤病）", "basis_injury" in keys)
        assertTrue("应包含 basis_equipment（有器械）", "basis_equipment" in keys)
        assertTrue("应包含 basis_overload（PROGRESSIVE_OVERLOAD 触发）", "basis_overload" in keys)
        assertTrue("本地规则不产出自由文本 analysis", proposal.analysis == null)
    }

    // ---------------- C1：每周必含「背」+ 单日动作不再同质化 ----------------

    @Test
    fun planWeek_alwaysSchedulesUpperPullDay() {
        // 修复 C1：旧实现用固定表按 index 取重点（每周只排 3 天 → index 只到 0/1/2），
        // 下标 3 的「背（UPPER_PULL）」永远排不到。现要求每周**必含**背日。
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = library,
            existing = emptyList(),
            today = today,
        )
        assertTrue(
            "每周训练计划必须包含一个「上肢拉（背）」训练日",
            proposal.days.any { it.focus == TrainingFocus.UPPER_PULL },
        )
    }

    @Test
    fun planWeek_singleDayExerciseSelection_isNotHomogenizedByPrimaryMuscle() {
        // 构造「同一主肌群动作数 > 每日上限」的池：4 个胸部 + 1 个肩部。
        // 背日这些动作都不匹配背的肌群 → 走"全天可用动作"兜底池（≥ 每日上限 4）。
        // 旧实现 `pool.take(4)` 会把前 4 个（全是胸部）排满一天 → 同质化；
        // 新实现优先"一主肌群一个"，单日内至少出现 2 个不同主肌群。
        val monoLibrary = listOf(
            exercise(1L, ExerciseCategory.BODYWEIGHT, "胸部"),
            exercise(2L, ExerciseCategory.BODYWEIGHT, "胸部"),
            exercise(3L, ExerciseCategory.BODYWEIGHT, "胸部"),
            exercise(4L, ExerciseCategory.BODYWEIGHT, "胸部"),
            exercise(5L, ExerciseCategory.BODYWEIGHT, "肩部"),
        )

        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = monoLibrary,
            existing = emptyList(),
            today = today,
        )

        val pullDay = proposal.days.first { it.focus == TrainingFocus.UPPER_PULL }
        val groups = pullDay.items.mapNotNull { item ->
            monoLibrary.first { it.id == item.exerciseId }.primaryMuscleGroup
        }
        assertTrue("单日动作不得全是同一主肌群（去同质化）", groups.distinct().size >= 2)
    }

    // ---------------- C3：有氧时长带入生成链路 ----------------

    @Test
    fun planWeek_cardioDurationFlowsIntoDraft_inMinutes() {
        val cardioLibrary = listOf(
            exercise(1L, ExerciseCategory.CARDIO, "有氧", durationSec = 1200), // 20 分钟
            exercise(2L, ExerciseCategory.CARDIO, "有氧", durationSec = 600),  // 10 分钟
            exercise(3L, ExerciseCategory.CARDIO, "有氧", durationSec = 45),   // <1 分钟 → null
            exercise(4L, ExerciseCategory.CARDIO, "有氧", durationSec = null), // 无时长 → null
        )
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = cardioLibrary,
            existing = emptyList(),
            today = today,
        )
        assertEquals("1200 秒 → 20 分钟", 20, proposal.itemFor(1L)!!.targetDurationMin)
        assertEquals("600 秒 → 10 分钟", 10, proposal.itemFor(2L)!!.targetDurationMin)
        assertNull("不足 1 分钟不写 0（记 null）", proposal.itemFor(3L)!!.targetDurationMin)
        assertNull("无默认时长 → null", proposal.itemFor(4L)!!.targetDurationMin)
    }

    // ---------------- C4：无目标组数（未关联计划）→ 不猜「做满」→ 维持 ----------------

    @Test
    fun overload_nullTargetSets_isNotAssumedCompleted_andMaintains() {
        // 修复 C4：旧实现把缺失的目标组数兜底为常量 3 → 完成 3 组即被判"做满"，
        // 叠加 RPE ≤ 6「有余量」→ 凭空加重 2.5kg。现应保持重量、理由为 MAINTAIN。
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(),
            library = overloadLibrary(),
            existing = emptyList(),
            history = listOf(
                ExerciseProgress(
                    exerciseId = 2L,
                    lastSetsCompleted = 3,
                    lastTargetSets = null,   // 未关联计划 → 无可信目标
                    lastRpe = 5,
                    lastWeightKg = 40f,
                ),
            ),
            today = today,
        )
        val item = proposal.itemFor(2L)!!
        assertEquals("无目标组数 → 不得假定做满 → 维持重量（不加重）", 40f, item.targetWeightKg!!, 0.0001f)
        assertEquals(PlanReason.MAINTAIN, item.reason)
    }

    // ---------------- D1：内置库必须含「无需器械」的背 / 后肩动作 ----------------

    /**
     * 真实内置库。
     *
     * ⚠️ [BuiltInExercises.all] 的每条 `id` 均为 `0`（交给 Room 自增），而 `planWeek` 会
     * 过滤掉 `id == 0` 的行，故这里按序补上 1..N 的自增 id，模拟"已播种进库"的真实数据。
     */
    private fun realLibrary(): List<Exercise> =
        BuiltInExercises.all.mapIndexed { index, exercise -> exercise.copy(id = index + 1L) }

    @Test
    fun builtInLibrary_pinsDocumentedCounts() {
        // 修复 D1 的 KDoc 同步：文件头明写「共 51 个 / 自重 19」——本断言锁死 KDoc 与实盘不漂移。
        assertEquals("内置动作总数（KDoc：共 51 个）", 51, BuiltInExercises.all.size)
        assertEquals(
            "自重组条目数（KDoc：自重 19）",
            19,
            BuiltInExercises.all.count { it.category == ExerciseCategory.BODYWEIGHT },
        )
    }

    @Test
    fun builtInLibrary_containsBodyweightBackAndRearDeltExercises() {
        // 修复 D1：无器械用户也必须有「不需要任何器械」的背 / 后肩动作可练。
        val bodyweightBack = BuiltInExercises.all.filter { exercise ->
            exercise.category == ExerciseCategory.BODYWEIGHT &&
                exercise.muscleGroups.any { group -> group == "背部" || group == "后肩" }
        }
        assertTrue(
            "内置库必须含 ≥3 个自重（无需器械）的背 / 后肩动作，否则无器械用户永远排不到背",
            bodyweightBack.size >= 3,
        )
    }

    @Test
    fun planWeek_noEquipment_stillSchedulesBackOrRearDeltWork() {
        // 修复 D1 的端到端回归：无器械 + 真实内置库 → 一周内必须能练到背 / 后肩。
        val library = realLibrary()
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(equipment = emptySet()),   // 空集 → {NONE}，仅自重
            library = library,
            existing = emptyList(),
            history = emptyList(),
            today = today,
        )
        val scheduledGroups = proposal.days
            .flatMap { day -> day.items }
            .mapNotNull { item -> library.first { it.id == item.exerciseId }.primaryMuscleGroup }
        assertTrue(
            "无器械用户的本周计划必须包含背 / 后肩动作（补自重背动作前此处必然为空）",
            scheduledGroups.any { group -> group == "背部" || group == "后肩" },
        )
    }

    // ---------------- D2：跨重点补足（任一天内主肌群不重复）----------------

    @Test
    fun planWeek_overManyWeeks_neverDuplicatesPrimaryMuscleWithinADay() {
        // 修复 D2：≥8 周 × 7 个不同 today × {无器械, 有哑铃}，逐日断言
        //「同一主肌群不得出现两次」。旧实现（第二轮从**重点命中池**补足）在"只有 3 个可匹配
        // 主肌群"时会重复该肌群（背日排两次背）→ 原为 112 处违规；现要求 **0**。
        val library = realLibrary()
        val baseEpochDay: Int = LocalDate(2026, 1, 1).toEpochDays()
        val profiles = listOf(
            UserProfile(equipment = emptySet()),
            UserProfile(equipment = setOf(Equipment.DUMBBELL)),
        )

        var duplicateExcess = 0
        var scheduledDays = 0
        for (week in 0 until 8) {
            for (offset in 0 until 7) {
                val cursor: LocalDate = LocalDate.fromEpochDays(baseEpochDay + week * 7 + offset)
                for (profile in profiles) {
                    val proposal = LocalRuleAdvisor.planWeek(
                        profile = profile,
                        library = library,
                        existing = emptyList(),
                        history = emptyList(),
                        today = cursor,
                    )
                    for (day in proposal.days) {
                        scheduledDays++
                        assertTrue("每天动作数不得为 0", day.items.isNotEmpty())
                        assertTrue("每天动作数不得超过上限 4", day.items.size <= 4)
                        val groups = day.items.map { item ->
                            library.first { it.id == item.exerciseId }.primaryMuscleGroup
                        }
                        duplicateExcess += groups.size - groups.distinct().size
                    }
                }
            }
        }

        // 真实内置库主肌群种类足够（12–15 种）→ 跨重点补足后每日 4 条必为 4 个不同主肌群。
        assertTrue("回归样本应覆盖到多天（否则断言无意义）", scheduledDays >= 8 * 7 * 2 * 3)
        assertEquals("任一天内同一主肌群每天至多出现一次 → 0 处重复", 0, duplicateExcess)
    }

    // ================= P1：档案真正参与排课 =================
    //
    // 下面这组用例锁死"档案字段 → 排课结果"的端到端行为。
    // 单看 `ProfileLoadPolicyTest` 只能证明"参数算对了"，这里证明**参数真的被用了**。

    /** 训练日集合（周一为 1）。 */
    private fun PlanProposal.daySet(): Set<Int> = days.map { it.dayOfWeek }.toSet()

    private fun PlanProposal.itemCountOf(exerciseId: Long): Int =
        days.sumOf { day -> day.items.count { it.exerciseId == exerciseId } }

    private fun PlanProposal.usedExerciseIds(): Set<Long> =
        days.flatMap { it.items }.map { it.exerciseId }.toSet()

    /** 带"安全替代肌群"的库：腿部（膝伤会挡掉）+ 臀部 / 核心（安全）。 */
    private val kneeSwapLibrary: List<Exercise> = listOf(
        exercise(1L, ExerciseCategory.BODYWEIGHT, "腿部"),
        exercise(2L, ExerciseCategory.BODYWEIGHT, "臀部"),
        exercise(3L, ExerciseCategory.BODYWEIGHT, "核心"),
        exercise(4L, ExerciseCategory.BODYWEIGHT, "胸部"),
    )

    @Test
    fun planWeek_trainingDaysPerWeek3to6_usesConfiguredDaySets() {
        for (days in 3..6) {
            val proposal = LocalRuleAdvisor.planWeek(
                profile = UserProfile(trainingDaysPerWeek = days),
                library = library,
                existing = emptyList(),
                today = today,
            )
            assertEquals("档案设 $days 天 → 就排 $days 天", days, proposal.days.size)
            assertEquals(
                "训练日集合由天数决定（周日恒为休息日）",
                LocalRuleAdvisor.TRAINING_DAY_SETS.getValue(days).toSet(),
                proposal.daySet(),
            )
            assertTrue(
                "每周仍必须含一个「上肢拉（背）」日",
                proposal.days.any { it.focus == TrainingFocus.UPPER_PULL },
            )
            assertEquals(
                "basis_frequency 要报出实际天数",
                listOf(days),
                proposal.basis.first { it.key == "basis_frequency" }.args,
            )
        }
    }

    @Test
    fun planWeek_dirtyTrainingDays_isClampedIntoRange() {
        val tooMany = LocalRuleAdvisor.planWeek(
            profile = UserProfile(trainingDaysPerWeek = 99),
            library = library,
            existing = emptyList(),
            today = today,
        )
        assertEquals("越界天数钳制到 6", 6, tooMany.days.size)

        val tooFew = LocalRuleAdvisor.planWeek(
            profile = UserProfile(trainingDaysPerWeek = 0),
            library = library,
            existing = emptyList(),
            today = today,
        )
        assertEquals("越界天数钳制到 3", 3, tooFew.days.size)
    }

    @Test
    fun planWeek_moreDays_neverSchedulesSameMuscleOnConsecutiveDays() {
        // 4/5/6 天时必然出现"连着两天"，此时两天的肌群标签必须不相交
        // —— **有氧除外**：有氧配额（每周至少 N 个）优先于"不连排"，而且有氧不需要 48 小时恢复。
        val cardio = "有氧"
        val profile = UserProfile(trainingDaysPerWeek = 6)
        val library = realLibrary()
        var checkedPairs = 0

        for (offset in 0 until 14) {
            val cursor = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + offset)
            val proposal = LocalRuleAdvisor.planWeek(
                profile = profile,
                library = library,
                existing = emptyList(),
                today = cursor,
            )
            val byDay = proposal.days.associateBy { it.dayOfWeek }
            for (day in 1..5) {
                val current = byDay[day] ?: continue
                val next = byDay[day + 1] ?: continue
                checkedPairs++
                fun groupsOf(planned: com.ironhabit.app.domain.model.PlannedDay): Set<String> =
                    planned.items
                        .flatMap { item -> library.first { ex -> ex.id == item.exerciseId }.muscleGroups }
                        .filter { tag -> tag != cardio }
                        .toSet()

                val currentGroups = groupsOf(current)
                val nextGroups = groupsOf(next)
                assertTrue(
                    "连着两天（周$day/周${day + 1}）不得练到同一块肌群：" +
                        "$currentGroups ∩ $nextGroups（focus=${current.focus}/${next.focus}）",
                    currentGroups.intersect(nextGroups).isEmpty(),
                )
            }
        }
        assertTrue("样本应覆盖到连续训练日（否则断言无意义）", checkedPairs > 0)
    }

    @Test
    fun planWeek_consecutiveDays_haveDisjointTrainingFocuses() {
        // 规则层的硬保证（也是上面那条的"设计依据"）：相邻两天的**训练重点**肌群标签不相交。
        for (days in 3..6) {
            for (offset in 0 until 14) {
                val cursor = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + offset)
                val schedule = LocalRuleAdvisor.planWeek(
                    profile = UserProfile(trainingDaysPerWeek = days),
                    library = realLibrary(),
                    existing = emptyList(),
                    today = cursor,
                ).days

                for (index in 0 until schedule.size - 1) {
                    val current = schedule[index]
                    val next = schedule[index + 1]
                    val currentTags = LocalRuleAdvisor.FOCUS_TAGS.getValue(current.focus)
                    val nextTags = LocalRuleAdvisor.FOCUS_TAGS.getValue(next.focus)
                    assertTrue(
                        "训练重点不得连排同一肌群：周${current.dayOfWeek}(${current.focus}) → " +
                            "周${next.dayOfWeek}(${next.focus})",
                        currentTags.intersect(nextTags).isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun planWeek_goalDrivesRepsRange() {
        val bulk = LocalRuleAdvisor.planWeek(
            profile = UserProfile(goal = Goal.BULK),
            library = library,
            existing = emptyList(),
            today = today,
        )
        val cut = LocalRuleAdvisor.planWeek(
            profile = UserProfile(goal = Goal.CUT),
            library = library,
            existing = emptyList(),
            today = today,
        )

        for (item in bulk.days.flatMap { it.items }) {
            assertTrue("增肌：每组次数必须落在 8–12（实际 ${item.targetReps}）", item.targetReps in 8..12)
        }
        for (item in cut.days.flatMap { it.items }) {
            assertTrue("减脂：每组次数必须落在 12–15（实际 ${item.targetReps}）", item.targetReps in 12..15)
        }
        assertEquals(
            "basis_volume 要报出「次数区间 + 组数区间」4 个参数",
            listOf(8, 12, 3, 4),
            bulk.basis.first { it.key == "basis_volume" }.args,
        )
    }

    @Test
    fun planWeek_outOfRangeDefaultReps_isClampedIntoGoalRange() {
        val highReps = listOf(exercise(1L, ExerciseCategory.BODYWEIGHT, "胸部", reps = 30))
        val cut = LocalRuleAdvisor.planWeek(
            profile = UserProfile(goal = Goal.CUT),
            library = highReps,
            existing = emptyList(),
            today = today,
        )
        assertEquals(
            "动作自带的 30 次被夹进减脂区间 12–15",
            15,
            cut.itemFor(1L)!!.targetReps,
        )
    }

    @Test
    fun planWeek_goalDrivesWeightStep() {
        // 同样的历史（做满 + RPE 5 + 上次 40kg），增肌 +2.5kg、减脂 +1.25kg。
        val history = listOf(
            ExerciseProgress(
                exerciseId = 2L,
                lastSetsCompleted = 3,
                lastTargetSets = 3,
                lastRpe = 5,
                lastWeightKg = 40f,
            ),
        )
        val bulk = LocalRuleAdvisor.planWeek(
            profile = UserProfile(goal = Goal.BULK),
            library = overloadLibrary(),
            existing = emptyList(),
            history = history,
            today = today,
        )
        val cut = LocalRuleAdvisor.planWeek(
            profile = UserProfile(goal = Goal.CUT),
            library = overloadLibrary(),
            existing = emptyList(),
            history = history,
            today = today,
        )

        assertEquals(42.5f, bulk.itemFor(2L)!!.targetWeightKg!!, 0.0001f)
        assertEquals("减脂期加重更保守：只 +1.25kg", 41.25f, cut.itemFor(2L)!!.targetWeightKg!!, 0.0001f)
    }

    @Test
    fun planWeek_bodyFatHigh_reportsBasisAndRaisesCardio() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(
                gender = com.ironhabit.app.domain.model.Gender.MALE,
                bodyFatPct = 30f,
                goal = Goal.MAINTAIN,
            ),
            library = library,
            existing = emptyList(),
            today = today,
        )
        val keys = proposal.basis.map { it.key }

        assertTrue("体脂偏高必须写进生成依据", "basis_bodyfat_high" in keys)
        assertEquals(
            "依据里的有氧数 = 参数里的有氧数（2）",
            listOf(2),
            proposal.basis.first { it.key == "basis_bodyfat_high" }.args,
        )
        assertTrue(
            "每天 1 个有氧 → 一周 3 个 ≥ 目标 2 个",
            proposal.itemCountOf(6L) >= 2,
        )
    }

    @Test
    fun planWeek_weightAboveTarget_reportsCutRule() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(goal = Goal.MAINTAIN, goalWeightKg = 70f),
            library = library,
            existing = emptyList(),
            today = today,
            bodyWeightKg = 82f,
        )
        val keys = proposal.basis.map { it.key }

        assertTrue("体重高于目标体重要写进依据", "basis_weight_cut" in keys)
        assertFalse("方向相反的那条不得出现", "basis_weight_gain" in keys)
        assertTrue("有氧按上调后的 2 个安排", proposal.itemCountOf(6L) >= 2)
    }

    @Test
    fun planWeek_weightBelowTarget_reportsGainRuleAndRaisesSets() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(goal = Goal.MAINTAIN, goalWeightKg = 80f),
            library = library,
            existing = emptyList(),
            today = today,
            bodyWeightKg = 70f,
        )
        val keys = proposal.basis.map { it.key }

        assertTrue("离目标体重还有差距要写进依据", "basis_weight_gain" in keys)
        assertEquals(
            "依据里的组数上限 = 上调后的 4",
            listOf(4),
            proposal.basis.first { it.key == "basis_weight_gain" }.args,
        )
    }

    @Test
    fun planWeek_age50Plus_reducesItemsPerDay_andReportsBasis() {
        val young = LocalRuleAdvisor.planWeek(
            profile = UserProfile(age = 30),
            library = realLibrary(),
            existing = emptyList(),
            today = today,
        )
        val senior = LocalRuleAdvisor.planWeek(
            profile = UserProfile(age = 55),
            library = realLibrary(),
            existing = emptyList(),
            today = today,
        )
        val keys = senior.basis.map { it.key }

        assertTrue("年龄 ≥50 必须写进依据", "basis_age_volume" in keys)
        assertTrue("年龄 ≥40 必须写进恢复建议", "basis_recovery_age" in keys)
        assertEquals(listOf(55, 3), senior.basis.first { it.key == "basis_age_volume" }.args)
        for (day in senior.days) {
            assertTrue(
                "55 岁：每天动作数下调到 3（实际 ${day.items.size}）",
                day.items.size <= 3,
            )
        }
        assertTrue(
            "同一档案下年轻人每天仍是 4 个动作（对照组）",
            young.days.all { it.items.size == 4 },
        )
    }

    @Test
    fun planWeek_injury_substitutesSafeNeighbouringMuscle_andMarksInjurySafe() {
        val profile = UserProfile(
            injuryAreas = setOf(InjuryArea.KNEE),
            equipment = setOf(Equipment.DUMBBELL),
        )
        var plansWithSubstitution = 0
        var markedItems = 0

        // 换 7 个不同的 today：训练重点按周轮换，一周样本覆盖不到所有重点组合。
        for (offset in 0 until 7) {
            val cursor = LocalDate.fromEpochDays(LocalDate(2026, 9, 14).toEpochDays() + offset)
            val proposal = LocalRuleAdvisor.planWeek(
                profile = profile,
                library = kneeSwapLibrary,
                existing = emptyList(),
                today = cursor,
            )

            assertFalse(
                "会刺激膝的「腿部」动作仍必须被排除（排除这一层没有放松）",
                1L in proposal.usedExerciseIds(),
            )

            val marked = proposal.notes.filter { it.kind == PlanReason.INJURY_SAFE }
            if (marked.isNotEmpty()) {
                plansWithSubstitution++
                markedItems += marked.size
                assertTrue(
                    "被标成「安全替代」的动作必须真的来自安全邻近肌群（臀部 2 / 核心 3）",
                    marked.all { it.exerciseId == 2L || it.exerciseId == 3L },
                )
                assertTrue(
                    "有替代就要写进生成依据，且数量与标注一致",
                    proposal.basis.any {
                        it.key == "basis_injury_swap" && it.args.single() == marked.size
                    },
                )
            }
        }

        assertTrue("7 个样本里至少要有一次「重点被伤病挡掉 → 换成安全肌群」", plansWithSubstitution > 0)
        assertTrue("替代标注不能是空跑", markedItems > 0)
    }

    @Test
    fun planWeek_noInjury_producesNoSubstitutionAndNoSwapBasis() {
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(equipment = setOf(Equipment.DUMBBELL)),
            library = kneeSwapLibrary,
            existing = emptyList(),
            today = today,
        )

        assertFalse(
            "没伤病就不该出现「安全替代」理由（否则是假的理由）",
            proposal.notes.any { it.kind == PlanReason.INJURY_SAFE },
        )
        assertFalse(
            "没伤病就不该出现 basis_injury_swap",
            proposal.basis.any { it.key == "basis_injury_swap" },
        )
        assertFalse("没伤病就不该出现 basis_injury", proposal.basis.any { it.key == "basis_injury" })
    }

    @Test
    fun planWeek_handEditedSlot_stillWinsOverSubstitution() {
        // P1 新增的"替代补位"绝不能写进用户手改过的槽位。
        val edited = WeekPlan(id = 900L, exerciseId = 2L, dayOfWeek = 1, isUserEdited = true)
        val proposal = LocalRuleAdvisor.planWeek(
            profile = UserProfile(
                injuryAreas = setOf(InjuryArea.KNEE),
                equipment = setOf(Equipment.DUMBBELL),
            ),
            library = kneeSwapLibrary,
            existing = listOf(edited),
            today = today,
        )
        assertFalse(
            "周一 × 臀部（动作2）是手改槽位 → 替代补位也不得写它",
            proposal.days.firstOrNull { it.dayOfWeek == 1 }
                ?.items
                ?.any { it.exerciseId == 2L } == true,
        )
    }

    @Test
    fun planWeek_bodyWeightChange_isTheOnlyDifferenceInResult() {
        // 同一档案、同一库，只差"当前体重" → 结果必须不同（证明体重真的进了规则）。
        val profile = UserProfile(goal = Goal.MAINTAIN, goalWeightKg = 70f)
        val heavy = LocalRuleAdvisor.planWeek(
            profile = profile,
            library = realLibrary(),
            existing = emptyList(),
            today = today,
            bodyWeightKg = 82f,
        )
        val atTarget = LocalRuleAdvisor.planWeek(
            profile = profile,
            library = realLibrary(),
            existing = emptyList(),
            today = today,
            bodyWeightKg = 70f,
        )

        assertTrue(
            "体重 82kg（要减 12kg）必须写进依据，70kg（已到位）不写",
            heavy.basis.any { it.key == "basis_weight_cut" } &&
                atTarget.basis.none { it.key == "basis_weight_cut" },
        )
    }
}
