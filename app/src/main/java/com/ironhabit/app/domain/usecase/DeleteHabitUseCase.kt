package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.HabitRepository
import javax.inject.Inject

/**
 * 「删除习惯」用例 —— **软删除**。
 *
 * 落地为 `is_active = 0`，**不做物理 `DELETE`**，保留该习惯的历史日志关联，
 * 避免历史打卡记录出现悬空引用（schema-v2 §5）。
 *
 * @param habitId 习惯 id
 */
class DeleteHabitUseCase @Inject constructor(
    private val habitRepository: HabitRepository,
) {

    suspend operator fun invoke(habitId: Long) {
        habitRepository.deleteHabit(habitId)
    }
}
