package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.model.Meal
import com.ironhabit.app.domain.model.MealType
import com.ironhabit.app.domain.repository.MealRepository
import javax.inject.Inject

/**
 * 编辑一餐（改动内容 / 热量 / 蛋白）→ 置 `isUserEdited = true`（保护用户改动，禁止被重新生成覆盖）。
 *
 * 走**显式 upsert**（命中已存在行 → `UPDATE` 并**保留完成勾选**；未命中 → `INSERT`），
 * 且 `isActive = true`（若该餐此前被软删，编辑即视为"加回"）。**绝不使用 `REPLACE`**。
 *
 * @return 行 id
 */
class UpsertMealUseCase @Inject constructor(
    private val mealRepository: MealRepository,
) {

    suspend operator fun invoke(
        id: Long,
        epochDay: Long,
        mealType: MealType,
        items: List<String>,
        kcal: Int,
        proteinG: Double,
    ): Long {
        // 条目清洗：去空白、丢空行（与 MealMapper 同一口径），至少保留一个非空条目。
        val cleaned: List<String> = items.map { it.trim() }.filter { it.isNotEmpty() }
        return mealRepository.upsert(
            Meal(
                id = id,
                dateEpochDay = epochDay,
                mealType = mealType,
                items = cleaned,
                kcal = kcal.coerceAtLeast(0),
                proteinG = proteinG.coerceAtLeast(0.0),
                sortOrder = mealType.ordinal,
                isActive = true,
                isUserEdited = true,
            )
        )
    }
}
