package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.MealRepository
import javax.inject.Inject

/**
 * 勾选 / 取消一餐（对应预览 `toggleMeal(i)`）。
 *
 * 只改 `is_completed`，**不触碰** `is_user_edited` / `is_active` —— 打卡是「今天吃没吃」的记录，
 * 不应把它标记成「用户改了计划内容」，否则下次重新生成会把它当手改行保护、不再更新内容。
 */
class ToggleMealUseCase @Inject constructor(
    private val mealRepository: MealRepository,
) {

    suspend operator fun invoke(mealId: Long, done: Boolean) {
        mealRepository.setCompleted(mealId, done)
    }
}
