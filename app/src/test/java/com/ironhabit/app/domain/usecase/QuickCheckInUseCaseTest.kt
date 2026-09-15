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
