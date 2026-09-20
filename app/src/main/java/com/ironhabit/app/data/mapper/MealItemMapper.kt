package com.ironhabit.app.data.mapper

import com.ironhabit.app.data.local.entity.MealItemEntity
import com.ironhabit.app.domain.model.FoodNutrition
import com.ironhabit.app.domain.model.MealItem

/**
 * [MealItem] ↔ [MealItemEntity]。纯映射，不做业务判断。
 *
 * 快照四项在领域层收成一个 [FoodNutrition]、在表里摊平成四列 ——
 * 摊平是为了让"某天合计"能用一条 SQL 求和，收拢是为了调用方拿到的是一个整体
 * （不会出现"改了 kcal 忘了改 protein"的半更新）。
 */
object MealItemMapper {

    fun toDomain(entity: MealItemEntity): MealItem = MealItem(
        id = entity.id,
        mealId = entity.mealId,
        foodId = entity.foodId,
        foodName = entity.foodName,
        grams = entity.grams,
        servingUnit = entity.servingUnit?.takeIf { it.isNotBlank() },
        servingCount = entity.servingCount,
        nutrition = FoodNutrition(
            kcal = entity.kcal,
            proteinG = entity.proteinG,
            carbsG = entity.carbsG,
            fatG = entity.fatG,
        ),
        sortOrder = entity.sortOrder,
        createdAt = entity.createdAt,
    )

    fun toEntity(item: MealItem): MealItemEntity = MealItemEntity(
        id = item.id,
        mealId = item.mealId,
        foodId = item.foodId,
        foodName = item.foodName.trim(),
        grams = item.grams,
        servingUnit = item.servingUnit?.trim()?.takeIf { it.isNotEmpty() },
        // 单位没了，份数也必须跟着没：留着会造出"显示 150g 但其实记的是 0.6 碗"的错位。
        servingCount = item.servingUnit?.trim()?.takeIf { it.isNotEmpty() }?.let { item.servingCount },
        kcal = item.nutrition.kcal,
        proteinG = item.nutrition.proteinG,
        carbsG = item.nutrition.carbsG,
        fatG = item.nutrition.fatG,
        sortOrder = item.sortOrder,
        createdAt = item.createdAt,
    )
}
