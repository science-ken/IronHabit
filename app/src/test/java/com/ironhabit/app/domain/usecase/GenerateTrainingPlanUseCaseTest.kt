package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.LocalRuleAdvisor
import com.ironhabit.app.domain.model.Exercise
import com.ironhabit.app.domain.model.ExerciseCategory
import com.ironhabit.app.domain.model.ExerciseProgress
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.repository.PlanRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(FIXED_MILLIS)
    }

    private val useCase = GenerateTrainingPlanUseCase(
        planRepository = planRepository,
        exerciseRepository = exerciseRepository,
        checkInRepository = checkInRepository,
        settingsRepository = settingsRepository,
        advisor = LocalRuleAdvisor,
        clock = fixedClock,
        timeZone = TimeZone.UTC,
    )

    private val library: List<Exercise> = listOf(
        exercise(1L, ExerciseCategory.BODYWEIGHT, "腿部"),
        exercise(2L, ExerciseCategory.BODYWEIGHT, "胸部"),
        exercise(3L, ExerciseCategory.BODYWEIGHT, "背部"),
        exercise(4L, ExerciseCategory.BODYWEIGHT, "腹部"),
        exercise(5L, ExerciseCategory.BODYWEIGHT, "核心"),
        exercise(6L, ExerciseCategory.CARDIO, "有氧"),
        exercise(7L, ExerciseCategory.BODYWEIGHT, "全身"),
    )

    private fun exercise(
        id: Long,
        category: ExerciseCategory,
        muscle: String,
    ): Exercise = Exercise(
        id = id,
        name = "ex-$id",
        category = category,
        muscleGroups = listOf(muscle),
        isActive = true,
        defaultSets = 3,
        defaultReps = 12,
    )

    private fun stubDefaults(existing: List<WeekPlan>) {
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        every { exerciseRepository.observeActive() } returns flowOf(library)
        every { planRepository.observeAllIncludingInactive() } returns flowOf(existing)
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

    private companion object {
        // 2026-09-14 附近的一个固定时刻（可复现；具体日期不影响「周计划按星期存储」的语义）。
        const val FIXED_MILLIS: Long = 1_787_000_000_000L
    }
}
