package com.ironhabit.app.domain.usecase

import com.ironhabit.app.domain.repository.ReminderScheduler
import com.ironhabit.app.domain.repository.ReminderType
import com.ironhabit.app.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * 应用每日提醒设置用例（架构 §4.3）。
 *
 * 读取最新设置后决定「排期」或「取消」训练提醒。
 * 习惯的按条提醒在编辑习惯时单独调度（见 `ReminderScheduler`），本用例只管全局训练提醒。
 */
class ScheduleReminderUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val reminderScheduler: ReminderScheduler,
) {

    suspend operator fun invoke() {
        val settings = settingsRepository.settings().first()
        if (settings.reminderEnabled) {
            reminderScheduler.schedule(
                type = ReminderType.TRAINING,
                hour = settings.reminderHour,
                minute = settings.reminderMinute,
            )
        } else {
            reminderScheduler.cancel(ReminderType.TRAINING)
        }
    }
}
