package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.HabitRepository
import javax.inject.Inject

/**
 * 习惯勾选 / 取消用例。
 *
 * 直接委托 [HabitRepository.setLog]（内部幂等 upsert 并自算 `dateStartMillis`），
 * 因此本用例只负责转发，不含日期计算。
 */
class ToggleHabitUseCase @Inject constructor(
    private val habitRepository: HabitRepository,
) {

    /**
     * @param done `true` = 完成，`false` = 取消
     */
    suspend operator fun invoke(habitId: Long, epochDay: Long, done: Boolean) {
        habitRepository.setLog(habitId = habitId, epochDay = epochDay, done = done, note = null)
    }
}
