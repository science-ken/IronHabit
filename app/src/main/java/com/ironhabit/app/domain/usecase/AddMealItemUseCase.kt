package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.Food
import com.ironhabit.app.domain.model.FoodNutritionCalculator
import com.ironhabit.app.domain.model.FoodServing
import com.ironhabit.app.domain.model.InputLimits
import com.ironhabit.app.domain.model.MealItem
import com.ironhabit.app.domain.repository.FoodRepository
import com.ironhabit.app.domain.repository.MealItemRepository
import javax.inject.Inject
import kotlinx.datetime.Clock

/**
 * 往一餐里加一条"我吃了这个"。
 *
 * 用密封结果而不是抛异常或返回 `Long?`：调用方（ViewModel）要区分
 * "到上限了"和"份量不合法"两种情况给不同提示，糊成一个 `null` 就只能说一句"失败了"。
 */
sealed class AddMealItemResult {
    data class Added(val item: MealItem) : AddMealItemResult()
    /** 这一餐条目数已达 [InputLimits.MAX_ITEMS_PER_MEAL]。 */
    data object MealFull : AddMealItemResult()
    /** 食物不存在或已停用。 */
    data object FoodMissing : AddMealItemResult()
    /** 既没给可用的份、也没给合法克数。 */
    data object InvalidPortion : AddMealItemResult()
}

/**
 * 加条目用例。
 *
 * ## 快照在这里算，不在 UI 算
 * 加一条 = 把"每 100g 定义 × 这次的量"算成四个数字落库。这条换算必须只有一个入口，
 * 否则以后"改份量"那条路径会各自算一遍、迟早算出两个结果
 * （本项目刚为 `todayEpochDay()` 抄了 6 份付过代价）。
 *
 * ## 份优先于克
 * 传了 [serving] 就按"份数 × 每份克数"，并且**把份也快照进去**（界面要显示"1碗"而不是"150 g"）。
 * 两者都给时取份 —— 用户点的是"一碗"，克数只是它的换算结果。
 */
class AddMealItemUseCase @Inject constructor(
    private val foodRepository: FoodRepository,
    private val mealItemRepository: MealItemRepository,
    private val clock: Clock,
) {

    suspend operator fun invoke(
        mealId: Long,
        foodId: Long,
        serving: FoodServing? = null,
        servingCount: Double = 1.0,
        grams: Double? = null,
    ): AddMealItemResult {
        if (mealItemRepository.countByMeal(mealId) >= InputLimits.MAX_ITEMS_PER_MEAL) {
            return AddMealItemResult.MealFull
        }
        val food: Food = foodRepository.getFood(foodId)?.takeIf { it.isActive }
            ?: return AddMealItemResult.FoodMissing

        val resolvedServing: FoodServing? = serving ?: food.servings.firstOrNull()
        val nutrition = if (resolvedServing != null) {
            FoodNutritionCalculator.forServings(food, resolvedServing, servingCount)
        } else {
            grams?.let { FoodNutritionCalculator.forGrams(food, it) }
        } ?: return AddMealItemResult.InvalidPortion

        val actualGrams: Double = if (resolvedServing != null) {
            resolvedServing.grams * servingCount
        } else {
            grams ?: 0.0
        }
        // 按克直接填时才卡上限；按份填的克数由食物定义决定，已在表单侧校验过。
        if (resolvedServing == null && !InputLimits.isValidServingGrams(actualGrams.roundToIntOrZero())) {
            return AddMealItemResult.InvalidPortion
        }

        val item = MealItem(
            mealId = mealId,
            foodId = food.id,
            foodName = food.name,
            grams = actualGrams,
            servingUnit = resolvedServing?.unit,
            servingCount = resolvedServing?.let { servingCount },
            nutrition = nutrition,
            sortOrder = mealItemRepository.countByMeal(mealId),
            createdAt = clock.now().toEpochMilliseconds(),
        )
        val id = mealItemRepository.upsert(item)
        return AddMealItemResult.Added(item.copy(id = id))
    }
}

/** 非有限值统一收成 0，让上层按"越界"处理而不是抛异常。 */
private fun Double.roundToIntOrZero(): Int =
    if (isFinite() && this in 0.0..Int.MAX_VALUE.toDouble()) Math.round(this).toInt() else 0
