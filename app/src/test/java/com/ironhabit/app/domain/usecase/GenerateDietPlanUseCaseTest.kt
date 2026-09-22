package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.model.UserProfile
import com.ironhabit.app.domain.repository.BodyMetricRepository
import com.ironhabit.app.domain.repository.MealRepository
import com.ironhabit.app.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [GenerateDietPlanUseCase] 的「已保留 N 餐」口径。
 *
 * **保护范围没变**（不变量 1：手改行含软删行一律不覆盖、不复活），变的只有那个数字 ——
 * `MealDao.softDelete` 会同时置 `is_user_edited = 1`，于是"用户删掉的那一餐"既不在界面上，
 * 又被旧口径报成"替你留住了改动"：一个谁也找不到对应的数字。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GenerateDietPlanUseCaseTest {

    private val mealRepository: MealRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val bodyMetricRepository: BodyMetricRepository = mockk(relaxed = true)
    private val trainingDayResolver: TrainingDayResolver = mockk(relaxed = true)

    private val useCase = GenerateDietPlanUseCase(
        mealRepository = mealRepository,
        trainingDayResolver = trainingDayResolver,
        settingsRepository = settingsRepository,
        bodyMetricRepository = bodyMetricRepository,
        clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(FIXED_MILLIS)
        },
        timeZone = TimeZone.UTC,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    /** 返回本次写出的全部草案餐型（`upsertGenerated` 可能分批）。 */
    private fun stub(existing: List<Meal>): MutableList<MealType> {
        every { settingsRepository.profile() } returns flowOf(UserProfile())
        coEvery { bodyMetricRepository.latest(any()) } returns null
        coEvery { trainingDayResolver(any()) } returns false
        coEvery { mealRepository.getMealsIncludingInactive(EPOCH_DAY) } returns existing
        val written = mutableListOf<MealType>()
        coEvery { mealRepository.upsertGenerated(any()) } answers {
            val batch = firstArg<List<Meal>>()
            written += batch.map { meal -> meal.mealType }
            batch.size
        }
        return written
    }

    @Test
    fun activeUserEditedMealIsCountedAndNeverOverwritten() = runTest {
        val written = stub(
            listOf(Meal(id = 1L, dateEpochDay = EPOCH_DAY, mealType = MealType.BREAKFAST, kcal = 500, isUserEdited = true)),
        )

        val summary = useCase(EPOCH_DAY)

        assertEquals("界面上看得见的手改餐要报出来", 1, summary.preservedCount)
        assertFalse("手改的早餐不得被覆盖", MealType.BREAKFAST in written)
    }

    /** 核心：数字口径变了，保护不变量一位没动。 */
    @Test
    fun softDeletedUserEditedMealStaysProtectedButStopsBeingCounted() = runTest {
        val deletedBreakfast = Meal(
            id = 1L,
            dateEpochDay = EPOCH_DAY,
            mealType = MealType.BREAKFAST,
            kcal = 500,
            isActive = false,
            isUserEdited = true,
        )
        val written = stub(listOf(deletedBreakfast))

        val summary = useCase(EPOCH_DAY)

        assertEquals("删掉的那一餐在界面上找不到，不该报进「已保留 N 餐」", 0, summary.preservedCount)
        assertFalse("保护照旧：软删的手改餐不得被重新生成覆盖", MealType.BREAKFAST in written)
    }

    @Test
    fun untouchedDayCountsNothingAndWritesAllFourMeals() = runTest {
        val written = stub(emptyList())

        val summary = useCase(EPOCH_DAY)

        assertEquals(0, summary.preservedCount)
        assertEquals("没有手改行时四餐照常生成", MealType.entries.toSet(), written.toSet())
    }

    private companion object {
        /** 2026-09-21（周一）。具体哪天不影响"按餐次槽位生成"的语义。 */
        const val EPOCH_DAY: Long = 20717L

        const val FIXED_MILLIS: Long = 1_787_000_000_000L
    }
}
