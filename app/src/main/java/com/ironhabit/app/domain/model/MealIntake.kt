package com.ironhabit.app.domain.model

/**
 * 某天"实际吃进去多少"的取数结果。
 *
 * @property coarseMealIds 只打了勾、没记明细的那几餐 —— 界面要标「约」，
 *   周复盘要能报"这周几顿是精确记的"（Q31=B）。
 *   把"哪些是估的"当成结果的一部分带出去，而不是让每个消费方自己重新判断一遍，
 *   否则磁贴说"约"而复盘说"精确"，同一份数据两种口径。
 */
data class MealIntake(
    val kcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    /** 有明细的餐数（用来显示"今天 N 顿是逐样记的"）。 */
    val preciseMeals: Int,
    /** 只打勾没记明细的餐 id。 */
    val coarseMealIds: Set<Long>,
) {

    /** 今天到底记没记过 —— 一条明细都没有、也没打过勾，就是没记。 */
    val hasAnyRecord: Boolean get() = preciseMeals > 0 || coarseMealIds.isNotEmpty()
}

/**
 * 一餐的实际营养怎么算（Q9 = **B**）。
 *
 * 三条规则，按顺序判：
 * 1. **有明细** → 各条目**数值快照**之和（精确）；
 * 2. 无明细但**打了勾** → 取那餐的整餐 `kcal` / `proteinG`（粗记，界面标「约」）；
 * 3. 无明细也没打勾 → **0**，包括"AI 生成过这一餐"的情况。
 *
 * ⚠️ 第 3 条是整件事的承重墙。AI 生成只写 `meals.items_text` + `meals.kcal`，
 * 并把 `is_completed` 保持为 false（`upsertGenerated` 刻意不碰它）。
 * 所以"排了餐"贡献 0 —— 这正是训练区 2026-09-20 把
 * `week_plans`（计划）与 `check_ins`（实际）分成两张表要保住的同一件事。
 * 任何"让生成完就算吃了"的改动都是 bug，不是优化。
 *
 * 纯函数、无 IO：这条算错会同时污染磁贴、周复盘和喂给 AI 的输入，必须可测。
 */
object MealIntakeCalculator {

    fun compute(meals: List<Meal>, items: List<MealItem>): MealIntake {
        val itemsByMeal: Map<Long, List<MealItem>> = items.groupBy { item -> item.mealId }

        var kcal = 0
        var protein = 0.0
        var carbs = 0.0
        var fat = 0.0
        var precise = 0
        val coarse = LinkedHashSet<Long>()

        for (meal in meals.filter { it.isActive }) {
            val mealItems: List<MealItem> = itemsByMeal[meal.id].orEmpty()
            if (mealItems.isNotEmpty()) {
                precise += 1
                for (item in mealItems) {
                    kcal += item.nutrition.kcal
                    protein += item.nutrition.proteinG
                    carbs += item.nutrition.carbsG
                    fat += item.nutrition.fatG
                }
            } else if (meal.isCompleted) {
                // 粗记：只有整餐级数字可用，宏量里的碳水/脂肪本来就没存，保持 0。
                coarse += meal.id
                kcal += meal.kcal
                protein += meal.proteinG
            }
        }

        return MealIntake(
            kcal = kcal,
            proteinG = protein,
            carbsG = carbs,
            fatG = fat,
            preciseMeals = precise,
            coarseMealIds = coarse,
        )
    }
}
