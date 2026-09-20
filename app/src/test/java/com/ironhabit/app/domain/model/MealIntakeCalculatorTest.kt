package com.ironhabit.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一餐实际营养的取数规则（Q9 = B）。
 *
 * 这里钉的是**整个饮食区最容易翻回错的那一条**：
 * "AI 排了这一餐"绝不能被算成"吃了这一餐"。
 * 训练区 2026-09-20 刚为同一个形状的 bug 把 `week_plans` 与 `check_ins` 分成两张表，
 * 而饮食区只要把 `meals.kcal` 无条件计入合计，就会原地复现它。
 */
class MealIntakeCalculatorTest {

    private fun meal(
        id: Long,
        kcal: Int = 500,
        proteinG: Double = 30.0,
        completed: Boolean = false,
        active: Boolean = true,
    ) = Meal(
        id = id,
        dateEpochDay = 20716L,
        mealType = MealType.BREAKFAST,
        items = listOf("燕麦", "牛奶"),
        kcal = kcal,
        proteinG = proteinG,
        isCompleted = completed,
        isActive = active,
    )

    private fun item(mealId: Long, kcal: Int, protein: Double = 10.0, carbs: Double = 20.0, fat: Double = 5.0) =
        MealItem(
            id = mealId * 100,
            mealId = mealId,
            foodId = 1L,
            foodName = "米饭（蒸）",
            grams = 150.0,
            servingUnit = "碗",
            servingCount = 1.0,
            nutrition = FoodNutrition(kcal, protein, carbs, fat),
        )

    /** 承重墙：AI 生成过、没打勾、没记明细 → 贡献 0。 */
    @Test
    fun plannedButNotEatenContributesNothing() {
        val intake = MealIntakeCalculator.compute(listOf(meal(1L, kcal = 550)), emptyList())
        assertEquals("排了餐不等于吃了", 0, intake.kcal)
        assertEquals(0.0, intake.proteinG, 1e-9)
        assertFalse("这天根本不该算'有记录'", intake.hasAnyRecord)
    }

    @Test
    fun tickedWithoutItemsFallsBackToWholeMealNumber() {
        val intake = MealIntakeCalculator.compute(
            listOf(meal(1L, kcal = 550, proteinG = 23.0, completed = true)),
            emptyList(),
        )
        assertEquals(550, intake.kcal)
        assertEquals(23.0, intake.proteinG, 1e-9)
        assertEquals("必须被标成粗记（界面显示「约」）", setOf(1L), intake.coarseMealIds)
        assertEquals(0, intake.preciseMeals)
        assertTrue(intake.hasAnyRecord)
    }

    @Test
    fun itemsBeatTheWholeMealNumber() {
        val intake = MealIntakeCalculator.compute(
            listOf(meal(1L, kcal = 550, completed = true)),
            listOf(item(1L, kcal = 174), item(1L, kcal = 133)),
        )
        assertEquals("有明细就只认明细，550 那个数不能再加进来", 307, intake.kcal)
        assertEquals(1, intake.preciseMeals)
        assertTrue("打了勾但记了明细 → 不算粗记", intake.coarseMealIds.isEmpty())
    }

    @Test
    fun macrosComeOnlyFromItems() {
        val intake = MealIntakeCalculator.compute(
            listOf(meal(1L, kcal = 550, completed = true), meal(2L, kcal = 700, completed = true)),
            listOf(item(1L, kcal = 174, protein = 3.9, carbs = 38.9, fat = 0.45)),
        )
        // 餐 1 有明细 → 174；餐 2 只有勾 → 700。
        assertEquals(874, intake.kcal)
        // 碳水/脂肪历史上没整餐值，所以只有明细那一份。
        assertEquals(38.9, intake.carbsG, 1e-9)
        assertEquals(0.45, intake.fatG, 1e-9)
    }

    @Test
    fun softDeletedMealIsIgnoredEntirely() {
        val intake = MealIntakeCalculator.compute(
            listOf(meal(1L, kcal = 550, completed = true), meal(2L, kcal = 700, completed = true, active = false)),
            listOf(item(2L, kcal = 400)),
        )
        assertEquals("「这餐不吃」之后，它下面的条目也不能再计入", 550, intake.kcal)
    }

    /**
     * 计算是**以餐为轴**的：只给条目、不给它所属的那餐，就不会被计入。
     *
     * 这不是缺陷而是必然 —— 软删一餐要靠"餐不在列表里"来排除它的条目（见上一个用例）。
     * 所以调用方必须把同一天、同一口径的 meals 与 items 一起传进来。
     */
    @Test
    fun itemsAreCountedThroughTheirMealSoBothListsMustAgree() {
        val orphan = listOf(item(9L, kcal = 200))
        assertEquals("餐不在列表里 → 条目不计入", 0, MealIntakeCalculator.compute(emptyList(), orphan).kcal)
        assertEquals(
            "同一批条目，餐在列表里就计入",
            200,
            MealIntakeCalculator.compute(listOf(meal(9L, kcal = 0, proteinG = 0.0)), orphan).kcal,
        )
    }
}
