package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.HabitRepository
import javax.inject.Inject

/**
 * 「习惯排序」用例。
 *
 * 按 [orderedIds] 的顺序依次写入 `sort_order`（`0, 1, 2, …`），
 * 由 Room 的 `ORDER BY sort_order, id` 决定列表展示次序（schema-v2 §5.1）。
 *
 * @param orderedIds 习惯 id 的目标次序
 */
class ReorderHabitsUseCase @Inject constructor(
    private val habitRepository: HabitRepository,
) {

    suspend operator fun invoke(orderedIds: List<Long>) {
        habitRepository.reorderHabits(orderedIds)
    }
}
