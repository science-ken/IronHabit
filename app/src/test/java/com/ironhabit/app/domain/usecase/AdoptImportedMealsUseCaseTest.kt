package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.ai.external.ImportedFoodEntry
import com.ironhabit.app.domain.ai.external.ImportedMealDraft
import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.repository.MealRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AdoptImportedMealsUseCase] 单测 —— 钉的是"外部文档写 `meals` 时会不会伤到用户的东西"。
 *
 * 为什么值得单独测：饮食这张表**没有任何仪器测试**（`androidTest` 下只有
 * `AppDatabaseTest` / `CheckInDaoTest` / `WeekPlanDaoUpsertTest`），
 * 而 `meal_items.meal_id` 对 `meals` 是 `ON DELETE CASCADE` —— 这一层只要哪天多出一句 DELETE，
 * 毁掉的是用户真实的逐样记录，不是计划行。所以这里既测"写了什么"，也测"什么都没删"。
 */
class AdoptImportedMealsUseCaseTest {

    private val weekStart: Long = 20_724L

    private val repository: MealRepository = mockk(relaxed = true)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_772_000_000_000L)
    }

    private fun useCase() = AdoptImportedMealsUseCase(
        mealRepository = repository,
        clock = clock,
    )

    private fun draft(
        day: Int,
        mealType: MealType = MealType.LUNCH,
        kcal: Int = 232,
        proteinG: Double = 5.2,
    ) = ImportedMealDraft(
        dayOfWeek = day,
        mealType = mealType,
        entries = listOf(
            ImportedFoodEntry(11L, "米饭（蒸）", 200, FoodNutrition(232, 5.2, 51.8, 0.6)),
        ),
        kcal = kcal,
        proteinG = proteinG,
    )

    private fun existing(
        day: Long,
        mealType: MealType,
        isUserEdited: Boolean = false,
        isActive: Boolean = true,
    ) = Meal(
        id = 7L,
        dateEpochDay = day,
        mealType = mealType,
        isUserEdited = isUserEdited,
        isActive = isActive,
    )

    @Test
    fun adopt_writesOnlyTheRequestedDays_andTheMealCarriesTheDraftNumbers() = runTest {
        val captured = slot<List<Meal>>()
        coEvery { repository.getMealsIncludingInactive(any()) } returns emptyList()
        coEvery { repository.upsertGenerated(capture(captured)) } returns 1

        val result = useCase()(weekStart, listOf(draft(1), draft(3)), setOf(3))

        assertEquals(1, result.writtenCount)
        val written: Meal = captured.captured.single()
        assertEquals("星期要换算成绝对日期", weekStart + 2L, written.dateEpochDay)
        assertEquals(MealType.LUNCH, written.mealType)
        assertEquals(
            "每行一条食物、只写克数：份量单位不进文本，「一碗」这种说法没法和用户家的碗核对",
            listOf("米饭（蒸） 200g"),
            written.items,
        )
        assertEquals(232, written.kcal)
        assertEquals(5.2, written.proteinG, 0.0001)
        assertEquals("sortOrder 沿用餐次 ordinal（早→午→加→晚 的顺序是接口的一部分）", 1, written.sortOrder)
        assertEquals(1_772_000_000_000L, written.createdAt)
    }

    @Test
    fun adopt_skipsSlotsTheUserEdited_andReportsThemAsPreserved() = runTest {
        coEvery { repository.getMealsIncludingInactive(weekStart) } returns
            listOf(existing(weekStart, MealType.LUNCH, isUserEdited = true))
        val captured = slot<List<Meal>>()
        coEvery { repository.upsertGenerated(capture(captured)) } returns 1

        val result = useCase()(
            weekStart,
            listOf(draft(1, MealType.LUNCH), draft(1, MealType.DINNER)),
            setOf(1),
        )

        assertEquals(1, result.writtenCount)
        assertEquals("手改那一格要计入「没动」的数，界面才能在采纳之前说清", 1, result.preservedCount)
        assertEquals(listOf(MealType.DINNER), captured.captured.map { meal -> meal.mealType })
    }

    @Test
    fun adopt_alsoProtectsASoftDeletedSlot_becauseRevivingItWouldBeWrong() = runTest {
        // 「这餐不吃」= 软删行（isActive=false 且 isUserEdited=true）。
        // 它仍占着 UNIQUE(date, meal_type) 槽位，写进去等于把用户主动停掉的一餐悄悄复活。
        coEvery { repository.getMealsIncludingInactive(weekStart) } returns
            listOf(existing(weekStart, MealType.SNACK, isUserEdited = true, isActive = false))

        val result = useCase()(weekStart, listOf(draft(1, MealType.SNACK)), setOf(1))

        assertEquals(0, result.writtenCount)
        assertEquals(1, result.preservedCount)
        coVerify(exactly = 0) { repository.upsertGenerated(any()) }
    }

    @Test
    fun adopt_writesNothingForDaysThatWereNotAskedAndNeverDeletes() = runTest {
        val result = useCase()(weekStart, listOf(draft(2)), setOf(5))

        assertEquals(0, result.writtenCount)
        assertEquals(0, result.preservedCount)
        coVerify(exactly = 0) { repository.upsertGenerated(any()) }
        coVerify(exactly = 0) { repository.getMealsIncludingInactive(any()) }
        // 🔒 这一条是整张表的安全线：`meal_items.meal_id` 对 `meals` 是 ON DELETE CASCADE。
        coVerify(exactly = 0) { repository.delete(any()) }
    }

    @Test
    fun adopt_passesEveryWritableSlotOfTheDayInOneCall() = runTest {
        coEvery { repository.getMealsIncludingInactive(any()) } returns emptyList()
        coEvery { repository.upsertGenerated(any()) } returns 4

        useCase()(
            weekStart,
            listOf(
                draft(1, MealType.BREAKFAST),
                draft(1, MealType.LUNCH),
                draft(1, MealType.SNACK),
                draft(1, MealType.DINNER),
            ),
            setOf(1),
        )

        // 一餐一次调用会留下"写了三餐、第四餐崩了"的半天：那天的合计会被教练页当既成事实读回去。
        coVerify(exactly = 1) { repository.upsertGenerated(withArg { list -> assertEquals(4, list.size) }) }
    }
}
