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

/** [QuickCheckInUseCase] 单测：验证一键打卡写入的内容与副作用。 */
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

    @Test
    fun writesCheckInWithPlanTargetsAndBumpsUsage() = runTest {
        val captured = slot<CheckIn>()
        coEvery { checkInRepository.upsert(capture(captured)) } returns 1L

        val plan = WeekPlan(
            id = 10L,
            exerciseId = 5L,
            dayOfWeek = 1,
            targetSets = 4,
            targetReps = 8,
            targetWeightKg = 20f,
        )

        useCase(plan)

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
}
