package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.FoodRepository
import javax.inject.Inject

/**
 * 首启播种内置食物库用例。
 *
 * 幂等：委托 [FoodRepository.seedBuiltIns]（同名已存在即跳过），
 * 重复启动不会产生重复食物；返回值 = 本次**新插入**的条数。
 *
 * `IronHabitApp` 在应用启动时于 `@ApplicationScope` 内无参调用本用例，
 * 与 [SeedExercisesUseCase] 并列、互不依赖 —— 一个失败不影响另一个。
 */
class SeedFoodsUseCase @Inject constructor(
    private val foodRepository: FoodRepository,
) {

    suspend operator fun invoke(): Int = foodRepository.seedBuiltIns()
}
