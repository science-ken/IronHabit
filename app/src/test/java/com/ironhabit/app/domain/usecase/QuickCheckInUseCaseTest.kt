package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.model.WeekPlan
import com.ironhabit.app.domain.repository.CheckInRepository
import com.ironhabit.app.domain.repository.ExerciseRepository
import com.ironhabit.app.domain.util.DateUtils
import com.ironhabit.app.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * [QuickCheckInUseCase] 单测：验证一键打卡写入的内容与副作用。
 *
 * ⚠️ Round-3 签名变更：`invoke` 现为 `(plan, epochDay)` —— 打卡日期由**调用方传入**
 * （= 所选日），用例内部**不再**自算「真实今天」（写入口径与逐组/RPE/撤销/习惯/补录统一）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickCheckInUseCaseTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val utc = TimeZone.UTC
    private val baseDate = LocalDate(2026, 2, 21)
    private val baseEpochDay = baseDate.toEpochDays().toLong()
    private val noonMillis = baseDate.atStartOfDayIn(utc).toEpochMilliseconds() + 12L * 3_600_000L
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(noonMillis)
    }

    private val checkInRepository = mockk<CheckInRepository>(relaxed = true)
    private val exerciseRepository = mockk<ExerciseRepository>(relaxed = true)

    private val useCase = QuickCheckInUseCase(
        checkInRepository = checkInRepository,
        exerciseRepository = exerciseRepository,
        clock = clock,
        timeZone = utc,
    )

    private val plan = WeekPlan(
        id = 10L,
        exerciseId = 5L,
        dayOfWeek = 1,
        targetSets = 4,
        targetReps = 8,
        targetWeightKg = 20f,
    )

    /**
     * ⚠️ `relaxed = true` 的 mockk 对**可空返回**给的是非空的链式桩，不是 `null`
     * （见 `BackfillCheckInUseCaseTest` 同一课）。这里不显式登记，"那天还没有行"
     * 这个前提在测试里根本不成立，而 [QuickCheckInUseCase] 新加的合并分支会被
     * 一个凭空的假旧行带进来说不清。
     */
    @Before
    fun noRowExistsOnThatDayYet() {
        coEvery { checkInRepository.getForExerciseOnDate(any(), any()) } returns null
    }

    /**
     * 一键打卡不许抹掉用户手记的明细（审查报告 P2-2）。
     *
     * 场景是真机能走到的：详细打卡弹层里记了"实际 90kg、5 个、备注腰有点顶"（3 组只勾了 2 组，
     * 条目未完成 → 一键打卡那颗按钮照旧可点），顺手再点一下按钮。
     * 旧实现把**计划目标值**整行盖上去：20kg / 8 个 / 备注空 —— 真实数据来源当场消失。
     */
    @Test
    fun quickCheckInKeepsManuallyLoggedDetails() = runTest {
        val captured = slot<CheckIn>()
        coEvery { checkInRepository.upsert(capture(captured)) } returns 1L
        coEvery { checkInRepository.getForExerciseOnDate(5L, baseEpochDay) } returns CheckIn(
            id = 42L,
            exerciseId = 5L,
            dateEpochDay = baseEpochDay,
            completedSetsMask = 0b011,
            completedReps = 5,
            weightKg = 90f,
            durationMinutes = 45,
            notes = "腰有点顶",
            rpe = 8,
            isQuick = false,
        )

        useCase(plan, baseEpochDay)

        val saved = captured.captured
        assertEquals("实际次数是用户记的，不能被计划目标值盖掉", 5, saved.completedReps)
        assertEquals(90.0, (saved.weightKg ?: 0f).toDouble(), 0.001)
        assertEquals("备注被一键打卡清空 = 用户下一次不知道该记什么", "腰有点顶", saved.notes)
        assertEquals(45, saved.durationMinutes)
        assertEquals("RPE 同样是用户录的强度", 8, saved.rpe)
        assertEquals(
            "isQuick 是「实际值 vs 计划复读」的唯一判据，翻成 true 会把真数据降级",
            false, saved.isQuick,
        )
        assertEquals("点这一下要推进的只有完成状态", 4, saved.completedSets)
        assertEquals(0b1111, saved.completedSetsMask)
    }

    /**
     * 反向那一条：两条都是快速打卡时**要**刷新。
     *
     * 少了这一条，上面的规则就退化成"一键打卡永远不写明细"，
     * 于是用户把计划重量从 20 改成 25、再点一次，行里还留着 20。
     */
    @Test
    fun quickCheckInOverAPreviousQuickRowRefreshesPlanTargets() = runTest {
        val captured = slot<CheckIn>()
        coEvery { checkInRepository.upsert(capture(captured)) } returns 1L
        coEvery { checkInRepository.getForExerciseOnDate(5L, baseEpochDay) } returns CheckIn(
            id = 42L,
            exerciseId = 5L,
            dateEpochDay = baseEpochDay,
            completedReps = 8,
            weightKg = 20f,
            isQuick = true,
        )

        useCase(plan.copy(targetWeightKg = 25f, targetReps = 10), baseEpochDay)

        val saved = captured.captured
        assertEquals(25.0, (saved.weightKg ?: 0f).toDouble(), 0.001)
        assertEquals(10, saved.completedReps)
    }

    @Test
    fun writesCheckInWithPlanTargetsAndBumpsUsage() = runTest {
        val captured = slot<CheckIn>()
        coEvery { checkInRepository.upsert(capture(captured)) } returns 1L

        useCase(plan, baseEpochDay)

        val saved = captured.captured
        assertEquals(5L, saved.exerciseId)
        assertEquals(10L, saved.planId ?: -1L)
        assertEquals(baseEpochDay, saved.dateEpochDay)
        assertEquals(DateUtils.startOfDayMillis(baseEpochDay, utc), saved.dateStartMillis)
        assertEquals(4, saved.completedSets)
        assertEquals(8, saved.completedReps)
        assertEquals(20.0, (saved.weightKg ?: 0f).toDouble(), 0.001)
        assertTrue(saved.isQuick)

        coVerify(exactly = 1) { exerciseRepository.bumpUsage(5L) }
        coVerify(exactly = 1) { checkInRepository.upsert(any()) }
    }

    /**
     * 写入口径统一（Round-3 修复后）：一键打卡落在**调用方传入的所选日**，
     * 而不是用例内部自算的「今天」——否则切到非今天查看时，同一屏会出现
     * 「一键打卡写今天、其它写所选日」两套口径。
     */
    @Test
    fun writesCallerSuppliedEpochDayNotInternalToday() = runTest {
        val captured = slot<CheckIn>()
        coEvery { checkInRepository.upsert(capture(captured)) } returns 1L

        // 所选日刻意取一个与 clock 所在日（baseEpochDay）不同的「未来日」
        val selectedDay = baseEpochDay + 7L
        useCase(plan, selectedDay)

        val saved = captured.captured
        assertEquals("打卡日期 = 调用方传入的所选日", selectedDay, saved.dateEpochDay)
        assertEquals(DateUtils.startOfDayMillis(selectedDay, utc), saved.dateStartMillis)
    }
}
