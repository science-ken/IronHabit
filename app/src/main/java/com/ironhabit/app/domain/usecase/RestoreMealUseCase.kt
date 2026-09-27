package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.MealRepository
import javax.inject.Inject

/**
 * 恢复一餐 —— [DeleteMealUseCase] 的反向操作（「今日饮食」里那个「已删除 N 条 · 恢复」）。
 *
 * 库里那行从来没被真的删掉（软删保留 `UNIQUE(date_epoch_day, meal_type)` 槽位），
 * 所以恢复只是翻回 `is_active`：**id 不变**，挂在它下面的条目与打卡记录原样接上。
 * 这也是"删掉再重新生成一餐同名"不算恢复的原因 —— 重新生成拿到的是新行，
 * 而且 `is_user_edited = 1` 会让生成器跳过老行，两行会各说各话。
 *
 * 不动 `is_user_edited`：这餐被用户改过是事实，恢复之后重新生成仍然要跳过它。
 */
class RestoreMealUseCase @Inject constructor(
    private val mealRepository: MealRepository,
) {

    suspend operator fun invoke(mealId: Long) {
        mealRepository.restore(mealId)
    }
}
