package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import javax.inject.Inject

/**
 * 「恢复已删除习惯」用例 —— [DeleteHabitUseCase] 的反向操作。
 *
 * 落地为 `is_active = 1`。日志从来没被删过，所以恢复之后连续天数、热力图都原样接上 ——
 * 这也是为什么"删掉重建一条同名的"不算恢复：重建拿到的是**新 id**，老日志挂在旧 id 上再也找不回来。
 *
 * 闹钟必须重排：删除时 [DeleteHabitUseCase] 明确 `cancelHabit` 过，只翻 `is_active`
 * 的话，习惯回到了列表上、提醒却永远不再响（`rescheduleAll` 只在开机 / 换时区 / 启动时才跑）。
 *
 * @param habitId 习惯 id
 */
class RestoreHabitUseCase @Inject constructor(
    private val habitRepository: HabitRepository,
    private val reminderScheduler: ReminderScheduler,
) {

    suspend operator fun invoke(habitId: Long) {
        habitRepository.restoreHabit(habitId)
        reminderScheduler.rescheduleAll()
    }
}
