package com.ironhabit.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每 100g 定义 → 实际克数的换算。
 *
 * 这条换算是饮食区所有数字的源头：一餐合计、磁贴分子、周复盘、喂给 AI 的输入
 * 全都从它长出来。它错了，上面每一层都会"看起来合理但其实是错的"。
 */
class FoodNutritionCalculatorTest {

    /** 米饭（蒸）：116 kcal / 2.6 蛋白 / 25.9 碳水 / 0.3 脂肪 每 100g。 */
    private val rice = Food(
        id = 1L,
        name = "米饭（蒸）",
        kcalPer100g = 116,
        proteinPer100g = 2.6,
        carbsPer100g = 25.9,
        fatPer100g = 0.3,
        servings = listOf(FoodServing(id = 1L, unit = "碗", grams = 150, sortOrder = 0)),
    )

    @Test
    fun oneHundredGramsReturnsTheDefinitionItself() {
        val n = FoodNutritionCalculator.forGrams(rice, 100.0)
        assertEquals(116, n.kcal)
        assertEquals(2.6, n.proteinG, 1e-9)
        assertEquals(25.9, n.carbsG, 1e-9)
        assertEquals(0.3, n.fatG, 1e-9)
    }

    @Test
    fun scalesLinearlyWithGrams() {
        val half = FoodNutritionCalculator.forGrams(rice, 50.0)
        assertEquals(58, half.kcal)
        assertEquals(1.3, half.proteinG, 1e-9)

        val double = FoodNutritionCalculator.forGrams(rice, 200.0)
        assertEquals(232, double.kcal)
    }

    /** 一碗 = 150g：这是用户最常走的路径。 */
    @Test
    fun servingMultipliesIntoGrams() {
        val n = FoodNutritionCalculator.forServings(rice, rice.servings.first(), servingCount = 1.0)
        assertEquals(174, n!!.kcal) // 116 × 1.5
        assertEquals(3.9, n.proteinG, 1e-9)
    }

    @Test
    fun halfServingIsAllowed() {
        val n = FoodNutritionCalculator.forServings(rice, rice.servings.first(), servingCount = 0.5)
        assertEquals(87, n!!.kcal) // 75g
    }

    /** 脏输入必须收成 0，绝不能把 NaN 写进库 —— 一个 NaN 会把整天的合计污染成 NaN。 */
    @Test
    fun nonFiniteOrNegativeGramsYieldZero() {
        for (grams in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val n = FoodNutritionCalculator.forGrams(rice, grams)
            assertEquals("grams=$grams 应得 0 kcal", 0, n.kcal)
            assertEquals(0.0, n.proteinG, 1e-9)
            assertTrue(n.carbsG.isFinite() && n.fatG.isFinite())
        }
    }

    @Test
    fun servingWithoutGramsOrCountReturnsNull() {
        val broken = FoodServing(unit = "碗", grams = 0)
        assertNull(FoodNutritionCalculator.forServings(rice, broken, 1.0))
        assertNull(FoodNutritionCalculator.forServings(rice, rice.servings.first(), 0.0))
        assertNull(FoodNutritionCalculator.forServings(rice, rice.servings.first(), Double.NaN))
    }

    /**
     * 纯脂肪的边界：100g 油 = 900 kcal/100g，取任意克数都不该越过
     * [InputLimits.MAX_FOOD_KCAL_PER_100G] 所暗示的量级。
     * 这条同时钉住"kcal 四舍五入而不是截断"。
     */
    @Test
    fun roundsRatherThanTruncates() {
        val oil = Food(
            name = "食用油",
            kcalPer100g = 900,
            proteinPer100g = 0.0,
            carbsPer100g = 0.0,
            fatPer100g = 100.0,
        )
        // 15g × 9 = 135 整；取 16g 时 900×0.16 = 144.0
        assertEquals(135, FoodNutritionCalculator.forGrams(oil, 15.0).kcal)
        assertEquals(144, FoodNutritionCalculator.forGrams(oil, 16.0).kcal)
        // 116 × 0.075 = 8.7 → 应进位到 9，不是截断成 8
        assertEquals(9, FoodNutritionCalculator.forGrams(rice, 7.5).kcal)
    }
}
