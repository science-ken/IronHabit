package com.ironhabit.app.domain.ai

import com.ironhabit.app.data.preset.BuiltInExercises
import com.ironhabit.app.domain.model.Equipment
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseProgress
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
}
