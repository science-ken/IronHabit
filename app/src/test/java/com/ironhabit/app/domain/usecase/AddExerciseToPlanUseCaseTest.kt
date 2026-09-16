package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.PlanRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AddExerciseToPlanUseCase] 单测：动作库「+」加入计划的用户路径落库行为。
 *
 * 重点守住四条不变量：
 * 1. 写入**必带** `weekStartEpochDay`（坑 1：不带会串进「每周相同」那份）；
 * 2. 全程无 `DELETE` / `REPLACE` —— 取消天走 `PlanRepository.delete`（软删，红线 3）；
 * 3. 用户路径所有写入 `isUserEdited = true`（AI 生成时整行跳过，红线 4）；
 * 4. 空周 + 「每周相同」有课 → 先复制整份再写目标天（防"加一个动作挤没整周课"）。
 */
class AddExerciseToPlanUseCaseTest {

    private val planRepository: PlanRepository = mockk(relaxed = true)
    private val useCase = AddExerciseToPlanUseCase(planRepository)

    private val week: Long = 20_600L // 某个周一路径值；UseCase 不解释它的含义

    private fun plan(
        id: Long = 0L,
        exerciseId: Long = 1L,
        day: Int = 1,
        isActive: Boolean = true,
        isUserEdited: Boolean = false,
        weekStart: Long = week,
    ): WeekPlan = WeekPlan(
        id = id,
        exerciseId = exerciseId,
        dayOfWeek = day,
        targetSets = 3,
        targetReps = 12,
        isActive = isActive,
        isUserEdited = isUserEdited,
        weekStartEpochDay = weekStart,
    )

    /** 收集所有经 `PlanRepository.upsert` 写入的行。 */
    private fun captureUpserts(): MutableList<WeekPlan> {
        val list = mutableListOf<WeekPlan>()
        coEvery { planRepository.upsert(any()) } answers {
            list.add(firstArg())
            1L
        }
        return list
    }

    private fun captureDeletes(): MutableList<Long> {
        val list = mutableListOf<Long>()
        coEvery { planRepository.delete(any()) } answers {
            list.add(firstArg())
            Unit
        }
        return list
    }

    @Test
    fun `adds selected days with userEdited flag and target week`() = runTest {
        val upserts = captureUpserts()
        coEvery { planRepository.getRowsForWeek(week) } returns emptyList()
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns emptyList()

        val result = useCase(
            exerciseId = 7L,
            weekStartEpochDay = week,
            selectedDays = setOf(2, 5),
            targetSets = 4,
            targetReps = 10,
            alsoRepeatWeekly = false,
        )

        assertEquals(listOf(2, 5), result.writtenDays)
        assertTrue(result.removedDays.isEmpty())
        assertEquals(0, result.copiedRepeatRows)
        assertEquals(2, upserts.size)
        upserts.forEach { row ->
            assertEquals("写入必须落在目标周（坑 1）", week, row.weekStartEpochDay)
            assertEquals(7L, row.exerciseId)
            assertTrue("用户路径必须置 isUserEdited（红线 4）", row.isUserEdited)
            assertTrue(row.isActive)
            assertEquals(4, row.targetSets)
            assertEquals(10, row.targetReps)
        }
        assertEquals(setOf(2, 5), upserts.map { it.dayOfWeek }.toSet())
        coVerify(exactly = 0) { planRepository.delete(any()) }
    }

    @Test
    fun `deselecting a currently active day soft-deletes it instead of hard delete`() = runTest {
        val activeMonday = plan(id = 11L, day = 1)
        val upserts = captureUpserts()
        val deletes = captureDeletes()
        coEvery { planRepository.getRowsForWeek(week) } returns listOf(activeMonday)
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns emptyList()

        val result = useCase(1L, week, selectedDays = setOf(3), targetSets = 3, targetReps = 12, alsoRepeatWeekly = false)

        assertEquals(listOf(1), result.removedDays)
        assertEquals("取消 = 软删除（红线 3：绝无 DELETE 语义的直接抹行）", listOf(11L), deletes)
        assertEquals(listOf(3), result.writtenDays)
        assertEquals(3, upserts.single().dayOfWeek)
    }

    @Test
    fun `re-selecting a user-deleted day revives the same row via upsert`() = runTest {
        // 用户先前删掉（软删）周一槽位；现在重新勾上 = 用户反悔，允许复活（用户显式路径）。
        val softDeleted = plan(id = 21L, day = 1, isActive = false, isUserEdited = true)
        val upserts = captureUpserts()
        val deletes = captureDeletes()
        coEvery { planRepository.getRowsForWeek(week) } returns listOf(softDeleted)
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns emptyList()

        useCase(1L, week, selectedDays = setOf(1), targetSets = 5, targetReps = 5, alsoRepeatWeekly = false)

        val upserted = upserts.single()
        assertEquals("复活必须复用原行 id（显式 upsert，不重建）", 21L, upserted.id)
        assertTrue(upserted.isActive)
        assertTrue("用户亲手加回的行仍是用户意图（红线 4）", upserted.isUserEdited)
        assertTrue(deletes.isEmpty())
    }

    @Test
    fun `empty week with repeat plan copies the whole repeat sheet first`() = runTest {
        // 该周无启用专属行 + 模板里有别的课 → 先复制整份（含本动作），再写目标天。
        val repeatPush = plan(id = 31L, exerciseId = 9L, day = 2, weekStart = WeekPlan.TEMPLATE_WEEK_START)
        val repeatSquat = plan(id = 32L, exerciseId = 1L, day = 4, weekStart = WeekPlan.TEMPLATE_WEEK_START)
        val upserts = captureUpserts()
        coEvery { planRepository.getRowsForWeek(week) } returns emptyList()
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns listOf(repeatPush, repeatSquat)

        val result = useCase(1L, week, selectedDays = setOf(6), targetSets = 3, targetReps = 12, alsoRepeatWeekly = false)

        assertEquals(2, result.copiedRepeatRows)
        // 复制体：id 归零落新槽位、周指向目标周、启用、isUserEdited 随源行保留。
        val copied = upserts.filter { it.exerciseId != 1L || it.dayOfWeek != 6 }
        assertEquals(setOf(2, 4), copied.map { it.dayOfWeek }.toSet())
        copied.forEach { row ->
            assertEquals(week, row.weekStartEpochDay)
            assertTrue(row.isActive)
        }
        // 目标天仍要写入（覆盖复制进来的模板同槽行 or 新增）。
        assertTrue(upserts.any { it.dayOfWeek == 6 && it.exerciseId == 1L })
    }

    @Test
    fun `week that already has active rows does not trigger copy`() = runTest {
        val existing = plan(id = 41L, exerciseId = 8L, day = 3)
        val upserts = captureUpserts()
        coEvery { planRepository.getRowsForWeek(week) } returns listOf(existing)
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns listOf(
            plan(id = 42L, exerciseId = 9L, day = 2, weekStart = WeekPlan.TEMPLATE_WEEK_START),
        )

        val result = useCase(1L, week, selectedDays = setOf(3), targetSets = 3, targetReps = 12, alsoRepeatWeekly = false)

        assertEquals(0, result.copiedRepeatRows)
        assertEquals("已有专属行的周只写目标槽位", 1, upserts.size)
        assertEquals(3, upserts.single().dayOfWeek)
    }

    @Test
    fun `alsoRepeatWeekly mirrors target state onto template sheet`() = runTest {
        val upserts = captureUpserts()
        coEvery { planRepository.getRowsForWeek(week) } returns emptyList()
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns emptyList()

        useCase(1L, week, selectedDays = setOf(5), targetSets = 3, targetReps = 12, alsoRepeatWeekly = true)

        val weeks = upserts.map { it.weekStartEpochDay }.toSet()
        assertEquals("两份都要写：目标周 + 每周相同那份", setOf(week, WeekPlan.TEMPLATE_WEEK_START), weeks)
        assertEquals(2, upserts.size)
    }

    @Test
    fun `alsoRepeatWeekly keeps template untouched when off`() = runTest {
        // 空周预处理允许"查询"模板份（判断是否要复制），但关闭开关时绝不允许"写入"模板份。
        val upserts = captureUpserts()
        coEvery { planRepository.getRowsForWeek(week) } returns emptyList()
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns emptyList()

        useCase(1L, week, selectedDays = setOf(5), targetSets = 3, targetReps = 12, alsoRepeatWeekly = false)

        assertTrue(
            "所有写入都必须落在目标周，模板份一行不碰",
            upserts.all { it.weekStartEpochDay == week },
        )
    }

    @Test
    fun `clamps sets and reps to InputLimits and filters invalid days`() = runTest {
        val upserts = captureUpserts()
        coEvery { planRepository.getRowsForWeek(week) } returns emptyList()
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns emptyList()

        useCase(1L, week, selectedDays = setOf(0, 8, 3), targetSets = 0, targetReps = 999, alsoRepeatWeekly = false)

        val row = upserts.single()
        assertEquals("非法天（0、8）必须被滤掉", 3, row.dayOfWeek)
        assertEquals("组数下限钳到 1（InputLimits.MIN_SETS）", 1, row.targetSets)
        assertEquals("次数上限钳到 100（InputLimits.MAX_REPS）", 100, row.targetReps)
    }

    @Test
    fun `overwrites existing ai row keeping its id`() = runTest {
        // 槽位上是 AI 行（isUserEdited=false）：用户显式加入 → 覆盖组次并置用户标记。
        val aiRow = plan(id = 51L, day = 2, isUserEdited = false)
        val upserts = captureUpserts()
        coEvery { planRepository.getRowsForWeek(week) } returns listOf(aiRow)
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns emptyList()

        useCase(1L, week, selectedDays = setOf(2), targetSets = 4, targetReps = 8, alsoRepeatWeekly = false)

        val row = upserts.single()
        assertEquals(51L, row.id)
        assertEquals(4, row.targetSets)
        assertEquals(8, row.targetReps)
        assertTrue(row.isUserEdited)
    }

    @Test
    fun `empty selection removes all active rows for the exercise`() = runTest {
        val monday = plan(id = 61L, day = 1)
        val friday = plan(id = 62L, day = 5)
        val otherExercise = plan(id = 63L, exerciseId = 99L, day = 2)
        val deletes = captureDeletes()
        coEvery { planRepository.getRowsForWeek(week) } returns listOf(monday, friday, otherExercise)
        coEvery { planRepository.getRowsForWeek(WeekPlan.TEMPLATE_WEEK_START) } returns emptyList()

        val result = useCase(1L, week, selectedDays = emptySet(), targetSets = 3, targetReps = 12, alsoRepeatWeekly = false)

        assertEquals(listOf(1, 5), result.removedDays)
        assertEquals("只删本动作的行，别碰其他动作", setOf(61L, 62L), deletes.toSet())
    }
}
