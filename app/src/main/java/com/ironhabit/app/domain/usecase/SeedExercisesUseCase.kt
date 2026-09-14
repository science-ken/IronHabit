package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.ExerciseRepository
import javax.inject.Inject

/**
 * 首启播种内置动作库用例。
 *
 * 幂等：委托 [ExerciseRepository.seedBuiltIns]（底层 `OnConflictStrategy.IGNORE`），
 * 重复启动不会产生重复动作；返回值 = 本次**新插入**的条数。
 *
 * `IronHabitApp` 在应用启动时于 `@ApplicationScope` 内无参调用本用例。
 */
class SeedExercisesUseCase @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
) {

    suspend operator fun invoke(): Int = exerciseRepository.seedBuiltIns()
}
