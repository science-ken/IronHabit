package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.CheckIn
import com.ironhabit.app.domain.repository.CheckInRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** [BackfillCheckInUseCase] 单测：历史补卡写入正确、`isQuick = false`。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackfillCheckInUseCaseTest {

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

    private val useCase = BackfillCheckInUseCase(
        checkInRepository = checkInRepository,
        clock = clock,
        timeZone = utc,
    )

    @Before
    fun noRowExistsOnThatDayYet() {
        /*
         * ⚠️ `relaxed = true` 对**可空返回**给的是非空链式桩，不是 null。
         * 不登记这一条，`previous` 在测试里其实一直"存在"，
         * 于是"新行不继承任何明细"与"旧行要继承明细"两条都测不到真值。
         */
        coEvery { checkInRepository.getForExerciseOnDate(any(), any()) } returns null
    }

    @Test
    fun backfillWritesHistoricalEntryMarkedNotQuick() = runTest {
        val captured = slot<CheckIn>()
        coEvery { checkInRepository.upsert(capture(captured)) } returns 1L

        val targetDay = baseEpochDay - 3L
        useCase(exerciseId = 7L, epochDay = targetDay, sets = 3, reps = 12)

        val saved = captured.captured
        assertEquals(7L, saved.exerciseId)
        assertEquals(targetDay, saved.dateEpochDay)
        assertEquals(DateUtils.startOfDayMillis(targetDay, utc), saved.dateStartMillis)
        assertEquals(3, saved.completedSets)
        assertEquals(12, saved.completedReps)
        assertFalse(saved.isQuick)
        // 那天本来没有行：null 是"本来就没有"，不是"把明细清空了"。
        assertNull(saved.weightKg)
        assertNull(saved.notes)

        coVerify(exactly = 1) { checkInRepository.upsert(any()) }
    }

    /**
     * 补卡弹层里**只有组×次**（审查报告 P2-2 同族）。
     *
     * 旧实现把重量/时长/备注写死 null，于是一天已有的"实重 90kg + 备注"会被一次补卡抹平。
     * 弹层没提供的格子必须沿用旧行 —— 那才是"我只改了组次"这个动作的真实语义。
     */
    @Test
    fun backfillKeepsDetailsTheDialogNeverAskedFor() = runTest {
        val captured = slot<CheckIn>()
        coEvery { checkInRepository.upsert(capture(captured)) } returns 1L
        coEvery { checkInRepository.getForExerciseOnDate(7L, baseEpochDay) } returns CheckIn(
            id = 42L,
            exerciseId = 7L,
            dateEpochDay = baseEpochDay,
            completedSetsMask = 0b11,
            completedReps = 10,
            weightKg = 90f,
            durationMinutes = 50,
            notes = "状态一般",
        )

        useCase(exerciseId = 7L, epochDay = baseEpochDay, sets = 3, reps = 12)

        val saved = captured.captured
        assertEquals(90.0, (saved.weightKg ?: 0f).toDouble(), 0.001)
        assertEquals(50, saved.durationMinutes)
        assertEquals("状态一般", saved.notes)
        assertEquals("改了的就是这次填的：组次要落新值", 12, saved.completedReps)
        assertEquals(0b111, saved.completedSetsMask)
    }
}
