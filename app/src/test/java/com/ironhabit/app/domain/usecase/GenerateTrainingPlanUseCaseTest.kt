package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.LocalRuleAdvisor
import com.ironhabit.app.domain.ai.PlanAdvisor
import com.ironhabit.app.domain.model.AdviceSource
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.PlanProposal
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GenerateTrainingPlanUseCase] 单测 —— 覆盖派工单的 **不变量 1 / 2**。
 *
 * 1. **手改行保护**：`isUserEdited == true` 的行（**含软删除行**）完整保留；
 * 2. **禁用 REPLACE / 禁用"先删再建"**：本用例**没有任何 DELETE 调用**，
 *    写入统一走显式 upsert（`PlanRepository.upsertGenerated`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenerateTrainingPlanUseCaseTest {

    private val exerciseRepository: ExerciseRepository = mockk(relaxed = true)
    private val planRepository: PlanRepository = mockk(relaxed = true)
    private val checkInRepository: CheckInRepository = mockk(relaxed = true)
    private val bodyMetricRepository: BodyMetricRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(FIXED_MILLIS)
    }

    private val useCase = GenerateTrainingPlanUseCase(
        planRepository = planRepository,
        exerciseRepository = exerciseRepository,
        checkInRepository = checkInRepository,
        bodyMetricRepository = bodyMetricRepository,
        settingsRepository = settingsRepository,
        advisor = LocalRuleAdvisor,
        clock = fixedClock,
        timeZone = TimeZone.UTC,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /**
     * P3：生成**必须落到目标周**，而不是落到"每周相同"那份（`weekStartEpochDay = 0`）。
     *
     * 这是回归防线：如果哪天有人把 `WeekPlan(weekStartEpochDay = targetWeek)` 那一行删掉，
     * "给下周生成计划"就会**偷偷改掉每周循环的那份计划** —— 用户下周看着没问题，
     * 但他"每周相同"的那份已经被换掉了。
     */
    @Test
    fun generateTrainingPlan_writesIntoTheTargetWeek_notIntoTheRepeatPlan() = runTest {
        stubDefaults(existing = emptyList())
        val targetWeek: Long = 20_710L   // 任意一个真实的周一 epochDay

        val written = slot<List<WeekPlan>>()
        coVerify(exactly = 0) { planRepository.upsertGenerated(any()) }

        useCase(targetWeek)

        coVerify(exactly = 1) { planRepository.upsertGenerated(capture(written)) }
        assertTrue("至少要写出几条计划", written.captured.isNotEmpty())
        assertTrue(
            "每一条都必须带目标周（否则会写进「每周相同」那份）",
            written.captured.all { plan -> plan.weekStartEpochDay == targetWeek },
        )
        coVerify(exactly = 1) { planRepository.getRowsForWeek(targetWeek) }
    }

    private val library: List<Exercise> = listOf(
        exercise(1L, ExerciseCategory.BODYWEIGHT, "腿部"),
        exercise(2L, ExerciseCategory.BODYWEIGHT, "胸部"),
        exercise(3L, ExerciseCategory.BODYWEIGHT, "背部"),
        exercise(4L, ExerciseCategory.BODYWEIGHT, "腹部"),
        exercise(5L, ExerciseCategory.BODYWEIGHT, "核心"),
        exercise(6L, ExerciseCategory.CARDIO, "有氧", durationSec = 1200),
        exercise(7L, ExerciseCategory.BODYWEIGHT, "全身"),
    )

    private fun exercise(
        id: Long,
        category: ExerciseCategory,
        muscle: String,
        durationSec: Int? = null,
    ): Exercise = Exercise(
        id = id,
        name = "ex-$id",
        category = category,
        muscleGroups = listOf(muscle),
        isActive = true,
        defaultSets = 3,
        defaultReps = 12,
        defaultDurationSec = durationSec,
    )

    private fun stubDefaults(existing: List<WeekPlan>) {
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        every { exerciseRepository.observeActive() } returns flowOf(library)
        coEvery { planRepository.getRowsForWeek(any()) } returns existing
        every { checkInRepository.latestProgressPerExercise() } returns flowOf(emptyList<ExerciseProgress>())
    }

    // ---------------- 不变量 1：手改行（含软删除行）绝不写入 ----------------

    @Test
    fun generateTrainingPlan_neverWritesIntoUserEditedSlots() = runTest {
        val existing = listOf(
            WeekPlan(id = 100L, exerciseId = 1L, dayOfWeek = 1, isUserEdited = true),
            WeekPlan(id = 200L, exerciseId = 2L, dayOfWeek = 1, isActive = false, isUserEdited = true),
            WeekPlan(id = 300L, exerciseId = 3L, dayOfWeek = 3),                 // 普通行：可被写
        )
        stubDefaults(existing)

        val summary = useCase()

        val slot = slot<List<WeekPlan>>()
        coVerify(exactly = 1) { planRepository.upsertGenerated(capture(slot)) }
        val written = slot.captured

        assertEquals("两条手改行（含 1 条软删除行）都要计入「已保留」", 2, summary.preservedCount)
        assertFalse(
            "手改行槽位（周一 × 动作1）不得被写入 —— 否则覆盖用户改动",
            written.any { it.dayOfWeek == 1 && it.exerciseId == 1L },
        )
        assertFalse(
            "软删除的手改行槽位（周一 × 动作2）不得被写入 —— 否则把用户删掉的那条「复活」",
            written.any { it.dayOfWeek == 1 && it.exerciseId == 2L },
        )
        assertEquals(written.size, summary.writtenCount)
    }

    // ---------------- 不变量 2：禁用 REPLACE / 禁用"先删再建" ----------------

    @Test
    fun generateTrainingPlan_callsNoDeleteAtAll() = runTest {
        stubDefaults(
            existing = listOf(
                WeekPlan(id = 100L, exerciseId = 1L, dayOfWeek = 1, isUserEdited = true),
            ),
        )

        useCase()

        // 「禁用 REPLACE / 先删再建」的可执行断言：本用例对仓库**零删除调用**。
        coVerify(exactly = 0) { planRepository.delete(any()) }
        coVerify(exactly = 0) { planRepository.resetToRecommended(any()) }
        coVerify(exactly = 0) { planRepository.upsert(any()) }
        // 写入一律走显式 upsert 通道。
        coVerify(atLeast = 1) { planRepository.upsertGenerated(any()) }
    }

    @Test
    fun generateTrainingPlan_writesOnlyIntoWritableSlots() = runTest {
        stubDefaults(existing = emptyList())

        val summary = useCase()

        val slot = slot<List<WeekPlan>>()
        coVerify(exactly = 1) { planRepository.upsertGenerated(capture(slot)) }
        val written = slot.captured

        assertTrue("无手改行时应当整批写入", written.isNotEmpty())
        assertEquals(0, summary.preservedCount)
        assertEquals(
            "训练日固定在周一 / 周三 / 周五",
            setOf(1, 3, 5),
            written.map { it.dayOfWeek }.toSet(),
        )
        for (plan in written) {
            assertTrue("目标组数不得为 0 / 负", plan.targetSets >= 1)
            assertTrue("目标次数不得为 0 / 负", plan.targetReps >= 1)
        }
    }

    // ---------------- C2：回收陈旧 AI 行（只停用，不删除；手改行永不回收）----------------

    @Test
    fun generateTrainingPlan_retiresStaleAiRows_butNeverTouchesUserEdited() = runTest {
        // 陈旧行：排在周日（训练日只有 1/3/5）→ 本次绝不会再生成 → 应被回收（停用）。
        val staleAi = WeekPlan(id = 300L, exerciseId = 1L, dayOfWeek = 7, isActive = true, isUserEdited = false)
        // 用户手改行：同样在周日，但被用户动过 → 永不回收。
        val editedSunday = WeekPlan(id = 400L, exerciseId = 2L, dayOfWeek = 7, isActive = true, isUserEdited = true)
        stubDefaults(existing = listOf(staleAi, editedSunday))
        coEvery { planRepository.deactivateGenerated(any()) } returns 1

        val summary = useCase()

        val slot = slot<List<WeekPlan>>()
        coVerify(exactly = 1) { planRepository.deactivateGenerated(capture(slot)) }
        assertTrue("陈旧 AI 行（周日 × 动作1）应被回收", slot.captured.any { it.id == 300L })
        assertFalse("用户手改行（周日 × 动作2）绝不被回收", slot.captured.any { it.id == 400L })
        assertEquals("retiredCount 取自仓库实际停用行数", 1, summary.retiredCount)
        // 回收走的是 UPDATE 通道，仍不得有任何 DELETE。
        coVerify(exactly = 0) { planRepository.delete(any()) }
    }

    @Test
    fun generateTrainingPlan_noStaleRows_reportsZeroRetired() = runTest {
        stubDefaults(existing = emptyList())
        coEvery { planRepository.deactivateGenerated(any()) } returns 0

        val summary = useCase()

        assertEquals("没有陈旧行 → retiredCount = 0", 0, summary.retiredCount)
    }

    // ---------------- B-3：空草案绝不写入、更绝不回收 ----------------

    @Test
    fun generateTrainingPlan_emptyDrafts_skipsWriteAndRetire() = runTest {
        // 一次幻觉/空结果让 advisor 返回空提案 —— 若照旧走回收，
        // 全部启用非手改行都会被停用 = 用户整周计划凭空消失。
        val existing = listOf(
            WeekPlan(id = 100L, exerciseId = 1L, dayOfWeek = 1, isActive = true, isUserEdited = false),
            WeekPlan(id = 200L, exerciseId = 2L, dayOfWeek = 3, isActive = true, isUserEdited = false),
        )
        stubDefaults(existing)

        val emptyAdvisor = mockk<PlanAdvisor> {
            every { source } returns AdviceSource.LOCAL_RULES
            every { lastFallbackReason } returns null
            every {
                planWeek(any(), any(), any(), any(), any(), any())
            } returns PlanProposal(source = AdviceSource.LOCAL_RULES)
            every { suggestExercises(any(), any(), any()) } returns emptyList()
        }
        val isolated = GenerateTrainingPlanUseCase(
            planRepository = planRepository,
            exerciseRepository = exerciseRepository,
            checkInRepository = checkInRepository,
            bodyMetricRepository = bodyMetricRepository,
            settingsRepository = settingsRepository,
            advisor = emptyAdvisor,
            clock = fixedClock,
            timeZone = TimeZone.UTC,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        val summary = isolated()

        coVerify(exactly = 0) { planRepository.upsertGenerated(any()) }
        coVerify(exactly = 0) { planRepository.deactivateGenerated(any()) }
        assertEquals("空草案 → writtenCount = 0", 0, summary.writtenCount)
        assertEquals("空草案 → 绝不回收现有行", 0, summary.retiredCount)
    }

    // ---------------- C3：有氧时长贯通到「写入的周计划」----------------

    @Test
    fun generateTrainingPlan_carriesCardioDurationIntoWrittenPlan() = runTest {
        // 只用有氧动作组成动作库 → 任何训练日都会命中（匹配或兜底），保证有氧必被排入。
        val cardioLibrary = listOf(
            Exercise(
                id = 6L,
                name = "ex-6",
                category = ExerciseCategory.CARDIO,
                muscleGroups = listOf("有氧"),
                isActive = true,
                defaultSets = 1,
                defaultReps = 1,
                defaultDurationSec = 1200,
            ),
        )
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        every { exerciseRepository.observeActive() } returns flowOf(cardioLibrary)
        coEvery { planRepository.getRowsForWeek(any()) } returns emptyList()
        every { checkInRepository.latestProgressPerExercise() } returns flowOf(emptyList<ExerciseProgress>())

        val summary = useCase()

        val cardio = summary.plans.first { it.exerciseId == 6L }
        assertEquals("有氧时长（1200s → 20min）应写入本周计划", 20, cardio.targetDurationMin)
    }

    // ---------------- P0-3：「每周相同」模板里的手改行不得被绕过 ----------------

    /**
     * 本周没有任何启用专属行 → 本周生效计划**来自模板**（`WeekPlanWeekResolver` 逐天覆盖规则）。
     * 此时若为这些天写入专属行，该天从此不再回落模板 → 用户在模板里删掉/换掉的动作
     * 在本周被静默绕过（"删掉的深蹲又回来了"）。修复：模板手改过的天本周整日不写。
     */
    @Test
    fun generateTrainingPlan_doesNotBypassTemplateHandEditedDays() = runTest {
        // 模板：周一那条被用户删掉（软删 + isUserEdited）、周三被用户手改过。
        val templateDeletedMonday = WeekPlan(
            id = 900L,
            exerciseId = 1L,
            dayOfWeek = 1,
            isActive = false,
            isUserEdited = true,
            weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START,
        )
        val templateEditedWednesday = WeekPlan(
            id = 901L,
            exerciseId = 3L,
            dayOfWeek = 3,
            isActive = true,
            isUserEdited = true,
            weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START,
        )
        stubDefaults(existing = emptyList())
        coEvery { planRepository.getRepeatRows() } returns
            listOf(templateDeletedMonday, templateEditedWednesday)

        val summary = useCase()

        val written = slot<List<WeekPlan>>()
        coVerify(exactly = 1) { planRepository.upsertGenerated(capture(written)) }
        assertFalse(
            "周一（模板里被删过、本周无专属行）不得写入任何专属行 —— 否则删除被绕过",
            written.captured.any { it.dayOfWeek == 1 },
        )
        assertFalse(
            "周三（模板里被手改过、本周无专属行）同理",
            written.captured.any { it.dayOfWeek == 3 },
        )
        assertTrue(
            "本周自有专属行、且模板未动过的天（周五）照常生成",
            written.captured.any { it.dayOfWeek == 5 },
        )
        assertEquals("两条模板手改行都要计入「已保留」", 2, summary.preservedCount)
    }

    /**
     * 🔴 回归钉子：报告的修复写法（`existing = 本周行 + 模板行`）会把模板行喂进
     * `deactivateGenerated` 的输入 → 整份「每周相同」计划被判成陈旧 AI 行而停用，
     * 比原缺陷更严重。本用例把这个坑钉死：模板行**永不**出现在回收输入里。
     */
    @Test
    fun generateTrainingPlan_neverRetiresRepeatTemplateRows() = runTest {
        val templateEdited = WeekPlan(
            id = 901L,
            exerciseId = 3L,
            dayOfWeek = 3,
            isActive = true,
            isUserEdited = true,
            weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START,
        )
        val templateAiRow = WeekPlan(
            id = 902L,
            exerciseId = 5L,
            dayOfWeek = 5,
            isActive = true,
            isUserEdited = false,
            weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START,
        )
        stubDefaults(existing = emptyList())
        coEvery { planRepository.getRepeatRows() } returns listOf(templateEdited, templateAiRow)

        useCase()

        val retired = slot<List<WeekPlan>>()
        coVerify(exactly = 1) { planRepository.deactivateGenerated(capture(retired)) }
        assertFalse("模板手改行绝不被回收", retired.captured.any { it.id == 901L })
        assertFalse(
            "模板里的 AI 行也绝不被回收（它是模板，不是本周的行）",
            retired.captured.any { it.id == 902L },
        )
        assertTrue("本周没有专属行 → 没什么可回收的", retired.captured.isEmpty())
    }

    /** 本周某天**有自己的启用专属行**时，该天不受模板影响（先保证不误伤正常路径）。 */
    @Test
    fun generateTrainingPlan_dayWithOwnWeekRowsIsNotTemplateOwned() = runTest {
        val weekRowMonday = WeekPlan(
            id = 100L,
            exerciseId = 4L,
            dayOfWeek = 1,
            isActive = true,
            isUserEdited = false,
        )
        val templateEditedMonday = WeekPlan(
            id = 900L,
            exerciseId = 1L,
            dayOfWeek = 1,
            isActive = true,
            isUserEdited = true,
            weekStartEpochDay = WeekPlan.TEMPLATE_WEEK_START,
        )
        stubDefaults(existing = listOf(weekRowMonday))
        coEvery { planRepository.getRepeatRows() } returns listOf(templateEditedMonday)

        val summary = useCase()

        val written = slot<List<WeekPlan>>()
        coVerify(exactly = 1) { planRepository.upsertGenerated(capture(written)) }
        assertTrue(
            "本周周一有自己的专属行 → 该天照常生成（模板手改不改变归属）",
            written.captured.any { it.dayOfWeek == 1 },
        )
        assertFalse(
            "但模板手改的**槽位**（周一 × 动作1）仍受保护，不得写入",
            written.captured.any { it.dayOfWeek == 1 && it.exerciseId == 1L },
        )
        assertEquals("模板手改行计入「已保留」", 1, summary.preservedCount)
    }

    private companion object {
        // 2026-09-14 附近的一个固定时刻（可复现；具体日期不影响「周计划按星期存储」的语义）。
        const val FIXED_MILLIS: Long = 1_787_000_000_000L
    }
}