package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.HabitRepository
import com.ironhabit.app.domain.repository.ReminderScheduler
import javax.inject.Inject

/**
 * 「删除习惯」用例 —— **软删除**。
 *
 * 落地为 `is_active = 0`，**不做物理 `DELETE`**，保留该习惯的历史日志关联，
 * 避免历史打卡记录出现悬空引用（schema-v2 §5）。
 *
 * 顺带撤掉它的闹钟：软删之后行还在，`rescheduleAll` 也只在开机 / 换时区 / 启动时才跑，
 * 不在这里取消的话，**当天剩下的时间里这条提醒照样会响**（孤儿闹钟）。
 *
 * @param habitId 习惯 id
 */
class DeleteHabitUseCase @Inject constructor(
    private val habitRepository: HabitRepository,
    private val reminderScheduler: ReminderScheduler,
) {

    suspend operator fun invoke(habitId: Long) {
        habitRepository.deleteHabit(habitId)
        reminderScheduler.cancelHabit(habitId)
    }
}

