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

        coVerify(exactly = 1) { checkInRepository.upsert(any()) }
    }
}
