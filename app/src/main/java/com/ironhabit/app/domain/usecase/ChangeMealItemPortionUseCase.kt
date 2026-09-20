package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.FoodNutritionCalculator
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import javax.inject.Inject

/**
 * 改一条已记录条目的份量（记成两碗了，其实是一碗）。
 *
 * ## 快照要重算，但两种情况下算法不同
 * - **食物还在库里** → 按**当前定义**重算。用户此刻是在修正自己的记录，
 *   用他现在认的那份定义算才对。
 * - **食物已被删除**（`foodId == null` 或查不到）→ 没有定义可依，只能按
 *   `新克数 / 旧克数` **等比缩放**当时那份快照。
 *
 * 为什么要专门处理第二种：条目存快照正是为了"食物没了历史仍然读得懂"，
 * 那改它的份量也就必须仍然可改 —— 否则"删过食物的那几顿永远改不动"。
 *
 * @return 更新后的条目；`null` = 没改成（条目不存在，或份量不合法）。
 */
class ChangeMealItemPortionUseCase @Inject constructor(
    private val foodRepository: FoodRepository,
    private val mealItemRepository: MealItemRepository,
) {

    suspend operator fun invoke(
        itemId: Long,
        serving: FoodServing?,
        servingCount: Double = 1.0,
        grams: Double? = null,
    ): MealItem? {
        val existing: MealItem = mealItemRepository.getById(itemId) ?: return null

        val newGrams: Double = serving?.let { it.grams * servingCount } ?: grams ?: return null
        if (!newGrams.isFinite() || !InputLimits.isValidServingGrams(newGrams.roundToIntOrZero())) {
            return null
        }

        val food: Food? = existing.foodId?.let { id -> foodRepository.getFood(id) }
        val nutrition: FoodNutrition = if (food != null) {
            FoodNutritionCalculator.forGrams(food, newGrams)
        } else {
            scale(existing.nutrition, existing.grams, newGrams)
        }

        val updated = existing.copy(
            grams = newGrams,
            servingUnit = serving?.unit,
            servingCount = serving?.let { servingCount },
            nutrition = nutrition,
        )
        mealItemRepository.upsert(updated)
        return updated
    }

    /** 按克数等比缩放一份旧快照。旧克数不可用时一律归零，绝不返回 NaN。 */
    private fun scale(old: FoodNutrition, oldGrams: Double, newGrams: Double): FoodNutrition {
        if (!oldGrams.isFinite() || oldGrams <= 0.0) return FoodNutrition(0, 0.0, 0.0, 0.0)
        val ratio: Double = newGrams / oldGrams
        return FoodNutrition(
            kcal = Math.round(old.kcal * ratio).toInt(),
            proteinG = old.proteinG * ratio,
            carbsG = old.carbsG * ratio,
            fatG = old.fatG * ratio,
        )
    }

    private fun Double.roundToIntOrZero(): Int =
        if (isFinite() && this in 0.0..Int.MAX_VALUE.toDouble()) Math.round(this).toInt() else 0
}
