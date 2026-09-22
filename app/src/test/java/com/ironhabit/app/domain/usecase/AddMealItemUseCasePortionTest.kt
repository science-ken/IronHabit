package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AddMealItemUseCase] 的份量口径（审查报告 P0-3 降级后剩下的那半边）。
 *
 * 钉三件事：
 * 1. **显式输入优先** —— 调用方直接给了克数，就不能拿食物的第一个份量去顶掉它；
 * 2. **份数要校验** —— `InputLimits.isValidServings` 以前零调用，传 200 份也能落库；
 * 3. **两条路径卡同一个上限** —— 按份算出来的总克数以前不卡，于是"能加进去却改不动"。
 */
class AddMealItemUseCasePortionTest {

    private val foodRepository = mockk<FoodRepository>(relaxed = true)
    private val mealItemRepository = mockk<MealItemRepository>(relaxed = true)
    private val clock = mockk<Clock>(relaxed = true)
    private val saved = slot<MealItem>()

    private val useCase = AddMealItemUseCase(foodRepository, mealItemRepository, clock)

    private val bowl = FoodServing(id = 1L, unit = "碗", grams = 200)

    private val rice = Food(
        id = 7L,
        name = "米饭",
        kcalPer100g = 116,
        proteinPer100g = 2.6,
        carbsPer100g = 25.9,
        fatPer100g = 0.3,
        servings = listOf(bowl),
    )

    private fun stub(stored: Food) {
        coEvery { foodRepository.getFood(stored.id) } returns stored
        coEvery { mealItemRepository.countByMeal(any()) } returns 0
        coEvery { mealItemRepository.upsert(capture(saved)) } returns 99L
    }

    @Test
    fun explicitGramsBeatTheFoodsFirstServing() = runTest {
        stub(rice)

        useCase(mealId = 1L, foodId = 7L, serving = null, grams = 100.0)

        assertEquals("调用方说了 100g，就不能落成「一碗 200g」", 100.0, saved.captured.grams, 0.0)
        assertNull("按克数记的条目不该带份量单位", saved.captured.servingUnit)
    }

    @Test
    fun servingPathMultipliesByCount() = runTest {
        stub(rice)

        useCase(mealId = 1L, foodId = 7L, serving = bowl, servingCount = 1.5)

        assertEquals(300.0, saved.captured.grams, 0.0)
        assertEquals("碗", saved.captured.servingUnit)
    }

    /** 挑选模式里"这条食物压根没定义份量"的那条路：仍然要能回落到第一个份量（此处即不落）。 */
    @Test
    fun nothingGivenFallsBackToTheFirstServing() = runTest {
        stub(rice)

        useCase(mealId = 1L, foodId = 7L)

        assertEquals("两者都没给时才允许拿食物的第一个份量兜底", 200.0, saved.captured.grams, 0.0)
    }

    @Test
    fun servingCountAboveTheLimitIsRejectedAndNeverStored() = runTest {
        stub(rice)

        val result = useCase(mealId = 1L, foodId = 7L, serving = bowl, servingCount = 21.0)

        assertTrue("份数上限 20，以前全程不校验", result is AddMealItemResult.InvalidPortion)
        coVerify(exactly = 0) { mealItemRepository.upsert(any()) }
    }

    @Test
    fun servingPathAlsoRespectsTheGramsCeiling() = runTest {
        val huge = rice.copy(servings = listOf(bowl.copy(grams = 1_500)))
        stub(huge)

        val result = useCase(mealId = 1L, foodId = 7L, serving = huge.servings.first(), servingCount = 2.0)

        assertTrue(
            "1500g × 2 = 3000g 超过 2000g 上限：以前加得进去、改的时候却被拒，两边口径不一致",
            result is AddMealItemResult.InvalidPortion,
        )
    }
}
