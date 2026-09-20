package com.ironhabit.app.data.mapper

import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.MealItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MealItemMapper] 的快照不变量 + 份量显示写法。
 *
 * 重点是一条**看起来无害、实际会骗人**的脏数据：
 * 有份数没单位（或反过来）。界面上会显示"150 g"，
 * 而库里其实存着"0.6 碗" —— 用户之后把一碗从 200g 改成 250g 时，
 * 这条历史到底按哪个算就没人说得清了。所以写库口子上强制成对。
 */
class MealItemMapperTest {

    private fun item(unit: String?, count: Double?, grams: Double = 150.0) = MealItem(
        id = 0L,
        mealId = 9L,
        foodId = 1L,
        foodName = "米饭（蒸）",
        grams = grams,
        servingUnit = unit,
        servingCount = count,
        nutrition = FoodNutrition(174, 3.9, 38.85, 0.45),
    )

    @Test
    fun unitAndCountSurviveTogether() {
        val entity = MealItemMapper.toEntity(item("碗", 1.0))
        assertEquals("碗", entity.servingUnit)
        assertEquals(1.0, entity.servingCount!!, 1e-9)
    }

    @Test
    fun countIsDroppedWhenUnitIsBlank() {
        val entity = MealItemMapper.toEntity(item("   ", 0.6))
        assertNull("单位空白时份数必须一起丢，否则存着一个算不回去的 0.6", entity.servingUnit)
        assertNull(entity.servingCount)
    }

    @Test
    fun countIsDroppedWhenUnitIsNull() {
        assertNull(MealItemMapper.toEntity(item(null, 2.0)).servingCount)
    }

    @Test
    fun nutritionFlattensIntoFourColumnsAndBack() {
        val entity = MealItemMapper.toEntity(item("碗", 1.0))
        assertEquals(174, entity.kcal)
        assertEquals(3.9, entity.proteinG, 1e-9)
        assertEquals(38.85, entity.carbsG, 1e-9)
        assertEquals(0.45, entity.fatG, 1e-9)

        val roundTripped = MealItemMapper.toDomain(entity)
        assertEquals("往返必须一模一样", 174, roundTripped.nutrition.kcal)
        assertEquals(38.85, roundTripped.nutrition.carbsG, 1e-9)
    }

    /** 反例长这样：把"一碗"改成 250g 之后，历史条目的显示与数值都跟着变了。 */
    @Test
    fun domainCarriesNoFoodReference_soEditsCannotRipple() {
        val entity = MealItemMapper.toEntity(item("碗", 1.0))
        // 快照列齐了 → 换算不需要回查 foods，这条断言是"历史不被改写"的结构性保证。
        assertEquals("米饭（蒸）", entity.foodName)
        assertEquals(174, entity.kcal)
        assertEquals(150.0, entity.grams, 1e-9)
    }

    @Test
    fun portionLabelPrefersServingThenFallsBackToGrams() {
        assertEquals("1碗", item("碗", 1.0).portionLabel)
        assertEquals("0.5碗", item("碗", 0.5).portionLabel)
        assertEquals("150 g", item(null, null).portionLabel)
        assertEquals("75.5 g", item("  ", null, grams = 75.5).portionLabel)
    }
}
