package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.MealRepository
import javax.inject.Inject

/**
 * **软删除**一餐（用户「这餐不吃」）。
 *
 * 🔒 只做 `UPDATE is_active = 0, is_user_edited = 1`，**禁止 `DELETE`**：
 * 软删保留 `UNIQUE(date_epoch_day, meal_type)` 槽位，且 `isUserEdited = 1`
 * 会让重新生成**跳过**它 —— **不会把用户删掉的餐复活**（设计文档 §2.3）。
 */
class DeleteMealUseCase @Inject constructor(
    private val mealRepository: MealRepository,
) {

    suspend operator fun invoke(mealId: Long) {
        mealRepository.delete(mealId)
    }
}
