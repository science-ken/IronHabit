package com.ironhabit.app.domain.model

/**
 * 一条食物在**某个具体克数**下的营养量。
 *
 * 这是要落进 `meal_items` 的快照值，不是"当前食物定义算出来的值" —— 见 [FoodNutrition]。
 */
data class FoodNutrition(
    val kcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

/**
 * 把"每 100g 定义"换算成"吃了这么多"的营养量。
 *
 * ## 为什么要单独一个纯函数
 * 这条换算会同时被三处用到：加入一餐时算快照、改份量时重算、以及以后可能的"复制这一餐"。
 * 抄三份就会像 `todayEpochDay()` 那样分叉（本项目刚为此把 6 份收成 1 份）。
 *
 * ## 为什么快照而不是查询时现算
 * 与 `CheckIn.weightKg` 同一个原则：**历史是历史，定义是定义**。
 * 用户把「米饭」从 116 kcal/100g 改成 130，三个月前那顿不该跟着变 ——
 * 否则趋势图会自己动，而那是最难查的一类 bug。
 */
object FoodNutritionCalculator {

    /** 每 100g 定义值 → [grams] 克的实际营养。 */
    fun forGrams(food: Food, grams: Double): FoodNutrition {
        val safeGrams: Double = if (grams.isFinite() && grams > 0.0) grams else 0.0
        val ratio: Double = safeGrams / GRAMS_PER_HUNDRED
        return FoodNutrition(
            kcal = (food.kcalPer100g * ratio).roundToIntSafe(),
            proteinG = food.proteinPer100g * ratio,
            carbsG = food.carbsPer100g * ratio,
            fatG = food.fatPer100g * ratio,
        )
    }

    /**
     * 按"份"换算：[servingCount] 份 × 该份的克数。
     *
     * 份数允许小数（半个鸡蛋 = 0.5 份），所以这里先乘成克数再走 [forGrams]，
     * 不单独写一套公式 —— 两处公式迟早会不一样。
     */
    fun forServings(food: Food, serving: FoodServing, servingCount: Double): FoodNutrition? {
        val gramsPerServing: Int = serving.grams.takeIf { it > 0 } ?: return null
        if (!servingCount.isFinite() || servingCount <= 0.0) return null
        return forGrams(food, gramsPerServing.toDouble() * servingCount)
    }

    private const val GRAMS_PER_HUNDRED: Double = 100.0

    /** 四舍五入到整数 kcal。`NaN` / 溢出统一收成 0，绝不让脏值进库。 */
    private fun Double.roundToIntSafe(): Int =
        if (isFinite() && this <= Int.MAX_VALUE && this >= Int.MIN_VALUE) Math.round(this).toInt() else 0
}
